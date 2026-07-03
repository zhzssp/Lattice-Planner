# Agent 工具授权（Auto-Approve）设计与执行计划

> 目标：在 Agent 聊天面板中新增「设置」栏，支持用户配置 **auto-approve 工具白名单**——
> 勾选的工具在 Agent 调用时不再弹窗确认，直接放行。设计保留后续扩展其他权限控制项的空间。
> 适用分支：`V3.0.0`。

---

## 一、现状分析

### 1.1 现有确认链路（每次都弹窗）

```
AgentOrchestrator.handleUserTurn
  → def.requiresConfirm()              // 来自 @AgentTool 注解，编译期固定
    → ToolConfirmCoordinator.askUser   // 发 WS confirmReq，阻塞等 future
      → 前端 addConfirm() 渲染[允许][拒绝]
        → 用户点击 → WS confirmReply
          → ToolConfirmCoordinator.onReply → future.complete
```

- 子代理 `SubAgentRunner.doRun` 中也有一份同样的 `requiresConfirm → confirm.askUser` 逻辑。
- 需确认的工具当前有：`kb.ingest_local_doc`、`kb.delete_local_doc`（`requiresConfirm=true`），以及本地文件类工具。

### 1.2 关键结论

- **`requiresConfirm` 是工具的固有属性**，不能改（改了对所有用户生效，破坏安全默认值）。
- 正确做法：在「决定是否弹窗」这一步之前，**插入一个用户级 auto-approve 策略判断**——
  即 `requiresConfirm && !autoApproved(tool)` 才弹窗。
- 已有 `UserPreference` 实体可复用做持久化载体，无需新建表结构体系（仅加字段）。

---

## 二、设计方案

### 2.1 数据模型

在 `UserPreference` 增加一个字段存储自动允许的工具名清单（逗号分隔或 JSON 字符串）：

```java
/** Agent 自动允许（免确认）的工具名清单，逗号分隔，如 "kb.ingest_local_doc,kb.delete_local_doc" */
@Column(name = "agent_auto_approve_tools", length = 2000, nullable = true)
private String agentAutoApproveTools;
```

- 迁移脚本：`db/migration/V6__agent_auto_approve.sql`
  ```sql
  ALTER TABLE user_preference
    ADD COLUMN agent_auto_approve_tools VARCHAR(2000) NULL;
  ```
- 选逗号分隔而非独立关联表：工具数量小（<50），读写简单，后续扩展权限项时再评估是否升级为 JSON。

### 2.2 策略层：`ToolApprovalPolicy`（新增）

新建 `feature/agent/policy/ToolApprovalPolicy.java`，集中判定"某工具对某用户是否需要弹窗确认"：

```java
@Component
public class ToolApprovalPolicy {
    // 依赖 UserPreferenceService（或 Repository）读取当前用户的 auto-approve 集合
    /** 需要弹窗确认 = 工具本身 requiresConfirm && 未被用户加入 auto-approve。 */
    public boolean needsConfirm(User user, ToolDefinition def) { ... }
    /** 读取用户 auto-approve 工具集（供设置面板回显）。 */
    public Set<String> autoApprovedTools(User user) { ... }
    /** 覆盖写入用户 auto-approve 工具集。 */
    public void updateAutoApproved(User user, Set<String> tools) { ... }
}
```

- 主循环与子代理都改为：`if (policy.needsConfirm(user, def)) { ... askUser ... }`。
- `user` 从 `AgentContext.requireUser()` 取，天然多用户隔离。
- 只对 `requiresConfirm=true` 的工具生效；`requiresConfirm=false` 的工具本就不弹窗，auto-approve 对其无意义（但清单里可含，无副作用）。

### 2.3 REST 接口（供设置面板读写）

新增 `AgentSettingsController`（`feature/agent/controller/`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/agent/settings/tools` | 返回全部「可确认工具」清单 + 当前用户已勾选的 auto-approve 集合 |
| PUT  | `/agent/settings/auto-approve` | 覆盖保存用户勾选的工具名数组 |

响应示例（GET）：
```json
{
  "confirmableTools": [
    {"name":"kb.ingest_local_doc","description":"摄取本地文档…"},
    {"name":"kb.delete_local_doc","description":"反摄取本地文档…"}
  ],
  "autoApproved": ["kb.ingest_local_doc"]
}
```
- `confirmableTools` 来源：`ToolRegistry.all()` 中 `requiresConfirm==true` 的工具（含 description 供 UI 展示）。
- 鉴权：走现有登录态（`Principal` → `UserRepository.findByUsername`）。

### 2.4 前端：设置栏

在 `agent-panel.html` header 增加一个「设置」按钮（齿轮图标），点击弹出一个面内浮层/抽屉：

```
[Lattice-Agent]  [模式▾]  [⚙设置]  [×]
```

设置浮层内容（首期只放「自动允许工具」）：
```
自动允许的工具（勾选后调用免确认）
  ☑ kb.ingest_local_doc  摄取本地文档进知识库
  ☐ kb.delete_local_doc  反摄取本地文档
  [保存]
```

- 打开设置时 `GET /agent/settings/tools` 拉取清单与已勾选项渲染复选框。
- 点保存时 `PUT /agent/settings/auto-approve`，body 为勾选的工具名数组。
- 保留浮层结构以便后续加更多权限控制项（如"高危操作二次确认""每次会话重置"等）。
- 样式加进 `chat-panel.css`，风格与现有面板一致。

### 2.5 行为语义

- auto-approve 命中：后端**不发 `confirmReq`**，直接执行工具。为可观测，仍通过 `toolStart` 卡片可见该工具被调用（前端无需改）。
- 未命中或未配置：行为与现在完全一致（弹窗确认）。
- 默认值：新用户 auto-approve 集合为空 → 保持"全部需确认"的安全默认。

---

## 三、执行计划（按依赖顺序）

### 阶段 1：数据与持久化
1. `UserPreference` 新增 `agentAutoApproveTools` 字段。
2. 新增迁移脚本 `db/migration/V6__agent_auto_approve.sql`。
3. 确认/新增 `UserPreference` 的读取服务（若已有 `UserPreferenceService` 则复用，否则用 Repository）。

### 阶段 2：后端策略层
4. 新增 `ToolApprovalPolicy`：实现 `needsConfirm` / `autoApprovedTools` / `updateAutoApproved`（含逗号串 ↔ Set 的解析与去重）。
5. 改 `AgentOrchestrator`：`if (def.requiresConfirm())` → `if (policy.needsConfirm(user, def))`。
6. 改 `SubAgentRunner`：同上替换，`user` 取 `AgentContext.requireUser()`。

### 阶段 3：后端接口
7. 新增 `AgentSettingsController`：`GET /agent/settings/tools`、`PUT /agent/settings/auto-approve`。
8. `ToolRegistry` 暴露"可确认工具清单"（或在 Controller 内过滤 `all()` 的 `requiresConfirm`）。

### 阶段 4：前端设置栏
9. `agent-panel.html`：header 加「⚙设置」按钮 + 设置浮层容器。
10. `chat-panel.js`：设置浮层的打开/关闭、GET 拉取渲染复选框、PUT 保存。
11. `chat-panel.css`：设置浮层样式。

### 阶段 5：联调与验证
12. 场景验证：
    - 勾选 `kb.ingest_local_doc` → 再次触发该工具应**不弹窗**直接执行。
    - 取消勾选 → 恢复弹窗。
    - 多用户隔离：A 的配置不影响 B。
    - 子代理路径同样遵守 auto-approve。
13. 边界：清单里含已下线/不存在的工具名 → 忽略不报错；`requiresConfirm=false` 的工具勾选无副作用。

---

## 四、影响面与风险

| 项 | 说明 |
|----|------|
| 兼容性 | 新字段可空、默认空集合，存量用户行为不变（全部仍需确认）。 |
| 安全 | auto-approve 是**用户显式**勾选的主动降权，默认关闭；`requiresConfirm=false` 工具不受影响。 |
| 改动范围 | 后端 2 处替换（主循环 + 子代理）+ 1 策略类 + 1 Controller + 1 实体字段 + 1 迁移；前端 3 文件。 |
| 可扩展 | 设置浮层与 `ToolApprovalPolicy` 均预留扩展位，后续可加"会话级临时允许""高危分级"等。 |

---

## 五、后续可选增强（本期不实现）

- **会话级"本次会话记住我的选择"**：确认弹窗上加「本次会话都允许」快捷项。
- **权限分级**：把工具按危险度分级（读/写/本地/删除），按级别批量授权。
- **审计**：记录 auto-approve 命中的工具调用日志。
