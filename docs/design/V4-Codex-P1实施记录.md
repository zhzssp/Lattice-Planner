# V4.0.0 · Codex P1 交付记录（验证闭环）

> 对应设计：`知识资产沉淀-P0至P2实施设计.md` §三「P1 · 验证闭环（护城河）」。
> 状态：**已实现，待真机验收**（本机无 JDK，未能执行编译与测试，见 §7）。

---

# 一、这一期解决什么

**「怎么证明你不是『看懂了』，而是『能改能跑』。」**

这是市面上任何知识管理软件都没有的能力：Notion/Obsidian 做存储与链接，
Anki 做记忆，都不做「证明你能改能跑」。

```
Guide → 检验条目 → 【先预测（不填则 run 锁定）】→ 受限执行 → 机器判定
  ├ 通过且预测对 → 掌握度 +1
  ├ 通过但预测错 → ★最高价值信号：结果对但因果理解错 → 建议沉淀笔记
  └ 失败 → 按「常见失败 → 盲点」回指 guide 章节
```

它让这个问题有了可回答的形式：

> 「你说你学会了 MLIR，证据是什么？」
> → 12 条检验通过 9 条其中 3 条 L2，预测准确率 67%，错的 4 条都沉淀了笔记。

---

# 二、零迁移：解析 86 条既有检验

目标仓库已有 **86 条**手写检验分布在 9 册 Markdown。若只支持声明式 front-matter，
用户得先把 86 条全部重写——这种「先交作业才能用」的设计会让功能永远停在纸面。

## 实测到的格式不一致（解析器必须全部容忍）

| 差异 | 实例 |
|---|---|
| 标题层级混用 | `01-llvm` 用 `###`，`08-distributed` 用 `####` |
| 分隔符 | 全角 `｜`（偶有半角 `|`） |
| 资源标签带修饰 | `**资源**：`单卡GPU`` / `**资源**：`本地`（纯 CPU，两个进程…）` |
| 段落标题带括注 | `**通过标准**（机器可判定）：` |
| 「常见失败」标题不统一 | `**常见失败 → 说明你哪里没懂**：` 等多种写法 |

**宁松勿严**（延续方案 E 立场）：解析不出某字段就留空，
**绝不因此丢掉整个条目**。`missingFieldsDoNotDropEntry` 专门守这条。

## 如实标注判据强度

解析出的判据只能是「退出码为 0」，达不到声明式 `expect` 的精确度。
因此一律标记 `verifySource=PARSED`，UI 上明确显示：

> 该条判据由原文 Markdown 解析推断，通常只能判「退出码为 0」。
> 机器判定通过**不等于**内容完全正确，仍应对照通过标准人工核对。

**不假装精确**——否则用户会以为「机器判过了就一定对」，
这比没有判定更危险。

## 命令提取的三个保守选择

| 选择 | 理由 |
|---|---|
| 含 `\|` `>` `&&` `;` `$()` `EOF` 的行**直接剔除** | 不做 shell 语义模拟。猜错的代价是在用户机器上执行了意料之外的命令 |
| 多条命令存进 `alternatives`，**不自动串联** | 同上 |
| `expect` 只放退出码，**不从中文通过标准猜关键词** | 中文标准常含「不应出现」「消失」等否定语，机械提取会产出方向相反的断言 |

---

# 三、五道安全闸门

受限执行是整个方案风险最高的一环——它在用户机器上跑真实命令。

| # | 闸门 | 实现 | 设计要点 |
|---|---|---|---|
| ① | **mode 隔离** | `exec` tag 仅在 `AgentMode.VERIFY` 的 allowTags | 复用方案 K 执行层强制，**不是仅从 prompt 隐藏**（提示层约束模型可无视） |
| ② | **命令白名单** | `CommandGuard`：白名单程序名 + shell 元字符一律拒绝 | 见下文「为什么是白名单」 |
| ③ | **路径沙箱** | cwd 与脚本须在仓库内（`toRealPath` 解析符号链接后比对）+ **须被 git 跟踪** | 字符串前缀判断会被符号链接绕出；未跟踪 = 无法审计来源 |
| ④ | **人工确认** | `requiresConfirm=true` + **硬编码禁止 auto-approve** | 见下文 |
| ⑤ | **运行时限制** | 超时强杀 / 输出 8KB 上限并标记 / **env 白名单** / 并发上限 1 | env 不透传全部：进程里有 `DEEPSEEK_API_KEY`，透给用户脚本等于泄漏凭证 |

## 为什么是白名单而非黑名单

黑名单永远列不全：`rm` 的变体、换行注入、变量展开、别名、
相对路径可执行文件……**漏一个就是完全绕过**。

白名单相反——漏了只会让某条合法命令跑不了，代价是「不够方便」而非「被攻破」。

`CommandGuardTest` 里每个「拒绝」用例都对应一种真实绕过手法：
`&&` 串联、`;`、管道、重定向、`$()`、反引号、`${}`、换行注入、
`./x` 相对路径、`/bin/bash` 绝对路径、`../` 逃逸、未跟踪脚本。

## auto-approve 硬例外（`ToolApprovalPolicy` 改动）

`checkpoint.run` **永远弹窗**，即便用户把它加进白名单。
且在**写入层剔除**而非只在 UI 禁用勾选——UI 禁用是提示层约束，可被绕过。

判断标准：*副作用是否发生在本应用数据之外*。建任务只影响自己的库，
用户想免确认是合理偏好；执行 shell 命令会触及文件系统与工具链，不可逆且难归因。

## 隔离强度的如实声明

**这里没有容器隔离。** 个人单机软件引入 Docker 依赖不现实，
且 lab 脚本依赖本机 conda 环境（目标仓库的 `setup.sh` 把 clang/mlir-opt 装进 conda），
容器内跑不通。

真实风险模型 ≈ **「用户自己在终端敲这条命令」**：在其本机、经其逐次确认、
跑其自己仓库里被 git 跟踪的脚本。`checkpoint.guard_info` 工具会把这段原话告知用户。

**如实说明比假装沙箱更负责。**

---

# 四、预测门禁的两个反作弊设计

这是全方案最容易被"优化掉"的一环，所以做成结构性保证而非配置项。

## 1. Agent 没有任何写入预测的通道

**`checkpoint.predict` 工具刻意不存在。**

理由：如果给 Agent 提交预测的工具，它一定会"贴心地"帮用户预测——
从模型视角这是在帮忙。但「先预测再动手」的全部价值在于
**暴露用户自己的心智模型**，AI 代填等于把机制彻底掏空。

预测的唯一入口是 `POST /api/codex/checkpoints/{id}/predict`（UI 表单）。

`VerifyDisciplineTest.NoPredictTool` 用反射扫描 `CheckpointTools` 的全部
`@AgentTool` 方法，断言不存在任何名含 `predict` 的写入工具，
并断言 `checkpoint.grade` 的参数里没有 `prediction`。
**将来若有人为了方便加上这个工具，测试会先红。**

## 2. 预测不可事后修改

`predictedAt` 一旦写入即冻结，重复提交返回 **409** 而非静默覆盖。
UI 上转为只读展示并标注冻结时间。

理由很直接：预测可事后修改的话，`predictionAccuracy` 这个指标就失去全部意义。

## 「通过但预测错」的判定

无法完全自动判定（预测是自然语言），设计为**半自动 + 裁判可追溯**：

- Agent 判定 → 记 `predictionJudge=AI`
- 用户在面板判定 → 记 `predictionJudge=USER`（权威，可覆盖 AI 结论）
- 判「预测错」时 `divergence` **必填**，否则返回 `DIVERGENCE_REQUIRED`
  ——说不出差异在哪的判定没有复盘价值

指标口径必须能区分二者，否则 `predictionAccuracy` 没有解释力。

---

# 五、交付清单

## 新增（14 个文件）

| 类型 | 路径 |
|---|---|
| 迁移 | `db/migration/V9__codex_checkpoint.sql` |
| 实体 | `codex/entity/KbCheckpoint.java`、`KbCheckpointRun.java` |
| 仓储 | `codex/repository/KbCheckpointRepository.java`、`KbCheckpointRunRepository.java` |
| 验证核心 | `codex/verify/CheckpointParser.java`（解析 86 条既有检验） |
| | `codex/verify/CommandGuard.java`（闸门 ②③） |
| | `codex/verify/CheckpointRunner.java`（闸门 ⑤ + 断言判定） |
| | `codex/verify/CheckpointService.java`（编排 + 预测门禁） |
| | `codex/verify/CheckpointSyncListener.java`（事件解耦） |
| | `codex/verify/VerifyMetrics.java` |
| 工具 | `codex/tool/CheckpointTools.java`（6 个工具，**无 predict**） |
| 控制器 | `codex/controller/CheckpointController.java` |
| 页面 | `resources/templates/checkpoint.html` |
| 测试 | `agenteval/unit/CheckpointParserTest.java`、`CommandGuardTest.java`、`VerifyDisciplineTest.java`（约 70 用例） |

## 改动（6 处）

| 文件 | 改动 | 兼容性 |
|---|---|---|
| `ToolApprovalPolicy` | 新增 `NEVER_AUTO_APPROVE_PREFIXES` 硬例外 + 写入层剔除 | 普通工具行为不变 |
| `RepoSyncService` | 索引成功后发 `RepoIndexedEvent` | 监听器异常被 catch，不影响索引 |
| `CodexViewController` | 新增 `/codex/checkpoints` 路由 | — |
| `ObservabilityController` | 新增 `verify` 分区 + `/api/codex/verify/stats` | 既有分区不变 |
| `application.properties` | 新增 `codex.verify.*` 配置段 | 总开关默认 `false` |
| `codex.html` | 增加检验面板入口 | — |

---

# 六、可观测指标（`/api/codex/verify/stats`）

```json
{
  "config": {
    "enabled": true,
    "requirePrediction": true,
    "allowedExecutables": ["bash", "python", "..."]
  },
  "metrics": {
    "runs": 12, "passed": 9, "passRate": 0.75,
    "passedByLevel": {"L0": 4, "L1": 3, "L2": 2},
    "predictionsSubmitted": 12,
    "blockedNoPrediction": 4,
    "rejectedUnsafeCommands": 0,
    "predictionJudged": 9,
    "predictionCorrect": 6,
    "predictionAccuracy": 0.667,
    "mispredicted": 3
  }
}
```

三个数字最值得盯：

| 指标 | 判读 |
|---|---|
| `blockedNoPrediction` | **门禁是否在起作用**。需结合 `predictionsSubmitted` 判断：都为 0 说明没被用过；只有前者为 0 才说明用户守纪律 |
| `rejectedUnsafeCommands` | **白名单必要性实证**。长期为 0 说明它是冗余保险；一旦不为 0，就是「只做提示层不够」的实证（同方案 D 的 `bannedToolCallsBlocked`） |
| `predictionAccuracy` | **别处拿不到的指标**。预测先于结果冻结，无法事后造假。但 AI 判定有误判，引用时须说明来源 |

`config` 回显是刻意加的：看到 `predictionAccuracy=0.667` 却不知道门禁开没开，
这个数字就没有解释力。

---

# 七、未验证事项（如实声明）

**本机未安装 JDK**（`JAVA_HOME` 未设，常见位置均无 javac，Gradle wrapper 无法启动），
因此以下**均未执行**：

- `./gradlew compileJava`
- `./gradlew test`（含本期新增约 70 个用例）
- `./gradlew agentEval`（cassette 回放回归）
- 针对 `AI-Infra` 的端到端验收（§8）

已做的替代验证：
- IDE 语言服务诊断：`src/` 全树 **0 error**
- 人工交叉审计：新增字段/方法签名一致性、`ToolApprovalPolicy` 改动的既有调用方、
  测试引用的实体方法（Lombok `@Data` 生成的 setter 已核对）
- 命名冲突检查：`CheckpointTools.detail` 重载已改名为 `detailOf`，避免误读

**请在有 JDK 的环境执行 §8 后再合并。**

---

# 八、验收清单（需真机执行）

前置：跑 `V8` + `V9` 迁移；配置 `codex.enabled=true`、`pkm.rag.git.enabled=true`、
`codex.verify.enabled=true`。

| # | 验收项 | 判定标准 |
|---|---|---|
| 1 | **解析 86 条** | `/codex/checkpoints` 点「从检验册重新解析」→ 9 册解析出约 86 条；code 与各册标注数对得上（12+12+11+11+10+9+8+13） |
| 2 | 格式容忍 | `08-distributed`（`####` 四级标题）的 13 条全部被识别 |
| 3 | **预测门禁** | 未填预测点「运行验收」→ 按钮锁定；直接 POST `/run` → 返回 `PREDICTION_REQUIRED`，`kb_checkpoint_run` **无新行** |
| 4 | **无代填通道** | 工具列表中不存在 `checkpoint.predict`（`/agent/settings/tools` 核对） |
| 5 | 预测冻结 | 重复 POST `/predict` → 返回 409，内容未被覆盖 |
| 6 | **安全闸门** | 6 类恶意命令全部拒绝且未执行：`rm -rf /`、`bash ../../x.sh`、`x && curl`、未跟踪脚本、仓库外 cwd、`$(whoami)` |
| 7 | **mode 隔离** | `chat` / `study` / `curate` 模式下 `checkpoint.run` 返回 `TOOL_NOT_VISIBLE` |
| 8 | auto-approve 例外 | 把 `checkpoint.run` 加入白名单 → 保存后查库，该项已被剔除 |
| 9 | **端到端** | `L0-MLIR-01`：填预测 → 冻结 → 运行 → 机器判定 → 状态流转 → 判定预测一致性 |
| 10 | 超时与截断 | 造 `sleep 999` 脚本 → 600s 后 `timed_out=1`；造大量输出 → 截断至 8KB 且 `output_truncated=1`，内容类断言被判不可靠 |
| 11 | 降级 | `codex.verify.enabled=false` 时索引行为与 P0 完全一致（事件监听器直接返回） |
| 12 | **无回归** | `./gradlew test` + `./gradlew agentEval` 全绿 |

第 3、4、6 条是本期核心价值；第 11 条验证事件解耦；第 12 条验证不破坏存量。

---

# 九、边界（要主动说清的）

1. **判据强度受限于源格式**。`PARSED` 来源只能判退出码，
   已在 UI 与工具返回里明示，但用户仍可能忽略。根治要等 P2 之后支持声明式
   front-matter 定义。

2. **预测一致性判定会误判**。LLM 判断自然语言一致性没有确定答案，
   所以允许用户覆盖并记录裁判来源。引用 `predictionAccuracy` 时必须说明
   「其中多少是 AI 判的」。

3. **无容器隔离**（见 §三末）。这是有意识的取舍，不是遗漏。

4. **单并发**。编译类任务并行只会互相拖慢并让耗时数据失真；
   更重要的是并发会让「哪条检验改了工作副本」无法归因。
   代价是不能同时跑多条。

5. **`git status` 会被检验执行改变**。跑 lab 脚本会产生 `build/` `out/` 等产物，
   这会让仓库变脏，进而阻止后续的 `repo.sync --pull`。
   当前依赖用户仓库的 `.gitignore` 覆盖这些产物（目标仓库已覆盖）。

---

# 十、下一步

P2（沉淀 + PR + 知识 CI）。本期已为它铺好两处地基：

- **失败/误判信号已在采集**：`predictionCorrect=false` 与 `divergence`
  正是 P2 里 `SEDIMENTER` 最好的沉淀原料——
  「我原以为…实际…」记下的是心智模型被修正的瞬间，是最难得的学习素材。
- **事件解耦范式已建立**：`RepoIndexedEvent` 的做法可直接复用于
  「索引后触发知识 CI」。
