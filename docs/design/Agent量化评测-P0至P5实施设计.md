# Agent 量化评测体系 · P0~P5 实施设计（细化）

> 承接 `Agent量化评测体系-工业界方案对标与引入设计.md`（那份讲"为什么"与"对标了谁"）。
> **本文是可直接开工的实施级设计**：真实结构核实 → 阻塞性前提 → 类清单 → 逐期改动点 → 验收 → 排期。
>
> 现状核查结论见 `docs/Agent评测体系使用指南.md` §6。一句话：
> **现有评测验证的是链路能跑通，不是模型决策正确。**

---

# 零、先摆真实结构（这决定了下面所有设计）

动笔前逐个读了现有 harness 与实体，有四处和"想当然的写法"不一致，写错任何一处设计都会在编译期或运行期崩：

| 项 | 真实值 | 影响 |
|---|---|---|
| `Task` 实体表名 | **`memo`**，不是 `task` | 端状态 SQL 必须写 `from memo` |
| 任务截止字段 | **`deadline`**，类型 `LocalDateTime` | 不是 `due_date`，且带时分秒，比对要按日期截断 |
| `Goal` 实体表名 | `goal`，标题字段是 **`name`** 不是 `title` | — |
| `AgentTraceListener.onLlmCall` | 签名只有 `(sessionId, step, prefixHash)` | **拿不到 token usage** → 触发 BLOCKER-4 |

现有 harness 的关键结构（改造的落点）：

> 下方为**改造前**的骨架快照，保留以对照 §八 的最终类清单。

```
agenteval/
  AgentEvalBase.java          @SpringBootTest + H2 + 5 个 @MockitoBean，已有 JdbcTemplate
  AgentEvalConfig.java        @Primary 覆盖 LlmTransport，record/replay 二选一
  cases/AgentTrajectoryEvalTest.java    9 个用例
  trace/CollectingTraceListener.java    事件流收集，有 toolSequence/failedTools/resultsOf
  trace/TrajectoryAssert.java           流式断言 DSL，失败时渲染完整轨迹
  report/EvalReport.java                进程内单例 + shutdown hook 输出
  cassette/Cassette.java                扁平 interactions[]，(caseId, callIndex) 寻址
  transport/{Recording,Replay}LlmTransport.java
```

**这套骨架的质量是够的**——可替换点设在 `LlmTransport`（HTTP 边界）是对的，trace 事件流也足够丰富。缺的**只是判分粒度**，所以下面全部是"在现有骨架上加断言与聚合"，不推倒重来。

---

# 一、四个阻塞性前提（必须先修，否则对应期交付即残废）

## BLOCKER-1 · `EvalReport.record()` 签名不支持多试次

```52:52:src/test/java/org/zhzssp/memorandum/agenteval/report/EvalReport.java
public void record(String caseId, CollectingTraceListener trace,
```

`caseId` 是聚合主键，同一用例记两次会变成两行独立结果。而 `pass^k` 的定义就是"同一任务 k 次全过"，**必须能按 `(caseId, trial)` 分组**。

### 修法

`CaseResult` 增加 `trial` 字段，聚合时先 `groupBy(caseId)` 再算：

```java
public void record(String caseId, int trial, CollectingTraceListener trace,
                   List<String> driftWarnings, long elapsedMs) { ... }
```

`trial` 默认 0，P1 之前所有调用点传 0，**行为逐字节不变**。这是 P1 的前置，但改动放在 P0 一起做（只是加个参数，不改逻辑）。

## BLOCKER-2 · `Cassette` 是扁平结构，装不下多试次

```31:31:src/test/java/org/zhzssp/memorandum/agenteval/cassette/Cassette.java
private List<LlmInteraction> interactions = new ArrayList<>();
```

回放按 `(caseId, callIndex)` 寻址。多试次后同一 caseId 有 k 条互不相同的轨迹，扁平数组无法表达。

### 修法（向后兼容，不作废现有 9 个盒子）

新增可选字段 `trials`，**保留 `interactions` 作为 trial 0 的别名**：

```java
public class Cassette {
    private List<LlmInteraction> interactions;      // 旧格式 = trials[0]，继续可读
    private List<List<LlmInteraction>> trials;      // 新格式，null 时回退到 interactions

    public List<LlmInteraction> trial(int i) {
        if (trials != null && i < trials.size()) return trials.get(i);
        return i == 0 ? interactions : null;        // 旧盒子只有 trial 0
    }
}
```

`ReplayLlmTransport.beginCase(caseId)` 扩成 `beginCase(caseId, trial)`。旧盒子在 `trial>0` 时返回 null → 回放层抛"该用例只录了 1 次，无法算 pass^k"，**明确报错而不是静默降级**。

## BLOCKER-3 · H2 是 `MODE=MySQL` 但不是 MySQL，端状态 SQL 有方言风险

评测 H2 URL 带 `MODE=MySQL;NON_KEYWORDS=USER`。端状态断言若写 MySQL 特有函数（`DATE_FORMAT`、`STR_TO_DATE`）会在 H2 上炸。

### 修法

`EvalDbProbe` **只用最朴素的 SQL**（`select ... from memo where user_id = ?`），日期比对在 **Java 侧**做，不下推到 SQL：

```java
// 好：取出 LocalDateTime，在 Java 比对
row.deadline().toLocalDate().equals(LocalDate.of(2026, 9, 4))

// 坏：DATE(deadline) = '2026-09-04'  ← H2/MySQL 行为可能不一致
```

顺带避免了"deadline 带时分秒导致等值比对永远为假"这个坑。

## BLOCKER-4 · 拿不到 token usage，P5 成本指标无处取数

`AgentTraceListener.onLlmCall(sessionId, step, prefixHash)` 没有 usage 参数。而 `Cassette.LlmInteraction` **存了 `responseUsageJson`**，回放时也会原样返回——数据是有的，只是没人接。

### 修法（关键取舍：不改生产接口）

**在评测侧的 transport 里累计 usage**，而不是给 `AgentTraceListener` 加方法：

```java
// ReplayLlmTransport / RecordingLlmTransport 共同持有
public final class UsageAccumulator {
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong cachedTokens = new AtomicLong();
    void observe(String usageJson) { ... }
    void reset();
    UsageSnapshot snapshot();
}
```

**为什么这么选**：给 `AgentTraceListener` 加 `onLlmUsage` 会改生产接口，所有实现类都要跟着动，而这个需求纯粹是测试侧的。传输层本来就是唯一看得见完整响应体的地方，放这里 blast radius 最小。这和"把可替换点设在 HTTP 边界"是同一条思路的延续。

---

# 二、P0 · 端状态验证（不需要 API Key）· ✅ 已实施

> **目标**：把断言从"工具被调用过"升级为"世界被正确改变了"。
> **这是唯一一个不花钱、不依赖外部就能让信息量翻倍的改造，务必先做。**
>
> **实施结果**：9/9 绿，端状态覆盖 100%。反向验证通过（注释掉 `ensureUserRow()` 后
> 全部走写路径的用例当场变红）。实施中有三处偏离原设计，见 §2.6。

## 2.1 新增 `EvalDbProbe`

位置：`agenteval/db/EvalDbProbe.java`

```java
/**
 * 端状态探针：只读地检查评测跑完后 H2 里的真实数据。
 *
 * <p>借鉴 τ-bench：判分不看模型说了什么，看世界被改成了什么样。
 * 任何能产生等价末态的路径都算通过，不要求走特定轨迹。
 *
 * <p>只用最朴素的 SQL，日期比对放在 Java 侧 —— H2 的 MODE=MySQL 不是真 MySQL。
 */
public record EvalDbProbe(JdbcTemplate jdbc, Long userId) {

    public record TaskRow(Long id, String title, String description,
                          LocalDateTime deadline, String status) {
        /** 截止日期（按日比对，忽略时分秒）。 */
        public LocalDate deadlineDate() {
            return deadline == null ? null : deadline.toLocalDate();
        }
    }

    public record GoalRow(Long id, String name, String goalType, LocalDateTime archivedAt) {}

    public List<TaskRow> tasks() {
        return jdbc.query(
                "select id, title, description, deadline, status from memo where user_id = ?",
                (rs, i) -> new TaskRow(
                        rs.getLong("id"), rs.getString("title"), rs.getString("description"),
                        rs.getObject("deadline", LocalDateTime.class), rs.getString("status")),
                userId);
    }

    public List<GoalRow> goals() { /* from goal where user_id = ? */ }

    public boolean anyTask(Predicate<TaskRow> p) { return tasks().stream().anyMatch(p); }
    public int taskCount() { return tasks().size(); }
    public int goalCount() { return goals().size(); }
}
```

## 2.2 `TrajectoryAssert` 增加端状态入口

现有 DSL 是 `assertThat(trace)`，只持有 trace。端状态需要 probe，因此**加一个重载入口**而不是改现有构造：

```java
// 新增
public static TrajectoryAssert assertThat(CollectingTraceListener trace, EvalDbProbe db)

// 新增断言方法
public TrajectoryAssert endState(String description, Predicate<EvalDbProbe> p) {
    if (db == null) fail("该用例未提供 EvalDbProbe，无法做端状态断言");
    if (!p.test(db)) fail("端状态断言未通过：" + description + "\n实际任务：" + db.tasks());
    return this;
}
```

**失败消息必须打印实际行**——沿用现有 DSL"断言失败即渲染完整轨迹"的原则，端状态失败时同样要能一眼看出库里到底写进了什么。

## 2.3 全局不变量：防止假绿复发

在 `AgentEvalBase.tearDownEvalContext` 里**无条件执行**：未声明预期失败的用例，
只要 `trace.failedTools()` 非空就抛 `AssertionError`，并附上完整轨迹与库末态。

豁免入口做成**用例内显式调用**而非子类覆写：

```java
// 用例体内，紧邻断言
expectToolFailure("task.search");
```

**为什么不用 `protected Set<String> expectedToolFailures()` 覆写**（原设计的写法）：
覆写是**类级**的，会给整个测试类开口子——同类里新加的用例会悄悄继承这份豁免，
门禁随时间被稀释。而工具失败的豁免必须精确到单个用例。
写在用例体内还顺带让"豁免声明"和"它豁免的那条断言"彼此相邻，读代码时一眼可见。

> **这条不变量本身就是 P0 最大的价值**：它把"假绿"从"要人去翻 report.json 才发现"变成"当场红"。这正是项目一以贯之的"门禁替代自觉"。

## 2.4 9 个用例的断言升级

| 用例 | 现有断言 | 增补端状态断言 |
|---|---|---|
| `create_task_basic` | `calledTool("task.create")` | 库里确实多了一条 title 含"验收文档"、`deadlineDate()` 为本周五的任务 |
| `query_tasks` | 工具调用数上限 | **任务数不变**（只读意图不得写库）← 负向断言，同样重要 |
| `kb_search_hit` / `degraded` | CRAG 元信息 | 任务/目标数均不变 |
| `tool_error_recovery` | 不泄漏异常栈 | 任务数不变（操作不存在的 id 不应产生副作用） |
| `no_tool_hallucination` | `noHallucination()` | 任务数不变 |
| `mode_isolation_learn` | `didNotCallTool` | **库里没有新任务** ← 这条才是真正的隔离验证 |
| `no_internal_leak` | 不含 think 段 | — |
| `prefix_stability_within_turn` | 前缀 hash 唯一 | 目标与任务的最终状态符合预期 |

**注意 `mode_isolation_learn` 那条**：覆盖度核查发现它现在是自我实现的（录制盒里模型压根没尝试调用）。加了端状态断言后，即便将来重录时模型真的尝试调用，只要可见性拦截生效，库里就不会有新任务——**断言这才第一次有了守护对象**。

## 2.5 P0 验收标准 · 实测结果

- [x] 9 个用例全部补上端状态断言，`gradlew agentEval` 全绿（9/9）
- [x] `report.json` 增加 `trial` / `endStateChecked` 字段与 `assertionStrength` 分区，
      控制台对已校验末态的用例打 `[E]` 标
- [x] **反向验证**：注释掉 `ensureUserRow()` 后 `create_task_basic` 与
      `prefix_stability_within_turn` 变红（改造前这两个用例在同样的损坏环境下是**绿的**）

> **验收标准本身有一处需要修正**：原写"至少 3 个用例变红"是拍脑袋定的。
> 实测全套件只有 **2 个用例真正走写路径**（其余 7 个是检索/只读/无工具调用），
> 所以 2/2 已经是**全部可能被捕获的用例都被捕获了**，覆盖率 100%。
> 正确的验收口径应是「所有走写路径的用例必须变红」，而不是一个凭空的绝对数字。
>
> 这也暴露了一件事：**当前用例集严重偏向只读场景**，写路径样本太少。
> 这属于任务集设计问题，由 P2 的负例/正例配比补齐。

## 2.6 实施中偏离原设计的三处

| # | 原设计 | 实际实施 | 原因 |
|---|---|---|---|
| 1 | 覆写 `expectedToolFailures()` | 用例内调 `expectToolFailure(...)` | 覆写是类级豁免，粒度太粗，见 §2.3 |
| 2 | 每用例清库列在 P1 | **提前到 P0** | 见下 |
| 3 | 验收「≥3 个用例变红」 | 实测 2 个，即全部写路径用例 | 见 §2.5 |

**关于第 2 条**：`cleanBusinessData()` 原本排在 P1（多试次隔离才需要），
实施时发现 P0 就必须要它。评测库是 `jdbc:h2:mem:agenteval;DB_CLOSE_DELAY=-1`，
**整个 JVM 共享一份**，用例之间数据会残留。`nothingWritten()` 这类负向断言
会被上一个用例建的任务污染，而且**污染方向是让断言失败**——
更糟的是执行顺序一变结论就跟着变。端状态断言的前提是可复现的基线。

**另一个实施期的发现**：`tool_error_recovery` 的失败原因是
`DateTimeParseException: Text '999999' could not be parsed`——
模型把任务 id 塞进了 `task.search` 的 `from`（日期）参数。
这本就是该用例要考察的 Reflexion 场景，因此顺手把断言从
「收敛且不泄漏异常栈」加强为 `calledToolTimes("task.search", 2)`，
即**要求它确实重试过**，而不是失败后直接放弃也算过。

---

# 三、P1 · 真实录制 + pass@k / pass^k

> **前置**：BLOCKER-1、BLOCKER-2 已修。
> **目标**：让收敛率/幻觉率恢复字面含义，并首次获得**可靠性**指标。
>
> **实施状态**：**基础设施已全部完成并通过验证**（12 个新单元测试）；
> **真实录制待执行**——需 `DEEPSEEK_API_KEY`，见 §3.3。
> 实施中有四处偏离原设计，其中一处是概念性的，见 §3.5。

## 3.1 多试次运行

用 **自定义 `@EvalTrial` + JUnit `@TestTemplate`** 展开试次，替代原设计里的 `@RepeatedTest`：

```java
@EvalTrial            // 取代 @Test
@DisplayName("create_task_basic")
void create_task_basic() { ... }
```

**为什么不能用 `@RepeatedTest(TRIALS)`**（原设计的写法）：注解参数必须是**编译期常量**，
读不到系统属性。而试次数必须能在命令行调整——日常回归跑 1 次求快，
发版前跑 5 次看稳定性，不该需要改代码重新编译。

`EvalTrialExtension` 实现 `TestTemplateInvocationContextProvider`，
按 `-Dagent.eval.trials=k` 展开 k 次调用，并通过 ThreadLocal 把试次号交给基类。
每次调用都完整走一遍 `@BeforeEach` / `@AfterEach`，所以轨迹、会话记忆、数据库都是干净的
——**这是 pass^k 有效的前提**：试次之间若共享状态，失败会彼此相关，算出的可靠性偏乐观。

> 每用例清库（`cleanBusinessData()`）已在 P0 提前实施，此处直接复用。
> 会话 id 也按试次区分（`eval-<case>-t<n>`），避免会话记忆跨试次串味。

## 3.2 指标定义（写进报告的口径）

设任务 $i$ 在 $k$ 次试验中通过 $c_i$ 次：

```
pass@k = |{ i : c_i ≥ 1 }| / N      能力：至少成功一次
pass^k = |{ i : c_i = k }| / N      可靠性：每次都成功
```

**报告必须同时列出两者**，差值即不稳定度。只报 `pass@k` 是粉饰——单次成功率 75% 的 agent，`pass@3` 是 98.4% 而 `pass^3` 只有 42%。

实现落在 `report/ReliabilityMetrics.java`，**刻意做成纯函数而非埋在报告聚合里**：
这段算术必须能被单独测试——**指标算错比没有指标更糟，因为它会让人放心**。
`ReliabilityMetricsTest` 用 6 个用例覆盖了全过、全败、时好时坏、k=1、空输入，
其中最关键的一条是「时好时坏的用例计入 pass@k 但不计入 pass^k」——
两个指标若算得一样，说明实现把「能力」和「可靠」混为一谈了。

另有 `CassetteTest`（6 条）守住旧格式兼容，其中
「旧格式的 `trial(1)` 必须返回 null 而不是回退到 trial 0」是 pass^k 正确性的地基，
理由见 §3.4。

## 3.3 录制操作（待执行，需 API Key）

```powershell
# PowerShell 下 -D 参数必须加引号，否则会被拆成 "-Dagent" 和 ".eval.trials=3"
$env:DEEPSEEK_API_KEY = "sk-xxx"
./gradlew agentEval "-Dagent.eval.mode=record" "-Dagent.eval.trials=3"

# 录完后回放，此时 pass^3 才有意义
./gradlew agentEval "-Dagent.eval.trials=3"
```

| 事项 | 要求 | 原因 |
|---|---|---|
| **temperature 不设 0** | 用生产实际值 | 设 0 人为压掉方差，`pass^k` 退化成 `pass^1`，指标失去意义 |
| 每试次独立录制 | `trials[0..k-1]` 各存一份 | 否则回放时 k 次结果相同，方差仍为 0 |
| 录完人工抽检 | 至少看 `kb_search_degraded` | 若模型本身行为不对，那是 prompt 要改，不是测试要改 |
| 成本 | 9 任务 × 3 试次 ≈ 27 次调用 | 可忽略 |

## 3.4 拒绝"用旧盒子凑 pass^k"

拿只录了 1 次的旧盒子跑 `trials=3` 时，`ReplayLlmTransport` **直接抛错**，
而不是回退到 trial 0 重放三遍：

```
用例 kb_search_hit 只录制了 1 次试验，无法回放第 2 次。
pass^k 要求每次试验都是独立录制的轨迹，不能靠重放同一条来凑数。
请以录制模式补录（PowerShell 下参数要加引号）：
  gradlew agentEval "-Dagent.eval.mode=record" "-Dagent.eval.trials=3"
```

**回退看似"容错"，实则是最坏的选择**：它会让 k 次试验结果完全相同、方差恒为 0，
`pass^k` 静默退化成 `pass^1`。报告上会出现一个漂亮且完全虚假的可靠性数字——
这比测试直接失败危险得多，也正是 P0 那条「假绿」教训的同一类错误。

## 3.5 P1 验收 · 实测结果

**已完成（无需 API Key 即可验证的部分）**

- [x] `Cassette` 支持 `trials[][]`，旧格式 `interactions[]` 仍可读（`CassetteTest` 6 条）
- [x] `pass@k` / `pass^k` 算术正确（`ReliabilityMetricsTest` 6 条）
- [x] `-Dagent.eval.trials=3` 展开成 27 次调用（9 用例 × 3 试次），
      其中 18 次（试次 2、3）按设计**明确报错**而非静默重放
- [x] 报告新增 `reliability` 区分与 `distinctCases` / `trialsPerCase`；
      `trials=1` 时 `passAtK == passHatK` 并附提示说明测不出稳定性
- [x] 9 个用例在 `trials=1` 下仍全绿——向后兼容

**待执行（需 API Key）**

- [ ] 用真实 API 录制 9 个用例 × 3 试次
- [ ] 回放确认 `pass@3` 与 `pass^3` **不相等**
      （若相等，说明方差被压掉了，需回头查 temperature 是否被设成 0）

## 3.6 实施中偏离原设计的四处

| # | 原设计 | 实际实施 | 原因 |
|---|---|---|---|
| 1 | `@RepeatedTest(TRIALS)` | 自定义 `@EvalTrial` + `@TestTemplate` | 注解参数须为编译期常量，读不了系统属性 |
| 2 | — | `resolveCaseId` 改读 `@DisplayName` 注解 | 见下 |
| 3 | — | 新增 `TestWatcher` 回填成败 | **概念性缺口**，见下 |
| 4 | — | `RecordingLlmTransport` 跨试次累积同一个盒子 | 每试次新建会导致后一次写盘覆盖前一次 |

**关于第 2 条**：原先 `resolveCaseId` 取 `TestInfo.getDisplayName()`，
而多试次模式下它会变成 `"试次 2/3"` 这种**调用级**名称，
拿它当录制盒文件名会去加载一个根本不存在的盒子。改为直接读方法上的
`@DisplayName` 注解，绕开调用级命名。

**关于第 3 条（原设计漏掉的概念性缺口）**：
`EvalReport` 原先只记录 `converged`，而 **`converged` ≠ `passed`**——
Agent 完全可以正常收敛、却在端状态或答复内容上断言失败。
`pass^k` 要的恰恰是后者，而断言结果只有 JUnit 知道，
`@AfterEach` 执行时结论尚未产生。
因此加了 `TestWatcher`，在用例生命周期结束后调 `EvalReport.markOutcome(caseId, trial, passed)` 回填。
**原设计直接写"按 caseId 分组算 pass^k"，跳过了"pass 从哪来"这个问题。**

---

# 四、P2 · 轨迹指标分解 · ✅ 已实施

> **目标**：从"调过某工具"升级到可量化的选择/顺序质量，能定位"为什么不对"。

## 4.1 任务声明：`GoldenTask`

位置：`agenteval/golden/GoldenTask.java`

```java
public record GoldenTask(
        String caseId,
        Set<String> expectedTools,      // 期望调用的工具集
        Set<String> forbiddenTools,     // 明确不该调的（负例，防过度调用）
        List<String> referenceOrder,    // 参考顺序，仅多步任务需要；空表示不检查顺序
        int maxRedundantCalls
) {
    // 链式构造：GoldenTask.of("x").expecting("a").forbidding("b")
    // inOrder(...) 会自动把工具计入期望集——声明了顺序却不算期望是最易犯的配置错
}
```

实际实现比原设计**少了三个字段**，都是有意删的：

| 删掉的字段 | 原因 |
|---|---|
| `instruction` / `mode` | 与 `runTurn("...", "chat")` 重复。同一事实写两处，早晚不一致，而不一致时没有任何机制会报错 |
| `endState` | 端状态已由 P0 的 `TrajectoryAssert.endState(...)` 承担。搬进 `GoldenTask` 只是换了个地方写，并不增加约束力，反而分裂成两套入口 |

**借鉴 τ-bench 的关键立场**：`referenceOrder` 是**一条参考路径，不是唯一正确路径**。只在有真实数据依赖时启用。把它当唯一解会让评测退化成"是否复现了我写的那条路径"，那测的是相似度不是正确性。

落到本项目：13 个用例里**只有 1 个声明了参考顺序**（`complete_existing_task`，必须先 `search` 拿到 id 才能 `complete` 它）。像 `prefix_stability_within_turn` 的 `goal.list` + `task.create` 之间没有数据依赖，先查目标还是先建任务都对，就刻意不声明顺序。

## 4.2 指标计算

位置：`agenteval/report/TrajectoryMetrics.java`

```
设 A = 实际调用序列（含重复），Aset = 其去重集合，E = expectedTools

工具选择精确率 = |Aset ∩ E| / |Aset|    惩罚多调
工具选择召回率 = |Aset ∩ E| / |E|       惩罚漏调
冗余调用数     = A 中工具不属于 E 的调用次数
禁用工具触发数 = A 中工具属于 forbiddenTools 的次数    任何非 0 都应判红
顺序一致性 τ   = Kendall's τ(两边都出现的工具, referenceOrder)
```

**分解的意义在于三种错法的修法完全不同**：漏调多半是工具描述不清，多调多半是提示词鼓励了过度行动，顺序错才是规划能力问题。一个混在一起的通过率会把这三者的信号全部抹平。

`Kendall's τ` 门禁取 `≥ 0.85` 而非 1.0：Agent 中途多查一次确认信息是合理行为，要求精确复现参考路径会让测试极度脆弱。且**只对声明了 `referenceOrder` 的任务计算**，其余记 `n/a`——报告里单独列出参与用例数，把单步用例的 n/a 当满分混进去会稀释出一个虚高的分数。

### 两处容易实现错的约定（已用单测钉死）

| 约定 | 若实现错会怎样 |
|---|---|
| **重复调用期望工具不算冗余** | `tool_error_recovery` 里模型第一次参数错、第二次改对，两次都调 `task.search`。若算成冗余，指标就在惩罚我们明确想要的自纠行为 |
| **漏调的工具不参与 τ 计算** | 漏调已由召回率惩罚。若 τ 也为此扣分，同一个错误被两个指标各罚一次，让人误判问题的严重程度 |
| **期望集为空时精确率/召回率取 1.0 而非 0/0** | 负例（`chitchat_no_tool` 等）期望集本就是空的，不做这个约定会得到 `NaN` 或被误判为失败 |

`TrajectoryMetricsTest` 15 条覆盖以上全部约定。立场与 `ReliabilityMetricsTest` 一致：**指标算错比没有指标更糟，因为它会让人放心**。

## 4.3 补齐负例（类别平衡）

只测"该调工具时调了"会养出一个**什么都想动手**的 Agent。负例在轨迹层面很难看出问题（工具都在可见列表里、调用也会成功），只有端状态能直接判死。

| 新增用例 | 验证 |
|---|---|
| `chitchat_no_tool` | "你好，你能做什么？" → 不该调任何工具 |
| `ambiguous_asks_clarification` | "帮我安排一下" → 信息不足时应追问而非瞎建任务 |
| `readonly_intent_no_write` | "我这周都有啥安排" → 只读，库不得变化 |
| `complete_existing_task` | **多步写路径**（替换原计划的 `out_of_scope_refusal`） |

**为什么把 `out_of_scope_refusal` 换掉**：现有 `no_tool_hallucination`（"同步到 Google Calendar"）已经在测能力边界拒绝，再加一个只是同类堆量。而 P0 验收时暴露的真问题是**写路径样本太少**（9 个用例里只有 2 个写库），补这个的边际价值高得多。

`complete_existing_task` 一个用例同时补上四件此前完全没有覆盖的事：

1. **修改类写操作**——此前只有新增类（`task.create`），没有任何用例验证"状态从 PENDING 改成 DONE"
2. **真实数据依赖下的顺序**——必须先 `task.search` 拿到 id 才可能 `task.complete` 它，这是全套件唯一一个参考顺序有意义的用例
3. **`requiresConfirm` 工具**——见 §4.4
4. **可预测 id 的 fixture**——`seedTask(90001, ...)` 显式指定 id。H2 的 IDENTITY 在 `delete` 后不回退、跨用例持续递增，靠自增就没法在录制盒里写死那个 id

## 4.4 实施中发现的真问题：确认弹窗堵死了写路径覆盖

这是 P2 最有价值的发现，且**它解释了 P0 遗留的"写路径样本太少"到底为什么发生**。

`goal.create` / `goal.link_task` / `task.complete` / `task.archive` 全都带 `requiresConfirm = true`。评测里没有真人去点"允许"，于是 `ToolConfirmCoordinator.askUser` 会**阻塞整整 60 秒后按拒绝处理**。结果是这些工具在评测中既跑不通、又把套件拖慢一个数量级——**于是没人敢给它们写用例**。

修法是在 `AgentEvalBase` 里给评测用户预置 auto-approve 白名单（`ensureAutoApprove()`）。免确认在这里是正当的：**确认弹窗是 UI 层的人工闸门，不属于 Agent 的决策质量**。要评测确认链路本身，应当单开用例显式构造，而不是让它把所有写路径用例一起拖住。

白名单刻意写成显式清单而非"全部工具"：`checkpoint.run` 这类在用户机器上执行真实命令的工具属于硬例外，`ToolApprovalPolicy.NEVER_AUTO_APPROVE_PREFIXES` 会无视白名单强制弹窗，写进来只会造成"配了却不生效"的误解。

### 实测：注释掉 `ensureAutoApprove()` 会怎样

| 观察项 | 结果 |
|---|---|
| 单个用例耗时 | **76.7 秒**（对比全套 13 个用例约 16 秒）——60 秒阻塞是真的 |
| 任务状态 | 仍是 `PENDING`，`task.complete` 拿到 `USER_REJECTED` |
| **轨迹断言** | **全部通过**：`task.search → task.complete` 都调了，τ=1.0，无禁用命中 |
| 端状态断言 | 失败：`任务状态应变为 DONE` |

**只有端状态抓住了它。** 轨迹看起来完美，世界却没被改变——这是 P0 端状态校验价值的又一个新鲜实例，也说明 P2 的轨迹指标必须和 P0 的端状态断言配合使用，单靠任何一层都会漏。

## 4.5 P2 验收 · 实测结果

```
distinctCases            13   （原 9 + 新增 4）
casesWithGoldenTask      13   （13/13 全部声明了轨迹契约）
precision / recall      1.0 / 1.0
totalRedundantCalls       0
totalForbiddenHits        0   ← 任何非 0 都判红
orderedCases              1   （只有 complete_existing_task 有真实数据依赖）
kendallTau              1.0
```

### 故意破坏验证（确认断言真的会咬人）

绿色测试如果不会红就没有价值。三处刻意破坏，全部如期变红：

| 破坏方式 | 结果 |
|---|---|
| 把 `readonly_intent_no_write` 录制盒改成调 `task.create` | `AssertionError: 调用了明确禁用的工具：[task.create]` |
| 把 `complete_existing_task` 的两步顺序颠倒 | `τ=-1.0 低于阈值 0.85。参考顺序 [task.search, task.complete]，实际 [task.complete, task.search]` |
| 注释掉 `ensureAutoApprove()` | 端状态断言失败（详见 §4.4） |

## 4.6 顺带修掉的一个报告级假绿

破坏验证时发现：逐用例明细行用的是 `converged()` 而不是断言结论，**一个"收敛了但断言没过"的用例会显示成 `PASS`**。「收敛」只说明主循环正常退出，不代表它做对了事。报告自己成了假绿的来源，已改为断言结论优先：

```java
String status = Boolean.FALSE.equals(c.passed()) ? "FAIL"
        : c.exhausted() ? "EXHAUST"
        : c.converged() ? "PASS" : "FAIL";
```

---

# 五、P3 · LLM 裁判（带校准）

> **目标**：替换脆弱的字符串匹配。当前 `finalAnswerContainsAny("未找到","通用知识",...)` 在模型换成"你的笔记里没有相关记录"时会误报。

## 5.1 唯一真正需要裁判的维度

**降级明示诚实度**——这恰好是本项目最重要的那条底线。其余维度（端状态、工具选择、不泄漏内部表示）都能确定性判分，**不要为了用裁判而用裁判**。

```
rubric（写进 prompt，要求返回 JSON）：
  2 = 明确告知"未找到相关笔记"，且没有把通用知识伪装成用户的笔记
  1 = 隐晦提及，普通用户可能看不出来
  0 = 完全没提，或假装命中
  U = 无法判断
```

## 5.2 三条必须遵守的纪律

1. **给逃生舱 `U`**。不给的话裁判会为了填格子而编分数。报告里 `U` 单独计数，占比过高说明 rubric 本身有问题。
2. **必须对人工标注校准，并把一致率与样本量写进报告**。做法：我自己标 30 条，报告写 `裁判-人工一致率 0.87（n=30，单标注者）`。**单标注者这个限制要如实写**——比假装有标注团队诚实得多，也是面试时的加分项。
3. **裁判只跑离线抽样，不进每次 CI**。成本与方差都不可控。工业界的结论很直接：LLM 裁判适合离线抽样，每轮在线判定应该用分类器。

## 5.3 判分器分层的最终形态

| 维度 | 判分器 | 跑在哪 |
|---|---|---|
| 端状态正确 | 代码（`EvalDbProbe`） | 每次 CI |
| 工具选择 / 顺序 / 参数 | 代码（集合与序列运算） | 每次 CI |
| 不泄漏内部表示 | 代码（正则） | 每次 CI |
| **降级明示诚实度** | **LLM 裁判 + rubric** | 夜间 / 发版前 |

---

# 六、P4 · RAG 质量单独度量

> **核心原则（最值得讲的一条）**：检索质量与生成质量**必须分开报告**，一个混合数字是陷阱——分数掉了你不知道该调检索还是调 prompt。

移植 RAGAS 思想但**不引 Python 依赖**，只自实现两个信息量最高的：

| 指标 | 要参考答案吗 | 算法 | 抓什么失败 | 跑在哪 |
|---|---|---|---|---|
| **faithfulness** | 否 | 把答复拆成 claim，逐条问裁判"能否由检索到的 context 推出" | **幻觉**：拿低相关片段编出有据的答案 | 可持续跑，含真实对话 |
| **context recall** | 是 | 参考答案的每个要点是否都能在 context 里找到 | **漏召**：右文档到了但关键那句没到 | 仅对金标集，CI 内 |

这个"无参考 / 有参考"的分工直接决定了运行时机，是 RAGAS 生态里最实用的一条设计。

## 金标集设计

规模 **30~50 条**起步（个人知识库语料远小于企业场景，工业界的 100~200 条建议可下调），但**必须覆盖三类**：

1. 单跳（库里有直接答案）
2. 多跳（要综合多条笔记）
3. **不可回答 / 越界**（库里根本没有）← **最容易被忘、也最重要**

第三类正是 `kb_search_degraded` 守的那条线。金标集**当代码管理**：进版本控制、改动要 review、发现新失败模式就补。

> **重要**：金标集扩容后基线分数必须重建。加了更难的题导致分数下降是预期行为，不该触发回归告警——报告里要记 `datasetVersion`。

---

# 七、P5 · 成本、延迟与门禁

> **前置**：BLOCKER-4 已修（usage 在 transport 侧累计）。

| 指标 | 门禁 |
|---|---|
| 每任务 LLM 调用数 | 回归集不得较基线上升 > 20% |
| 每任务 prompt / completion token | 同上，分列 |
| 每任务预估成本 | 记录，暂不设门禁 |
| 端到端延迟 P50/P95 | 记录，暂不设门禁 |
| **评测自身成本**（裁判调用） | 记录 —— 别让评测比被测系统还贵 |

## 能力集 vs 回归集

| 套件 | 目标通过率 | 作用 | 何时跑 |
|---|---|---|---|
| **回归集** | 接近 100% | 掉了就是 bug | 每次 PR |
| **能力集** | 30~60% | 给自己一座要爬的山 | 夜间 / 发版前 |

**流转规则**：能力集里稳定通过的**毕业**进回归集；回归集里连续多月 100% 且不再变化的**退休**（防评测饱和——全绿套件能追踪回归，但对改进零信号）。

能力集应放这些现在做不到或不稳定的：多步规划的端状态正确性、30+ 轮后早期约束仍被遵守（直接复用上下文工程基准的场景）、子代理结论回灌完整性、中途改变意图。

---

# 八、类清单总览

> ✅ = 已实施。

```
src/test/java/.../agenteval/
  db/EvalDbProbe.java              ✅ [P0] 端状态只读探针
  trial/EvalTrial.java             ✅ [P1] 多试次用例注解（替代 @Test）
  trial/EvalTrialExtension.java    ✅ [P1] 试次展开 + 成败回填（TestWatcher）
  report/ReliabilityMetrics.java   ✅ [P1] pass@k / pass^k 纯函数
  cassette/CassetteTest.java       ✅ [P1] 旧格式兼容验证（6 条）
  report/ReliabilityMetricsTest.java ✅ [P1] 指标算术验证（6 条）

  golden/GoldenTask.java           ✅ [P2] 轨迹契约（期望/禁用/参考顺序/冗余上限）
  report/TrajectoryMetrics.java    ✅ [P2] 精确率/召回率/冗余/禁用/Kendall τ 纯函数
  report/TrajectoryMetricsTest.java ✅ [P2] 指标算术验证（15 条）

  golden/GoldenSet.java               [P4 新增] RAG 金标集加载
  judge/LlmJudge.java                 [P3 新增] rubric 裁判
  judge/JudgeCalibration.java         [P3 新增] 人工标注比对与一致率
  report/RagMetrics.java              [P4 新增] faithfulness / context recall
  transport/UsageAccumulator.java     [P5 新增] token 累计（避免改生产接口）

  AgentEvalBase.java               ✅ [P0] 全局不变量 + probe 注入 + 每用例清库
                                   ✅ [P1] 试次注入、按试次隔离 session、caseId 解析加固
                                   ✅ [P2] ensureAutoApprove() 解开确认堵点 + seedTask()
  trace/TrajectoryAssert.java      ✅ [P0] endState() 断言 + probe 重载入口
                                   ✅ [P2] matchesGolden() 轨迹契约断言
  report/EvalReport.java           ✅ [P0] record() 加 trial、endStateChecked、assertionStrength
                                   ✅ [P1] markOutcome() 回填 + reliability 分区
                                   ✅ [P2] toolSelection 分区 + 修掉明细行的报告级假绿
                                      [P4/P5 改] 新指标分区
  cassette/Cassette.java           ✅ [P1] trials[][]，兼容旧 interactions[]
  transport/ReplayLlmTransport.java   ✅ [P1] beginCase(caseId, trial)，缺试次即报错
  transport/RecordingLlmTransport.java ✅ [P1] 跨试次累积同一个盒子
  build.gradle                     ✅ [P1] 透传 -Dagent.eval.trials
```

**没有任何生产代码改动**（BLOCKER-4 刻意绕开了 `AgentTraceListener` 接口）。这是有意的：评测体系的改造不应该有能力去弄坏被测系统。

---

# 九、排期与优先级

| 期 | 内容 | 依赖 | 工作量 | 收益 |
|---|---|---|---|---|
| **P0 ✅** | 端状态验证 + 全局不变量 + BLOCKER-1 | 无 | 已完成 | **最高**：消灭假绿，零成本 |
| **P1 ◐** | 真实录制 + pass@k/pass^k + BLOCKER-2 | API Key | 基建已完成，待录制 | **高**：指标恢复含义，首获可靠性数 |
| **P2 ✅** | 轨迹指标分解 + 负例补齐 | 无（不必等 P1） | 已完成 | 中高：能定位"为什么不对"，并解开了写路径覆盖的堵点 |
| **P3** | LLM 裁判 + 人工校准 | P1 | 两天 | 中：替换脆弱字符串匹配 |
| **P4** | RAG faithfulness / context recall + 金标集 | P3 | 三天 | 中：检索与生成分开归因 |
| **P5** | 成本延迟门禁 + 能力/回归分离 + BLOCKER-4 | P2 | 半天 | 中：防"对但太贵" |

> **P2 的依赖被证明是错的**：原设计写「P2 依赖 P1」，实则不然。轨迹指标是纯函数，负例是新用例，两者都不需要真实录制就能落地并验证。真正需要 P1 的只有「指标数值的可信度」——现在算出的 precision=1.0 反映的是手写录制盒，不是模型真实表现。

**P0 是最该先做的**：不花钱、不依赖外部，做完后现有用例信息量立刻翻倍，且参数准确率被顺带覆盖（deadline 解析错了，`deadlineDate()` 比对就不过）。

---

# 十、总验收：改造完成后的目标报告

```
══════════════════════════════════════════════════════════════
  Agent 评测报告   mode=replay(k=3)   回归集=18  能力集=12
══════════════════════════════════════════════════════════════
  端状态正确      回归 18/18 (100%)   能力 5/12 (41.7%)
  pass@3          回归 100%           能力 58.3%
  pass^3          回归 94.4%          能力 33.3%   ← 与 pass@3 之差 = 不稳定度
──────────────────────────────────────────────────────────────
  工具选择精确率  0.94    （多余调用 4 次）
  工具选择召回率  0.98    （漏调 1 次）
  顺序一致性 τ    0.91    （仅 7 个多步任务参与）
  禁用工具触发    0       ← 非 0 即判红
──────────────────────────────────────────────────────────────
  RAG · faithfulness      0.91   （无需参考答案，全量）
  RAG · context recall    0.78   （金标集 42 条，v3）
  降级明示诚实度          1.87/2 （LLM 裁判，人工一致率 0.87 n=30 单标注者）
──────────────────────────────────────────────────────────────
  效率  LLM 2.4 次/任务   prompt 3.1k tok   延迟 P95 4.2s
  成本  被测 $0.021   评测裁判 $0.008
──────────────────────────────────────────────────────────────
  录制新鲜度  2 个用例指纹漂移，建议重录
══════════════════════════════════════════════════════════════
```

**和现在最大的差别**：每个数字都能回答"它凭什么是这个值"，且**能力集的低分是特性不是缺陷**——它标出了下一步该往哪走。

---

# 附：本设计刻意不做的

| 不做 | 原因 |
|---|---|
| 线上 A/B 分流 | 单用户，达不到统计显著 |
| 标注团队 / 标注员间一致性 | 只有我一个人，改为单标注者并如实标注限制 |
| 接入 LangSmith / Braintrust / Phoenix | 引入 SaaS 依赖与数据外传；本项目缺判分不缺可观测 |
| RAGAS 全套 8 指标 | Java 仓库，移植思想不移植依赖，只做最有信息量的两个 |
| 改 `AgentTraceListener` 生产接口 | 纯测试侧需求，放传输层 blast radius 最小 |
| MCP loopback / 语义缓存 / 子代理的覆盖 | **本设计不涵盖**，属独立缺口，见覆盖度核查 |
