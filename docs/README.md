# 文档索引

> 全仓文档导航。**时效**列说明这篇是否随代码同步维护——查资料前先看这一列，避免拿旧数字做决策。
>
> 最近一次整理：2026-09-01（合并了 4 份重复文档，校正了一批与代码对不上的数字）。

## 时效标记含义

| 标记 | 含义 | 怎么用 |
|---|---|---|
| 🟢 **活跃** | 随代码同步维护，数字已校对 | 可直接引用 |
| 🟡 **快照** | 记录某期完成时的状态，不再更新 | 读设计意图可以，**数字别信** |
| 🔵 **规划** | 尚未实现或部分实现的方案 | 是"想做什么"不是"做了什么" |
| ⚪ **归档** | 已被取代或对应功能已下线 | 仅供追溯历史 |

---

## 一、面试与讲解（`docs/`）

| 文档 | 用途 | 时效 |
|---|---|---|
| [面试讲解手册.md](面试讲解手册.md) | **主文档**。话术、10 个创新点、6 个踩坑、边界划分、数字速记 | 🟢 |
| [保研面试PPT大纲.md](保研面试PPT大纲.md) | PPT 逐页内容 + 给 PPT AI 的指令。**只管"放什么"，话术在手册里** | 🟢 |
| [主循环流程图-面试讲解.md](主循环流程图-面试讲解.md) | 单图深讲：ReAct 骨架、三重闸门、子代理怎么开 | 🟢 |
| `主循环流程图.drawio` | 上一篇的矢量图源文件 | 🟢 |

> 这四篇之前是 7 篇——`保研面试PPT内容参考`、`亮点枚举`、`保研面试PPT-重点细化` 三份都在描述同一份 PPT，已合并进 `保研面试PPT大纲.md`。

## 二、使用与验收（`docs/`）

| 文档 | 用途 | 时效 |
|---|---|---|
| [Agent评测体系使用指南.md](Agent评测体系使用指南.md) | 怎么跑评测、怎么录制、**怎么判断报告可信**、上下文工程基准 | 🟢 |
| [Agent量化评测体系-工业界方案对标与引入设计.md](design/Agent量化评测体系-工业界方案对标与引入设计.md) | **为什么**：对标 τ-bench / pass^k / RAGAS，及刻意不引入什么 | 🟡 |
| [Agent量化评测-P0至P5实施设计.md](design/Agent量化评测-P0至P5实施设计.md) | **怎么做**：P0~P4 已实施（含实施中改过的决定），P5 待做 | 🟢 |
| [功能验收方案.md](功能验收方案.md) | 各能力的手工验收步骤与断言。**P11 档验收「量表本身准不准」**，附 C-5 三条已查出未修的产品缺陷 | 🟢 |

## 三、Agent 核心设计（`docs/design/`）

| 文档 | 用途 | 时效 |
|---|---|---|
| [Lattice-Agent功能总览.md](design/Lattice-Agent功能总览.md) | **入口文档**。分层结构、64 个工具、关键配置、已知限制 | 🟢 |
| [Agent实现方案.md](design/Agent实现方案.md) | 逐文件实现方案（"为什么这么做"） | 🟡 |
| [Agent优化方案候选.md](design/Agent优化方案候选.md) | 优化方向候选池，评测体系出自方案 A | 🔵 |

> `Lattice-Agent-功能完整说明.md` 已并入 `Lattice-Agent功能总览.md`（两份重叠且都已过时）。

## 四、上下文工程（`docs/design/`）

| 文档 | 用途 | 时效 |
|---|---|---|
| [上下文工程-滚动摘要与Facts层设计.md](design/上下文工程-滚动摘要与Facts层设计.md) | 设计与取舍 | 🟢 |
| [上下文工程P1-滚动摘要与Facts层实施记录.md](design/上下文工程P1-滚动摘要与Facts层实施记录.md) | **实施记录 + 遗留项 + 实测边界** | 🟢 |
| [Agent上下文工程-Token预算与分级压缩设计.md](design/Agent上下文工程-Token预算与分级压缩设计.md) | P2 方向：token 预算与分级压缩 | 🔵 |
| [Agent-Token计量与会话成本预算设计.md](design/Agent-Token计量与会话成本预算设计.md) | 成本预算设计 | 🔵 |
| [长期记忆机制补全实现报告.md](design/长期记忆机制补全实现报告.md) | 长期记忆归档 | 🟡 |

> **实测数字的唯一权威来源**是 `build/agent-eval/context-engineering.md`（跑 `gradlew test --tests '*ContextEngineeringBenchmark*'` 生成）。文档里的数字是它的摘录。

## 五、Agent 可控性（`docs/design/`）

| 文档 | 用途 | 时效 |
|---|---|---|
| [Agent工具运行时可见性-分层遮蔽设计.md](design/Agent工具运行时可见性-分层遮蔽设计.md) | 四层 scope 链、deny/pin、执行层强制 | 🟢 |
| [Agent轮次收尾解耦-TurnStopping设计.md](design/Agent轮次收尾解耦-TurnStopping设计.md) | 收尾顾问 steer + 粘性降级标记 | 🟢 |
| [Agent工具授权-AutoApprove设计与执行计划.md](design/Agent工具授权-AutoApprove设计与执行计划.md) | 授权策略与运行时降权 | 🟢 |
| [Lattice-Agent-SubAgent设计方案.md](design/Lattice-Agent-SubAgent设计方案.md) | 子代理三角色、并行 fan-out、递归防护 | 🟢 |
| [Agent多模型多提供方-模型切换与路由设计.md](design/Agent多模型多提供方-模型切换与路由设计.md) | 模型路由与加权轮询 | 🟢 |

## 六、知识库与 RAG（`docs/design/`）

| 文档 | 用途 | 时效 |
|---|---|---|
| [PKM-RAG实现方案.md](design/PKM-RAG实现方案.md) | Hybrid 检索设计 | 🟡 |
| [PKM-RAG实施成果.md](design/PKM-RAG实施成果.md) | 落地结果 | 🟡 |
| [AI-Infra-CRAG-SelfRAG实现计划.md](design/AI-Infra-CRAG-SelfRAG实现计划.md) | CRAG 状态机 | 🟢 |
| [AI-Infra-RAG-Serving-System实现计划.md](design/AI-Infra-RAG-Serving-System实现计划.md) | 语义缓存、精排、预取 | 🟢 |
| [AI-Infra-Prefix-KV-Cache实现计划.md](design/AI-Infra-Prefix-KV-Cache实现计划.md) | 前缀缓存与字节稳定化 | 🟢 |

## 七、MCP（`docs/design/`）

| 文档 | 用途 | 时效 |
|---|---|---|
| [Lattice-MCP-Server设计方案.md](design/Lattice-MCP-Server设计方案.md) | 对外暴露工具与资源 | 🟢 |
| [Lattice-MCP-Client设计方案.md](design/Lattice-MCP-Client设计方案.md) | 连接远程 Server + **loopback** | 🟢 |
| [本地文档-MCP信息来源-实现规划.md](design/本地文档-MCP信息来源-实现规划.md) | 本地文件从 Electron 桥迁到 MCP | 🟡 |

> ⚠️ 早期文档里的 `local.list_dir` / `local.read_file` / `local.read_pdf` **已下线**，现统一走 `mcp.loopback.local.read_document`。

## 八、知识仓库 Codex 五期（`docs/design/`）

| 文档 | 期 | 时效 |
|---|---|---|
| [知识资产沉淀-产品方案.md](design/知识资产沉淀-产品方案.md) | 总纲 | 🟡 |
| [知识资产沉淀-Git仓库形态产品方案V2.md](design/知识资产沉淀-Git仓库形态产品方案V2.md) | 总纲 V2 | 🟢 |
| [知识资产沉淀-P0至P2实施设计.md](design/知识资产沉淀-P0至P2实施设计.md) | P0~P2 设计 | 🟢 |
| [V4-Codex-P0实施记录.md](design/V4-Codex-P0实施记录.md) | P0 索引 | 🟡 |
| [V4-Codex-P1实施记录.md](design/V4-Codex-P1实施记录.md) | P1 验证闭环 | 🟡 |
| [V4-Codex-P2实施记录.md](design/V4-Codex-P2实施记录.md) | P2 沉淀 + CI | 🟡 |
| [V4-Codex-P3实施记录.md](design/V4-Codex-P3实施记录.md) | P3 缺口三源 | 🟡 |
| [V4-Codex-P4实施记录.md](design/V4-Codex-P4实施记录.md) | P4 蒸馏 + 定线 | 🟡 |
| [V4-Codex-P5体系化设计.md](design/V4-Codex-P5体系化设计.md) | P5 SYNTHESIZER | 🔵 |

## 九、其他

| 文档 | 用途 | 时效 |
|---|---|---|
| [主动式Agent-晨报晚报.md](design/主动式Agent-晨报晚报.md) | 定时推送 | 🟢 |
| `copyright/` | 软著申请材料（综合说明文档、源码片段、截图） | ⚪ |

---

## 维护约定

1. **数字必须可复现**。文档里报的每个数字都应能指向一条命令或一个端点。做不到就别写具体数字，写"约"或干脆不写。
2. **改了配置就回来改文档**。最容易漂的是 `Lattice-Agent功能总览.md` §12 配置表和 `面试讲解手册.md` 附录——这两处是数字集中地。
3. **写实施记录时同步标时效**。新写的实施记录默认 🟡（它记录的是那一期完成时的状态），只有承诺持续维护的才标 🟢。
4. **发现重复就合并，别新开一篇**。这次整理合并掉 4 篇，起因都是"当时觉得新写一篇比改旧的快"。
