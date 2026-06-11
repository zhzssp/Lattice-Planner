# 主动式 Agent · 晨报 / 晚报推送

> 本次改进让 Lattice-Planner 从「用户想起来才打开」变成「每天主动找用户两次」。
> 核心思路：**不造新轮子**——复用已有的 *事件链 / Insight 评分 / AI 复盘（RAG）/ Electron 桌面通知*，
> 仅补上「定时调度 + 时间窗闸门 + 客户端拉取弹窗」这条最短链路。

---

## 0. TL;DR

| 维度 | 内容 |
|---|---|
| **新增能力** | 晨报（每早提醒今日可推进任务 + 激励）、晚报（每晚 Insight 得分 + AI 复盘 + 明日建议），到点自动弹桌面通知 |
| **交付形态** | 后端新增 `feature/report` 模块 + 1 处启动注解 + 1 处安全白名单 + 7 项配置；Electron 端复用现成通知链路，新增一个轮询函数 |
| **复用率** | 晚报内容 100% 复用 `InsightScoreService` + `AiSummaryService`；晨报复用 `TaskService.getTodayActionableTasks`；弹窗复用 `main.js` 的 `Notification` |
| **零新表** | 不引入任何数据库迁移，推送状态以进程内内存维护（个人/小规模应用足够） |

---

## 1. 功能说明

### 1.1 晨报（Morning）
- **触发**：默认每天 08:00–11:00 窗口内，客户端首次轮询时推送一次。
- **内容**：今日可行动任务数量 + 按「时间段 / 精力」排序的清单（最多 8 条）+ 每日轮换的激励语。
- **无任务时**：友好提示「先给自己定一个今天最想完成的小目标」。

### 1.2 晚报（Evening）
- **触发**：默认每天 21:00–24:00 窗口内，客户端首次轮询时推送一次。
- **内容**：
  - 今日 Insight 得分（0–100）+ 计划/完成任务数、新增笔记数；
  - **AI 复盘**：调用 `AiSummaryService`，结合近 7 天趋势与 RAG 检索到的个人笔记给出针对性总结（自带超时 + 本地兜底降级）；
  - **明日建议**：根据剩余待办给出明天优先项。

### 1.3 桌面通知交互
- 标题 + 一句话摘要弹出系统通知；
- **点击通知**：唤起并聚焦主窗口；
- 完整 Markdown 全文通过 `daily-report` 通道转发给渲染进程，供应用内展示。

---

## 2. 架构与数据流

```
                     ┌──────────────── Electron 客户端 (main.js) ─────────────────┐
                     │  每 60s: getLoginState()                                    │
                     │    └─ 已登录 → checkTasksDue()  (原 DDL 提醒, 不变)         │
                     │              → checkDailyReport() ★新增                     │
                     │                   │  GET /report/pending (带 Cookie)        │
                     └───────────────────┼─────────────────────────────────────────┘
                                         ▼
        ┌──────────────────────── Spring Boot 后端 ────────────────────────────┐
        │  ReportController  GET /report/pending /morning /evening              │
        │        │                                                              │
        │        ▼                                                              │
        │  ProactiveReportService  (有状态: 时间窗 + 每日一次闸门 + 缓存)        │
        │        │  命中窗口 & 当日未送达 → 取缓存或即时生成                     │
        │        ▼                                                              │
        │  DailyReportService  (无状态内容生成器)                               │
        │     ├─ buildMorning → TaskService.getTodayActionableTasks            │
        │     └─ buildEvening → InsightScoreService.calculateScores            │
        │                      + AiSummaryService.summarizeScores(…, user)     │
        │                                   └─ RagSearchService (个人笔记上下文) │
        │                                                                       │
        │  DailyReportScheduler  @Scheduled(cron)                              │
        │     └─ 到点为「最近在线用户」预生成报告 → 写入 ProactiveReportService  │
        └───────────────────────────────────────────────────────────────────────┘
```

**为什么是「客户端拉取 + 时间窗」而非服务端直推**：服务端无法主动连到某个特定的 Electron
客户端，而客户端本就每 60s 带 Cookie 轮询。沿用此模型可零改造复用现成的桌面通知链路，
服务端只需按「时间窗 + 每日一次」闸门决定是否在某次轮询里返回报告。

---

## 3. 新增 / 修改清单

### 3.1 后端新增模块 `feature/report`

| 文件 | 角色 |
|---|---|
| `dto/DailyReport.java` | 报告载体 record：`type / title / body / detail / generatedAt`，`none()` 表示无推送 |
| `service/DailyReportService.java` | **无状态**内容生成器：`buildMorning(user)` / `buildEvening(user)`，全量复用现有服务，内含容错降级 |
| `service/ProactiveReportService.java` | **有状态**协调器：进程内按用户维护「已送达日期 + 预生成缓存 + 最近活跃时间」，实现时间窗 + 每日一次闸门 |
| `scheduler/DailyReportScheduler.java` | `@Scheduled` 定时为「最近在线用户」预生成报告（尤其晚报含 LLM 调用，预生成避免阻塞轮询线程） |
| `controller/ReportController.java` | `GET /report/pending`（轮询入口）/`/morning`/`/evening`（应用内即时查看、调试） |

### 3.2 后端改动

| 文件 | 改动 |
|---|---|
| `MemorandumApplication.java` | 增加 `@EnableScheduling`（原已有 `@EnableAsync`） |
| `config/WebSecurityConfig.java` | CSRF 白名单加入 `/report/**`（与 `/due-dates` 一致） |
| `resources/application.properties` | 新增 `report.proactive.*` 共 7 项配置 |

### 3.3 Electron 客户端改动

| 文件 | 改动 |
|---|---|
| `electron-app/main.js` | 新增 `checkDailyReport()`（复用同一 `Notification`），挂入 60s 轮询；新增 `shownReportKeys` 客户端去重并随每日清理；点击通知唤起主窗口 |
| `electron-app/preload.js` | 暴露 `latticePlanner.onDailyReport(callback)` 订阅接口；监听 `daily-report` 通道 |

---

## 4. 关键设计决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **拉取 + 时间窗**而非服务端直推 | 服务端无法定位单个 Electron 客户端；复用现成 60s 轮询，零改造接入 |
| 2 | **每日一次闸门**（per-user 已送达日期） | 同一窗口内 60s 多次轮询只推一次；跨天自动重置 |
| 3 | **Scheduler 预生成 + 缓存** | 晚报含 LLM 调用（数秒~数十秒），预生成避免阻塞轮询请求线程；轮询命中缓存即时返回 |
| 4 | **只为「最近活跃用户」预生成** | 未开客户端的用户不浪费 LLM 调用；在线用户持续轮询，到点必在活跃集合内 |
| 5 | **即时生成兜底** | 即便 Scheduler 未跑（如刚重启），命中窗口且当日未送达仍会即时补生成——预生成只是性能优化，非正确性前提 |
| 6 | **状态仅存内存、零新表** | 个人/小规模应用足够；重启后用户下次轮询自动重新登记，体验无损 |
| 7 | **全链路容错降级** | 任务/评分/LLM 任一异常都被 `safe*` 包装吞掉并给兜底文案，绝不中断推送 |
| 8 | **客户端二次去重** | `type:日期` 键防止服务端重启边界下的重复弹窗 |

---

## 5. 配置手册（`application.properties`）

```properties
report.proactive.enabled=true                  # 总开关
report.proactive.morning.window-start-hour=8   # 晨报窗口 [start, end)
report.proactive.morning.window-end-hour=11
report.proactive.evening.window-start-hour=21  # 晚报窗口 [start, end)，end=24 表示直到当天结束
report.proactive.evening.window-end-hour=24
report.proactive.morning.cron=0 0 8 * * *      # Scheduler 预生成时刻（秒 分 时 日 月 周）
report.proactive.evening.cron=0 0 21 * * *
report.proactive.active-window-minutes=15      # 「最近活跃」判定窗口
```

> 复用的 `agent.llm.summary-timeout-seconds`（默认 60s）控制晚报 AI 复盘的等待上限，超时自动回退本地规则摘要。

---

## 6. 接口一览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/report/pending` | 需登录（Cookie） | 客户端轮询：按窗口 + 每日一次返回报告，否则 `type=none`；同时登记在线活跃 |
| GET | `/report/morning` | 需登录 | 应用内即时查看 / 调试，无窗口与去重限制 |
| GET | `/report/evening` | 需登录 | 同上 |

`DailyReport` 响应示例：

```json
{
  "type": "evening",
  "title": "晚间复盘 · 今日 78 分",
  "body": "今天稳步推进，完成了 4/5 件，继续保持。",
  "detail": "## 今日晚报 · 2026-06-11\n\n**今日得分：78 / 100**\n\n- 计划任务：5 件，已完成：4 件\n...",
  "generatedAt": "2026-06-11T21:00:12.345"
}
```

---

## 7. 验证步骤

1. **即时验证内容**（不必等到点）：登录后浏览器直接访问
   `GET /report/morning`、`GET /report/evening`，确认返回完整 `detail`。
2. **窗口触发验证**：把 `report.proactive.evening.window-start-hour` 临时改成当前小时，
   启动 Electron 客户端登录，约 60s 内应弹出晚报系统通知；点击通知唤起主窗口。
3. **每日一次验证**：弹出后继续等待，下一次 60s 轮询不再重复弹窗（服务端闸门 + 客户端去重双保险）。
4. **降级验证**：把 `agent.llm.api-key` 置空或断网，晚报仍能弹出（AI 复盘回退本地规则摘要）。
5. **预生成验证**：观察日志 `[DailyReport] 预生成晚报，活跃用户数=N`，确认 Scheduler 到点为在线用户预生成。

---

## 8. 与现有文档的定位关系

- `Lattice-Agent功能总览.md` —— 产品全貌（用户视角）
- `Agent实现方案.md` —— Agent 运行时 / 工具机制
- `PKM-RAG实施成果.md` —— 个人知识库 RAG 交付
- **本文档** —— 主动式晨报/晚报的设计、代码地图、配置与验证路径

---

## 9. 后续可拓展方向

1. **习惯模块（#1）**：新增 `Habit` 实体（`recurrenceRule / streak / lastCheckInAt`）+ 周期生成器（挂本次的 `DailyReportScheduler`），让晨报「每天有事可做」的内容供给更丰富，并纳入 Insight 评分。
2. **报告落库**：将每日报告持久化为历史，支持「回看过去某天的晨/晚报」。
3. **应用内 Inbox**：渲染进程消费 `daily-report` 通道，在页面顶部做一个报告卡片/红点。
4. **多端推送**：在纯 Web（无 Electron）场景下，用 WebSocket 或前端轮询同一 `/report/pending` 端点复用本套闸门。
5. **个性化时间**：把窗口/cron 从全局配置下沉为用户偏好（`UserPreference`），实现「按个人作息推送」。
