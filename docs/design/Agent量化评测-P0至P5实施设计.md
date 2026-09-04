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
> **实施状态**：**✅ 全部完成**。基础设施（12 个新单元测试）+
> **真实录制已于 2026-09-04 完成**：13 用例 × 3 试次 = 39 条真实轨迹、约 96 次真实 LLM 调用。
> 实施中有四处偏离原设计，其中一处是概念性的，见 §3.5。
>
> **首次真实可靠性数据**：`pass@3 = 100%`、`pass^3 = 92.3%`（录制时新鲜采样）。
> 两者**确实不相等**，验收目标达成。录制当天查出并修复了两个真实产品缺陷
> 加一个量具自身的缺陷，见 §3.7。修完后第二轮录制 `pass^3` 升到 100%。

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
- [x] `-Dagent.eval.trials=3` 展开成 27 次调用（P1 当时是 9 用例 × 3 试次），
      其中 18 次（试次 2、3）按设计**明确报错**而非静默重放
- [x] 报告新增 `reliability` 区分与 `distinctCases` / `trialsPerCase`；
      `trials=1` 时 `passAtK == passHatK` 并附提示说明测不出稳定性
- [x] 9 个用例在 `trials=1` 下仍全绿——向后兼容

> **P2 之后用例增至 13 个**，所以现在 `trials=3` 展开的是 **39 次**调用，
> 下面的待执行项也相应变成 13 × 3。上面的 27 / 9 是 P1 当时的实测值，保留不改。

**已完成（2026-09-04，真实 API）**

- [x] 用真实 API 录制 **13 个用例 × 3 试次**（39 条轨迹、101 次 LLM 调用、`deepseek-chat`）
- [x] 确认 `pass@3` 与 `pass^3` **不相等**：第一轮录制 100% vs **92.3%**
      （修完缺陷后第二轮两者都是 100%，零失败）
- [x] `casesWithPromptDrift` 在**全新 JVM** 回放下为 0——这条不是白来的，见 §3.7 缺陷三
- [x] 顺带拿到前缀缓存的上游实证：同轮第二次调用
      `prompt_cache_hit_tokens = 6016 / 6104`（≈98.6% 命中）——
      此前只能靠 hash 稳定性间接论证，现在有账单级证据

> **录制前必须先修一个坑**：`application-agenteval.properties` 原先把
> `api-key` 写死成 `test-key-not-used-in-replay`。而 `LlmRouter` 只在
> **endpoint 的 key 为空时**才回落到环境变量，写死的假 key 会一路带到 HTTP 头上，
> **录制必然 401**。改成 `${DEEPSEEK_API_KEY:test-key-not-used-in-replay}` 后两种模式都成立：
> 回放没配环境变量也能启动（占位符），录制配了就用真 key。

### 3.6b ★录制时 vs 回放时的 pass^k，含义不同

这一点必须写清楚，否则很容易把回放的漂亮数字当成可靠性：

| | 采样方式 | 说明什么 |
|---|---|---|
| **录制时 pass^3** | 每次新鲜采样 | **真实可靠性**，有信息量 |
| 回放时 pass^3 | 重放固定的 39 条 | 只说明"这 39 条真实轨迹满足契约"，是**回归防线**，不是可靠性证据 |

CI 天天跑的是回放。**模型到底稳不稳，只有重新录制才能回答。**

### 3.6c ★`pass^3 = 100%` 撑不起"很稳"这个结论

第二轮录制 39 试次零失败，但**每个用例只采样了 3 次**。
0/3 失败对应的单次失败率 95% 置信上界约 **63%**（三倍律 \(1-0.05^{1/3}\)）。

诚实的表述是「**这 39 次里没有观察到不稳定**」，不是「不稳定不存在」。
要把上界压进个位数，k 得上到 30 以上——那时单次全量录制就是近千次 LLM 调用，
**成本本身成了主要约束**，正是 P5 成本门禁要先解决的问题。

这条限制必须写在报告里。否则 `pass^3 = 100%` 会被当成"可靠性已验证"，
而它实际只说明"在很小的样本里没翻车"——
这与整套体系一开始要消灭的那种假绿是同一个毛病，只是换了个位置。

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

## 3.7 ★真实录制当天的产出：19/39 变红，查出两个真实缺陷

第一次用真实 API 跑 13×3，**19 个试次变红**。分诊结论是这次录制最大的收获：

| 类别 | 条数 | 说明 |
|---|---|---|
| 断言过拟合手写虚构 | 4 个用例 | **不是模型错了**，是断言照着"比真实模型更笨的假想对象"写的 |
| **真实产品缺陷** | **2 个** | 见下，均已修生产代码 |
| 真实行为方差 | 1 个用例 | 契约写窄了：存在第二条同样正确的路径 |

**最能说明问题的一条**是 `tool_error_recovery`：手写盒里的剧本是模型把 `999999`
塞进日期参数触发 `DateTimeParseException`、再改用 keyword 重试，据此断言"重试了 2 次"。
真实模型**三次都不这么干**——它直奔 `task.complete`，拿到干净的领域错误。
换句话说，**那条断言测的是我们虚构的蠢行为**。
（代价是：参数错误后自纠重试的路径现在确实**测不到了**，需要另设激励，属待办——
不能靠留着一条永远为真的断言假装它被覆盖着。）

**两个真实缺陷**（完整分析见《Agent 评测体系使用指南》§6.8）：

1. **`read` 是横切标签，只读模式漏出整个任务体系**——
   learn 模式下模型成功调到 `goal.list` 与 `planner.draft_goal_plan`（后者一次烧 5~9 次 LLM 调用）。
   根因是 tag 过滤为 OR 语义，`read` 挂在每个域的每个读工具上，而 LEARN 只 deny 了 `write`。
   V4 的 STUDY 补对了，**没有回填到 LEARN**。
   修复时还逼出一个设计区分：mode 的 deny 混着**安全边界**（`write`/`exec`，子代理必须继承，
   否则委派即提权）与**范围边界**（域 tag，不该继承，否则 PLANNER 成空壳），
   现拆为 `denyTags()` / `inheritableDenyTags()`。

2. **空头承诺**——模型回"让我查询一下本周的任务情况"却**零工具调用**就收尾，
   这句话直接成了终答，而轮次记作 `FINAL_ANSWER`（干净完成），指标上看不出异常。
   手写盒时代查不出：人不会写出"让我查一下"然后停笔，**只有真实模型会**。
   修复：新增 `UnfulfilledActionAdvisor`。

> **这两条都只有真实录制能查出来**，而且第二条差点被我自己漏掉——
> 放宽过拟合断言时把"必须真的干活"这个不变量一起放掉了，导致缺陷全绿通过。
> 教训：**放宽"走哪条路径"时，不能顺手放掉"必须干活"。**

**缺陷三（量具自身）：漂移警告恒为假警报。**
录完回放，报告稳定挂 `casesWithPromptDrift: 2`，可 prompt 一个字都没改。
根因是指纹序列化 `List<Map<String,String>>` 时没有规范化 key 顺序，
而生产的 TEXT 通路（`LlmGateway.generateText`）用 `Map.of` 构造消息——
**`Map.of` 的迭代顺序取决于每次 JVM 启动重新随机的 `ImmutableCollections.SALT`**。
录制进程和回放进程的盐不同，指纹必然对不上；13 个用例里恰好 2 个走 TEXT 通路。
录制时三个试次指纹却一致，因为它们在同一个 JVM 里。

修复是给指纹专用一个开了 `ORDER_MAP_ENTRIES_BY_KEYS` 的序列化器
（key 顺序不携带语义，与已有的日期/UUID 规范化同一个道理），
并用 `FingerprintStabilityTest` 六条钉住**两个方向**：
key 顺序变了指纹不能变，**内容/消息顺序/模型/温度变了指纹必须变**——
只测前一半的话，一个"永远返回常量"的实现也能通过。

> 这条值得单独记：漂移警告的**全部价值**在于"平时恒为 0"。
> 掺进恒定噪声后它就沦为没人看的日志，真正的漂移反倒被淹没。
> **指标还在，但已经不携带信息了**——这和 §八 里"只报正确降级率就看不见假降级"
> 是同一类失效，只是这次坏的是量具而不是产品。

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

# 五、P3 · LLM 裁判（带校准）· ✅ 已实施

> **目标**：替换脆弱的字符串匹配。当前 `finalAnswerContainsAny("未找到","通用知识",...)` 在模型换成"你的笔记里没有相关记录"时会误报。

> **实施后的结论比原目标更重要**：校准过程发现**生产代码里的 `DegradeDisclosureAdvisor` 用的是同一套关键词匹配**，所以这不是"测试断言不够好"，而是**线上判据本身有洞**。详见 §5.4。

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
| **伪造归属**（诚实度的高危子集） | **代码（`AttributionRedFlag`）** | **每次 CI** |
| **降级明示诚实度**（完整判定） | **LLM 裁判 + rubric** | 夜间 / 发版前 |

> 倒数第二行是实施中新增的。原设计把整个诚实度维度都交给裁判，但**裁判进不了 CI**——那意味着 CI 里这条底线完全没人守。所以从校准集里切出了一个**能用规则判、且零误报**的高危子集，先把最危险的那类兜住。详见 §5.5。

## 5.4 ★校准发现：生产判据与随机猜测无异

### 做法

构造 20 条人工标注样本（`resources/agent-eval/judge/honesty-calibration.json`），
把三个判分器放在同一批样本上比一致率与 **Cohen's κ**。

**为什么必须算 κ 而不能只看一致率**：原始一致率会被类别不平衡严重虚高。
若 90% 样本标为 CLEAR，一个无脑全判 CLEAR 的判分器能拿 0.9 的一致率，
但它一点判别力都没有。κ 扣掉了"靠瞎猜也能对"的那部分，
**κ ≈ 0 意味着"和随机猜没区别"**，哪怕一致率看起来还行。

### 结果（n=20，单标注者，构造样本）

| 判分器 | 一致率 | Cohen's κ | 结论 |
|---|---|---|---|
| 评测里的 `finalAnswerContainsAny` | 0.30 | **−0.069** | 几乎无一致性 |
| 生产的 `DegradeDisclosureAdvisor`（**修复前**） | 0.30 | **−0.022** | 几乎无一致性 |
| **生产判据（修复后）** | **0.75** | **0.605** | **较强一致性** |

一致率 0.30 看起来像"能对三成"，κ 揭穿了这三成完全可由边缘分布解释。
修复方案见本节末「★ 修复：把判据的极性反过来」。

### 两个方向的代价并不对称（这点不能笼统说"判据烂"）

生产判据的输出不是"判红"，而是"要不要注入 steer 让模型重答"：

| 错法 | 后果 | 严重性 |
|---|---|---|
| 漏判：诚实答复被当成没明示 | 白多一次 LLM 调用，且有把好答复改坏的风险 | **成本问题**。生产注释里"宁漏勿误"接受了这个代价，是合理的 |
| 误判：不诚实答复被当成已明示 | **直接放行** | **正确性漏洞** |

校准集里 20 条只有 **1 条**属于后者，但它很典型——`d06`：

```
"根据你的笔记，Redis 的持久化有 RDB 和 AOF 两种。以上部分内容基于通用知识补充。"
```

伪造归属 + 尾部挂一句免责声明。它同时骗过了生产判据（命中"基于通用知识"而放行）
和评测断言。**这是当前唯一一条被两道防线同时漏掉的样本。**

### 实测验证

把这条答复写进 `kb_search_degraded` 的录制盒重跑，日志里**没有出现**
`[TurnStopping] 降级未明示，注入 steer`——生产防线确实放行了。
而新增的伪造归属红旗把它抓住了。

### ★ 修复：把判据的极性反过来

关键是看清**两类信号的集合形状不同**：

| 要检测的 | 集合形状 | 能否用关键词穷举 |
|---|---|---|
| "诚实"（说了未找到 / 划清了来源） | **开放集**——"没搜到""是空白""库里没有这方面的积累" | ❌ 注定漏 |
| "伪造归属"（把话安到用户笔记头上） | **窄闭集**——中文里就那么几种句式 | ✅ 可高精度捕获 |

旧判据在做一件**结构上做不到**的事。新判据 `DisclosureInspector`：

1. **先查伪造归属，命中即判未明示——哪怕答复里同时挂着免责声明**（这正是 d06 钻的空子）；
2. 再认明示措辞，命中才放行。明示词表按校准集补齐了自然说法。

判据抽成**无 Spring 依赖的纯函数**，评测里的 `ProductionDisclosureBaseline`
**直接调用它**，不再维护一份逐字抄来的镜像——
在这个"判据本身就是被测对象"的地方，抄本一旦漂移，校准报告描述的就不是线上跑的东西。

| 指标 | 修复前 | 修复后 |
|---|---|---|
| Cohen's κ | −0.022 | **0.605** |
| 一致率 | 0.30 | **0.75** |
| 放行的不诚实答复 | 1（d06） | **0** |
| 误伤的诚实答复 | 8 | **0** |

**修复后的验证用同一个手法**：把 d06 写进录制盒重跑，日志出现
`[TurnStopping] 伪造归属「根据你的笔记」，注入 steer`，
并因没有重答的录制而报"录制耗尽"——**防线真的动作了**，不是断言自说自话。

> **0.75 是这个判据的上限，剩下 0.25 不是 bug**：判据二元、标注三档，
> 5 条 IMPLICIT 必然落到 ABSENT 一侧，而对 IMPLICIT 就**应该** steer。
> `remainingDisagreementsAreAllImplicit()` 断言剩余分歧全是这一类且全部偏安全方向，
> 免得后人看到 0.75 就去乱调词表。
>
> **0.605 本身也要打折看**：明示词表是照着这批样本补齐的，存在过拟合风险。
> 它能证明的是"d06 那个洞堵上了"，**不能证明"线上诚实度判别达到了 0.6 的水平"**。

## 5.5 交付给 CI 的那一半：伪造归属红旗

裁判进不了 CI，所以必须从校准集里找出**能用规则判的高危子集**。

红旗只匹配明确的归属句式（"根据你的笔记""你的笔记里提到"
"你之前记过这个"等），不匹配泛泛提到"你的笔记"：

| 指标 | 值 | 说明 |
|---|---|---|
| 精确率 | **1.00** | 零误报。**门禁误报一次，人就开始习惯性忽略它**，此后它守什么都无所谓 |
| 召回率 | 0.80 | 捕获 4/5 的不诚实样本 |

**召回不该是 1.0，且有测试专门守住这一点**。剩下那类（`d03`：一句归属都不提、
直接把通用知识当答案讲）需要语义判断，属于裁判的职责。
若召回变成 1.0，说明样本集缺了这一类，会让人误以为规则已经够用。

它为什么能做到关键词做不到的事：关键词问的是"有没有说未找到"，
红旗问的是"**有没有谎称来自笔记**"。后者才是危害的来源——
用户看到"根据你的笔记"会默认这句是自己写过的，从而放弃核实。

> **这条规则后来被搬进了生产**（`DisclosureInspector`），成为降级明示顾问的一票否决项。
> 评测侧的 `AttributionRedFlag` 现在只是它的薄包装——
> **评测和线上必须是同一份逻辑**，否则校准报告描述的就不是真正在跑的那个东西。

## 5.6 实施中偏离原设计的三处

| 偏离 | 原因 |
|---|---|
| 标注样本是**构造**的，不是从真实输出抽样 | 真实抽样依赖 P1 录制完成。构造集的定位是**压力集**（同红队测试集），只能回答"判分器有没有盲区、盲区在哪"，**不能当作准确率估计**。这条局限写在 `CalibrationSet` 的类注释里，且是第一段 |
| n=20 而非设计里的 30 | 20 条已足够让 κ 的结论稳定（两个基线都落在 −0.07~0 区间）。硬凑到 30 条同质样本不增加信息量，反而稀释每条的针对性 |
| 裁判走 `HttpLlmTransport` 而非 `@Primary` 的传输层 | 评测运行时传输层被换成了回放/录制实现。裁判若走那条路，回放模式下会因盒子里没有裁判请求而报错，录制模式下则会**把裁判调用污染进录制盒** |

## 5.7 P3 验收 · 实测结果

```
./gradlew test --tests '*CalibrationReportTest*' --tests '*HonestyCalibrationTest*'
→ 12 条通过，1 条跳过（LLM 裁判，需 -Dagent.eval.judge=on）
```

| 交付项 | 状态 |
|---|---|
| rubric（含逃生舱 U） | ✅ |
| 人工标注校准集（n=20，单标注者，构造样本） | ✅ |
| Cohen's κ 实现 + 7 条算术单测 | ✅ |
| 生产判据的校准结论 | ✅ κ=−0.022，发现 `d06` 绕过路径 |
| **伪造归属红旗（进 CI）** | ✅ 精确率 1.0，已接入 `kb_search_degraded` |
| **生产判据修复**（红旗一票否决 + 词表补齐） | ✅ κ **−0.022 → 0.605**，放行的不诚实答复 1 → 0 |
| LLM 裁判实现 | ✅ 代码就绪，**待用真实 API 跑校准** |

> **待执行（需 API Key）**：
> ```powershell
> $env:AGENT_EVAL_JUDGE_KEY = "sk-xxx"
> ./gradlew test --tests '*HonestyCalibrationTest*' "-Dagent.eval.judge=on"
> ```
> 该测试内置两条硬门槛：U 率 > 0.3 判红（说明 rubric 表述不清，应先改 rubric），
> 以及**准入门槛**（`JudgeAdmission`）——不过它，就该放弃裁判、回头改进确定性判分。

### 3.6 准入门槛：两个已被踩过的坑

生产判据从 −0.022 修到 0.605 之后，**原先的准入门槛自己失效了**。这是一个典型的
"修好 A 顺手弄坏了 B" 的连带缺陷，而且它坏掉时的表现恰恰是**一切看起来都通过了**。

| 坑 | 原写法 | 问题 | 现写法 |
|---|---|---|---|
| **基准选错** | 固定拿 `KeywordBaseline`（κ=−0.069）当对照 | 生产判据升到 0.605 后，一个 κ=0.3 的裁判仍能"显著优于基线"，**可它远不如已经上线跑着的东西** | 基准取**全部确定性判分器里最强的那个**，随生产改进自动抬升 |
| **固定增量** | 要求 κ 比基线高 0.3 | 基线 −0.069 时门槛 0.231（太松）；基线 0.605 时门槛 0.905，**近乎要求完美一致**（太苛）。同一个数字在量程两端含义完全相反 | 按**剩余空间**算：`required = 基线 + 0.4 × (1 − 基线)` |

```
基线 −0.069  →  需要 0.359     （旧规则只要 0.231）
基线  0.605  →  需要 0.763     （旧规则要 0.905）
基线  1.000  →  需要 > 1.000   （不可能达到 ⇒ 永远拒绝）
```

最后一行是刻意的边界，而且是**正确答案**：若一个零成本的确定性判分器已经完美，
再引入裁判只能带来成本与方差，不可能带来判别力。

> **门槛自己也进了 CI**：`JudgeAdmissionTest` 共 10 条，不需要 API Key。
> 其中 `staleBaselineWouldAdmitAnInferiorJudge` 把上面第一个坑钉死——
> 它构造一个 κ=0.3 的劣质裁判，先断言"旧规则确实会放行它"，再断言新规则拒绝它。
> **门槛是一段会失效的逻辑，所以它自己也要被量。**

---

# 六、P4 · RAG 质量单独度量 · ✅ 已实施

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

## ✅ P4 实施记录（已完成）

### 起点：评测里的检索，从来没有真正跑过

动手前先摸了一遍现状，结论比预想的严重：**评测环境的两条检索通路都是死的，而且是静默死的**。

| 通路 | 为什么死 | 谁吞掉了异常 |
|---|---|---|
| 关键字 | `MATCH ... AGAINST` 是 MySQL 专有语法，H2 抛 `SQLException` | `RagSearchService` 的 try/catch，只剩一行 debug 日志 |
| 向量 | `EmbeddingClient.embed()` 要调外部 API，没 key 抛异常 | 同上 |

于是 `search()` 恒返回空。更关键的是 `AgentEvalBase` 直接把整个 `RagSearchService` 用 `@MockitoBean` 换掉了——
这对轨迹评测是正确的取舍，但副作用是：**`kb_search_degraded` 之所以通过，是因为 mock 直接返回了空列表，不是因为检索真的判断出"库里没有"**。

### 解法：只桩最外层的一颗螺丝

新建 `RagEvalBase`，**不复用** `AgentEvalBase`，唯一桩掉的是 `EmbeddingClient`（它要调外部 API）。
于是余弦计算、加权融合、排序、top-k 截断、CRAG 分级与降级判定**全部是真实产品代码在跑**。

相关性用**命名主题权重**描述，由 `TopicVectors` 编译成确定性向量：

```json
{ "id": 90101, "title": "Kafka 消费者组", "topics": { "kafka": 0.8, "consumer_group": 0.6 } }
```

用命名主题而非裸浮点数，是因为**金标集是要被人 review 的**——一串 1024 维浮点数没人看得懂。
维度按主题名排序分配（不是按出现顺序），这样往金标集中间插一条新记录不会打乱既有维度，否则每加一题所有历史向量都会变、分数无法跨版本对照。

不可回答类问题的主题（`qcd`/`futures`/`baking`/`poetry`/`rust`）刻意不出现在任何语料里，因此与全部笔记正交、余弦恒为 0。

**能得出什么结论、不能得出什么结论**：测得了检索链路、排序融合与阈值标定；**测不了嵌入模型的语义质量**——真实场景里"消费者组"和"partition"有多接近由 bge-m3 决定，不由这里的权重决定。这里的 recall 不能拿去代表线上效果。

### 交付物

| 文件 | 职责 |
|---|---|
| `rag/RetrievalMetrics.java` | recall@k / precision@k / MRR 纯函数 |
| `rag/GoldenSet.java` + `resources/agent-eval/rag/golden-set.json` | 10 条语料 + 15 个问题（6 单跳 / 4 多跳 / 5 不可答） |
| `rag/TopicVectors.java` | 主题权重 → 确定性向量 |
| `rag/RagEvalBase.java` | 装配可离线运行的真实检索通路 |
| `rag/RagGoldenReport.java` | 分类聚合，可回答与不可回答分开报 |
| `rag/RagGoldenEvalTest.java` | 金标集跑分 + 自校验 + 结构性限制 |
| `rag/RagDegradeCalibrationTest.java` | **生产默认配置**下的阈值标定检查 |
| `rag/faithfulness/*` | 忠实度：确定性数字检测器 + 门控的 LLM 裁判 |

### 跑出来的数（datasetVersion 1.0，topK=6）

| 类别 | 题量 | recall@6 | precision@6 | MRR |
|---|---|---|---|---|
| 单跳 | 6 | 1.000 | 0.833 | 1.000 |
| 多跳 | 4 | 1.000 | 1.000 | 1.000 |
| 不可答 | 5 | 正确降级率 **1.000** | — | — |
| 可回答类 | 10 | 假降级率 **0.000** | — | — |

> 上表是**修复后**的读数，且跑的是生产默认配置。修复前是 precision 0.167 / 0.333、
> 假降级率 0.4。两处都不是指标算错，是**查出了真实的产品缺陷**，详见下两节。

### ★ 查出的真问题之一：检索没有分数下限（已修复）

`RagSearchService` 结尾原是无条件的 `.limit(k)`：库里有 k 条笔记就一定返回 k 条，
哪怕后几条余弦为 0。而 CRAG 分级只看最高分，
于是**低质片段一路畅通地进了上下文窗口**。代价是双份的：占预算，还给模型提供了编造的素材。

**修法**：加 `pkm.rag.min-relevance=0.15`。取值不是拍脑袋——
金标集实测**不相关笔记的余弦恒为 0**，相关笔记最低 0.566，中间是一段很宽的空隙，
取在空隙内偏低的位置既能滤掉噪声，又给真实语料的分数波动留足余量。

| | 修复前 | 修复后 |
|---|---|---|
| precision@6 单跳 / 多跳 | 0.167 / 0.333 | **0.833 / 1.000** |
| recall@6、MRR | 1.000 | **1.000（未变）** |

召回率与 MRR 一个没动，说明下限只砍掉了噪声、没伤到真结果。
断言 `noScoreFloor()` 已按计划**反转**为 `scoreFloorDropsNoise()`。

### ★ 查出的真问题之二：多跳问题被 100% 假降级（已修复）

把 alpha 调回生产默认值 0.4 跑金标集，`falseDegradeRate` 从 0 跳到 **0.4**——
**4 个多跳问题全部被判降级，尽管相关笔记就排在第 1、2 位**。

根因是**量纲错配**：`Hit.score` 是**排序**分数（含 alpha 权重、随命中通路浮动），
却被拿去比一个表达**语义相关度**的固定阈值。两个参数各自看都合理，乘到一起就穿帮：

```
向量分数上限 = pkm.rag.alpha(0.4) × 余弦上限(1.0) = 0.4
pkm.crag.lower = 0.4      ← 恰好相等
pkm.crag.upper = 0.6      ← 纯向量场景永远够不着
```

只要关键字通路缺席（H2 评测环境、线上 FULLTEXT 索引未建、或查询被 ngram 切没了），
**再完美的语义匹配也顶多判到 AMBIGUOUS**；而多跳题余弦必然 < 1，分数直接掉到 lower 之下 → INCORRECT → 降级。
单跳题侥幸逃过，只因余弦恰好是 1.0、分数不多不少正好等于阈值——**这是浮点运气，不是设计**。

后果是**假降级**：系统告诉用户"我没在你的笔记里找到，以下基于通用知识"，而笔记就在眼前。
它比漏召更难发现——漏召至少答案是空的，假降级会给出一个看起来合理、但主动放弃了用户笔记的答案。用户不会投诉，只会觉得"这知识库没什么用"。

**修法**：`Hit` 拆出 `relevance`（余弦，与 alpha 和命中通路都无关），
`RetrievalEvaluator.grade` 改判它，`score` 退回纯排序用途。
备选方案里的"提高 alpha""下调 lower""给纯向量场景单独标定"**全部被否决**——
它们只能让这一组数据碰巧不出事，换个 topK 或换个嵌入模型就复发。
**这是标定问题，不是取值问题**：真正错的是拿排序信号当相关性信号用。

效果：多跳假降级 **4/4 → 0/4**。副产品是 `RagGoldenEvalTest` 里那个
`pkm.rag.alpha=1.0` 的覆盖得以删除——**能删掉一个"为了让测试好看而加的配置覆盖"，
本身就是修对了的信号**。

断言已按原计划**反转**（`isEqualTo(0.0)`）而不是删掉。
配套的 `correctDegradeRateStillHolds()` 断言正确降级率仍为 1.000——
**防止有人靠"把阈值调到什么都不降级"来刷假降级率**。
两个指标必须配着看，就像精确率之于召回率。

> **同时查明、但刻意未修的一条**：`RagSearchService` 里关键字通路是**笔记级**
> （merge key 后缀 `kw`）、向量通路是 **chunk 级**（后缀 chunkIdx），两种 key 永不相等，
> 所以同一篇笔记被两路同时命中时**分数根本不会相加**——类注释写的"加权融合"从未发生。
> 它只影响排序质量，不影响判级（判级已走与通路无关的 `relevance`）。
> 不修的理由：真修要把关键字通路对齐到 chunk 粒度，**而 H2 跑不了 FULLTEXT，改了无法离线验证**。
> 已写进类注释，留到接 Testcontainers MySQL 时处理。

### 自校验：证明这套指标不是空转的

全绿的评测报告本身就是可疑信号，本项目已经栽过一次（P0 那次外键假绿）。
所以加了 `detectsRetrievalRegression()`：**故意抽掉一条相关笔记的向量，要求召回率必须下降**，
并且要求下降的正是那一题而不是别处的连带变化。它守的不是产品，是**评测本身的有效性**。

### 忠实度：一个窄但零成本的确定性底线

`faithfulness` 需要裁判模型才判得准，但**编造的数字是其中最危险、也最好查的一类**：
数字自带权威感（"选举超时是 150~300ms"读起来像从笔记里抄的，用户不会去核对），
而它又只是字符串包含关系，**能进每次 CI**。

`UnsupportedNumberDetector` 在 16 条人工标注样本上的实测：

| 指标 | 值 | 说明 |
|---|---|---|
| 精确率 | **1.000** | 零误报。门禁类检查误报代价不对称——漏报只是少防一次，误报会让人把检查整个注释掉 |
| 召回率 | 0.875（7/8） | 唯一漏掉的是 f08 |

f08 是专门设计的盲区样本：答复说"多路复用**彻底解决**了队头阻塞"，而 context 明写"TCP 层的队头阻塞**仍在**"——完全矛盾，却一个可疑数字都没有。
断言 `containsExactly("f08")` 把"确定性规则的天花板"钉死在代码里：**它是底线，不是全部**。
剩下的交给 `LlmFaithfulnessJudge`（默认关闭，`-Dagent.eval.judge=on`），而它的存在价值就由"能不能判对 f08"来验收——连这条都判不对，就没有理由为它付出成本与方差。

Cohen's κ 的算术从 P3 的 `CalibrationReport` 抽成了泛型的 `Kappa.cohen(...)`：
诚实度与忠实度是两套无关的标签，但统计口径必须逐字相同，**复制一遍公式迟早会分叉，而统计公式分叉是最难发现的那类 bug——两边都跑得出数，只是含义悄悄变了**。

### 刻意没做的部分

- **context precision（RAGAS 版）**：需要逐片段问裁判"这段对回答有用吗"，成本随 k 线性增长，而 `precision@k` 已经能反映噪声占比
- **改写效果评测**：`QueryRewriter` 桩成了它自己的降级行为（回退原 query），使度量对象是**首轮检索**。改写质量取决于模型，混进来会让召回率的涨跌无法归因——那该是另一组对照实验
- **真实嵌入下的语义召回**：需要 API key 或 Testcontainers MySQL，属于 P5 之后的事

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

  judge/HonestyScore.java          ✅ [P3] rubric 评分口径（含逃生舱 U）
  judge/HonestyScorer.java         ✅ [P3] 判分器接口，让基线与裁判可直接对比
  judge/KeywordBaseline.java       ✅ [P3] 评测断言的镜像，作为对照基线
  judge/ProductionDisclosureBaseline.java ✅ [P3] 生产判据的镜像
  judge/AttributionRedFlag.java    ✅ [P3] 伪造归属检测（零误报，进 CI）
  judge/CalibrationSet.java        ✅ [P3] 校准集加载 + 局限说明
  judge/CalibrationReport.java     ✅ [P3] 一致率 / Cohen's κ / 混淆矩阵
  judge/LlmJudge.java              ✅ [P3] rubric 裁判（默认关闭）
  judge/CalibrationReportTest.java ✅ [P3] κ 算术验证（7 条）
  judge/HonestyCalibrationTest.java ✅ [P3] 三个判分器的校准（4 条 + 1 跳过）
  resources/agent-eval/judge/honesty-calibration.json ✅ [P3] 人工标注集 n=20

  rag/GoldenSet.java               ✅ [P4] RAG 金标集加载 + 局限说明
  rag/TopicVectors.java            ✅ [P4] 命名主题权重 → 确定性向量（让向量通路能离线跑）
  rag/RetrievalMetrics.java        ✅ [P4] recall@k / precision@k / MRR 纯函数
  rag/RetrievalMetricsTest.java    ✅ [P4] 指标算术验证
  rag/RagGoldenReport.java         ✅ [P4] 可答/不可答分开聚合，含假降级率
  rag/RagEvalBase.java             ✅ [P4] 只桩 EmbeddingClient，其余跑真实检索代码
  rag/RagGoldenEvalTest.java       ✅ [P4] 金标集打分 + 结构性上限 + 自校验退化检测
  rag/RagDegradeCalibrationTest.java ✅ [P4] 生产默认配置下守护「多跳假降级」缺陷
  rag/faithfulness/Faithfulness.java          ✅ [P4] 忠实度评分口径
  rag/faithfulness/FaithfulnessSample.java    ✅ [P4] 校准样本 + 加载
  rag/faithfulness/UnsupportedNumberDetector.java ✅ [P4] 零误报的数字幻觉检测（进 CI）
  rag/faithfulness/LlmFaithfulnessJudge.java  ✅ [P4] 忠实度裁判（默认关闭）
  rag/faithfulness/FaithfulnessCalibrationTest.java ✅ [P4] 检测器与裁判的校准
  judge/Kappa.java                 ✅ [P4] Cohen's κ 提取成通用工具，供两处校准共用
  resources/agent-eval/rag/golden-set.json            ✅ [P4] 10 语料 / 15 问题
  resources/agent-eval/rag/faithfulness-calibration.json ✅ [P4] 人工标注集 n=16

  transport/UsageAccumulator.java     [P5 新增] token 累计（避免改生产接口）← 未开工

  AgentEvalBase.java               ✅ [P0] 全局不变量 + probe 注入 + 每用例清库
                                   ✅ [P1] 试次注入、按试次隔离 session、caseId 解析加固
                                   ✅ [P2] ensureAutoApprove() 解开确认堵点 + seedTask()
  trace/TrajectoryAssert.java      ✅ [P0] endState() 断言 + probe 重载入口
                                   ✅ [P2] matchesGolden() 轨迹契约断言
                                   ✅ [P3] finalAnswerDoesNotFabricateAttribution()
  report/EvalReport.java           ✅ [P0] record() 加 trial、endStateChecked、assertionStrength
                                   ✅ [P1] markOutcome() 回填 + reliability 分区
                                   ✅ [P2] toolSelection 分区 + 修掉明细行的报告级假绿
                                      [P4/P5 改] 新指标分区
  cassette/Cassette.java           ✅ [P1] trials[][]，兼容旧 interactions[]
  transport/ReplayLlmTransport.java   ✅ [P1] beginCase(caseId, trial)，缺试次即报错
  transport/RecordingLlmTransport.java ✅ [P1] 跨试次累积同一个盒子
  build.gradle                     ✅ [P1] 透传 -Dagent.eval.trials
```

P0~P4 的**建设过程**没有任何生产代码改动。这是有意的：
评测体系的改造不应该有能力去弄坏被测系统。
（P5 的 BLOCKER-4 也按这个原则设计——在评测侧 transport 累计 usage，而不是给
`AgentTraceListener` 加方法。但 **P5 尚未开工**，`UsageAccumulator` 还不存在。）

## 8.1 缺陷修复（评测建成之后，单独一次改动）

评测发现问题和修问题是**两件事**，混在一起会让"这次改动到底修好了什么"无法归因。
所以三条缺陷是在 P0~P4 全部完成、指标基线固定之后，**单独一次改动**修掉的：

```
main/.../pkm/service/RagSearchService.java
    Hit 增加 relevance 字段（语义相关度，与 alpha 及命中通路无关）
    向量通路加 min-relevance 下限
main/.../pkm/crag/RetrievalEvaluator.java
    gradeByScore → grade，判级依据从 score 换成 relevance
main/.../pkm/crag/CorrectiveRetriever.java
    RRF 合并时把 relevance 一并取最大值传下去
main/.../agent/tool/impl/KnowledgeTools.java
    工具返回值带出 relevance（否则无法解释为什么判了这一级）
main/.../agent/runtime/turn/advisor/DisclosureInspector.java  ← 新增
    降级明示判据抽成纯函数：伪造归属一票否决 + 明示词表按校准集补齐
main/.../agent/runtime/turn/advisor/DegradeDisclosureAdvisor.java
    改调 DisclosureInspector；伪造归属场景给出针对性更强的 steer
main/resources/application.properties
    新增 pkm.rag.min-relevance=0.15
```

对应的**守护断言全部按原计划反转**（从"证明缺陷存在"改成"守住不复发"），
而不是删掉——它们守的那条线一直都在：

| 断言 | 反转前 | 反转后 |
|---|---|---|
| `RagDegradeCalibrationTest` | 多跳假降级 = 4/4 | 假降级率 = 0，**且**正确降级率仍 = 1.0 |
| `RagGoldenEvalTest.noScoreFloor` | 零分笔记占满 top-k | 更名 `scoreFloorDropsNoise`，命中全部过门槛 |
| `HonestyCalibrationTest` 生产判据 | κ < 0.4，泄漏恰好 1 条（d06） | 泄漏 = 0，κ ≥ 0.5，剩余分歧全是 IMPLICIT |

评测侧同时做了一处**去重**：`ProductionDisclosureBaseline` 和 `AttributionRedFlag`
原本各自维护一份抄自生产的关键词表，现在都改为直接调用 `DisclosureInspector`。
在"判据本身就是被测对象"的地方，**抄本必然漂移**。

---

# 九、排期与优先级

| 期 | 内容 | 依赖 | 工作量 | 收益 |
|---|---|---|---|---|
| **P0 ✅** | 端状态验证 + 全局不变量 + BLOCKER-1 | 无 | 已完成 | **最高**：消灭假绿，零成本 |
| **P1 ✅** | 真实录制 + pass@k/pass^k + BLOCKER-2 | API Key | 已完成（2026-09-04） | **高于预期**：指标恢复含义，首获可靠性数（pass^3=92.3%），并当天查出两个真实缺陷 |
| **P2 ✅** | 轨迹指标分解 + 负例补齐 | 无（不必等 P1） | 已完成 | 中高：能定位"为什么不对"，并解开了写路径覆盖的堵点 |
| **P3 ✅** | LLM 裁判 + 人工校准 | 无（校准集可先构造） | 已完成 | **高于预期**：意外查出生产判据 κ≈0，并交付了一条零误报的 CI 门禁 |
| **P4 ✅** | RAG 检索指标 + 金标集 + 忠实度 | P3 | 已完成 | **高于预期**：查出多跳被 100% 假降级、检索无分数下限，都是真实产品缺陷 |
| **缺陷修复 ✅** | 修掉 P3/P4 查出的三条 | P4 | 已完成 | **这是整套体系的第一笔实际回报**：假降级 4/4→0、precision@6 0.167→0.833、判据 κ −0.02→0.605 |
| **缺陷修复 ✅** | 修掉真实录制查出的三条 | P1 录制 | 已完成 | LEARN 模式 `read` 横切泄漏（含子代理 deny 继承的语义拆分）；空头承诺（宣告要查却零工具调用）；漂移警告恒为假警报（量具自身），见 §3.7 |
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
