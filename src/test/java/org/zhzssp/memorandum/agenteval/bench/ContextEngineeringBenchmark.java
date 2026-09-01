package org.zhzssp.memorandum.agenteval.bench;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.memory.AgentFact;
import org.zhzssp.memorandum.feature.agent.memory.AgentFactRepository;
import org.zhzssp.memorandum.feature.agent.memory.FactService;
import org.zhzssp.memorandum.feature.agent.runtime.ContextCompactor;
import org.zhzssp.memorandum.feature.agent.runtime.ConversationMemory;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnOutcome;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上下文工程 P1 的「开关前后对比」基准。
 *
 * <h3>为什么单独建这个类，而不是塞进现有单测</h3>
 * <p>{@code ContextCompactorTest} 守的是<strong>单次折叠</strong>的行为正确性。
 * 但这个特性真正要解决的问题是跨几十轮的：「用户第 3 轮说的 deadline，第 35 轮还在不在」。
 * 那是一条只有把整段对话跑完才看得见的曲线，本类负责把它量出来。</p>
 *
 * <h3>★哪些数字是真的，哪些是模拟的（读结论前必须先看这段）</h3>
 * <ul>
 *   <li><strong>真实</strong>：窗口淘汰机制、折叠触发时机、折叠级联（摘要本身会被再次折叠）、
 *       摘要 200 字截断、纯噪声短路、facts 注入的 DAY 粒度卡点。这些全是被测代码的真实行为。</li>
 *   <li><strong>模拟</strong>：摘要器本身。这里用一个<em>抽取式</em>桩替代真实 LLM——
 *       它按真实 prompt 的指令（"必须保留约束、可以丢弃寒暄"）保留含具体值的句子。
 *       所以「留存率」量的是<strong>折叠机制能否把约束带过窗口边界</strong>，
 *       不是「某个模型的摘要写得好不好」。后者是另一根轴，要在 record 模式下用真 API 量。</li>
 * </ul>
 *
 * <p>产物写到 {@code build/agent-eval/context-engineering.md}，可直接引用。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContextEngineeringBenchmark {

    /** 报告累积区：三个基准各写一段，最后一个测试统一落盘。 */
    private static final List<String> REPORT = new ArrayList<>();

    /* ==================== 场景数据 ==================== */

    /**
     * 5 条硬约束，分别在第 1..5 轮说出。每条带一个独一无二的可检索标记，
     * 用来在几十轮之后判定「这条约束还在不在上下文里」。
     */
    private record Constraint(String marker, String sentence) {
    }

    private static final List<Constraint> CONSTRAINTS = List.of(
            new Constraint("下周五", "项目 X 的 deadline 是下周五，不能再拖。"),
            new Constraint("8000", "这次采购预算上限是 8000 元，超了要重新审批。"),
            new Constraint("v2.3.1", "线上必须锁在 v2.3.1 这个版本，别升级。"),
            new Constraint("3 台", "机房只剩 3 台机器可用，方案按这个规模来。"),
            new Constraint("杭州", "评审会在杭州开，异地同事要提前订票。"));

    /** 抽取式摘要器识别「含具体值的句子」用的标记。 */
    private static final Pattern CONCRETE = Pattern.compile("\\d|deadline|版本|预算|评审");

    /* ==================== 基准 1：长对话中的约束留存率 ==================== */

    @Test
    @Order(1)
    @DisplayName("★基准1：40 轮对话后，关键约束的留存率（compaction 关 vs 开）")
    void constraintRetentionAcrossLongConversation() {
        int turns = 40;

        // 两种约束分布：集中在开头 vs 均匀散布。折叠是「把最老一段压成一条、放回队头」，
        // 所以约束落在对话的哪个位置，决定了它要被反复压缩几次——这是机制的真实边界。
        Retention offEarly = runConversation(turns, false, 1);
        Retention onEarly = runConversation(turns, true, 1);
        Retention onSpread = runConversation(turns, true, 4);

        assertEquals(0, offEarly.retained(),
                "窗口 " + ConversationMemory.windowSize() + " 条、每轮 2 条，第 1..5 轮的约束"
                        + "到第 40 轮必然已被 pollFirst 淘汰干净");
        assertTrue(onEarly.retained() > offEarly.retained(),
                "开启折叠后留存必须严格变多，否则这个特性没有存在价值");

        REPORT.add(section("基准 1 · 长对话约束留存",
                """
                场景：40 轮对话，5 条硬约束（deadline / 预算 / 版本 / 规模 / 地点），
                其余轮次为无约束闲聊。窗口 %d 条，每轮 2 条消息。
                问题：跑到第 40 轮时，这 5 条约束还有几条留在上下文里？

                | 配置 | 约束分布 | 留存 | 留存率 | 摘要 LLM 调用 | 末态上下文条数 |
                | --- | --- | --- | --- | --- | --- |
                | compaction 关（原行为） | 第 1..5 轮 | %d / %d | %.0f%% | %d | %d |
                | compaction 开 | 第 1..5 轮 | %d / %d | %.0f%% | %d | %d |
                | compaction 开 | 第 1/5/9/13/17 轮 | %d / %d | %.0f%% | %d | %d |

                丢失的约束：关=%s；开·集中=%s；开·分散=%s

                读法（两条，第二条更重要）：

                1. 关闭时是 0%%，而且是**静默**丢失——模型和用户都不知道信息掉了，
                   于是照着错的 deadline 排期。这就是这个特性存在的理由。

                2. 开启后集中分布能到 %.0f%%，但这个数字有运气成分，不能当作「无损」宣传：
                   折叠把最老一段压成一条**放回队头**，而摘要截断是从**尾部**切的
                   （超过 %d 字截断），所以越早说的约束越靠近队头、越受保护。
                   一旦把同样 5 条约束散布到第 1..17 轮，留存就掉到 %.0f%%——
                   靠后的约束每次都落在待折叠段的尾部，被反复压缩后截掉。
                   **真实结论是「早期约束基本保得住，中段约束会衰减」**，
                   而不是「开了 compaction 就不丢信息」。
                """.formatted(
                        ConversationMemory.windowSize(),
                        offEarly.retained(), CONSTRAINTS.size(),
                        100.0 * offEarly.retained() / CONSTRAINTS.size(),
                        offEarly.summarizerCalls(), offEarly.finalSize(),
                        onEarly.retained(), CONSTRAINTS.size(),
                        100.0 * onEarly.retained() / CONSTRAINTS.size(),
                        onEarly.summarizerCalls(), onEarly.finalSize(),
                        onSpread.retained(), CONSTRAINTS.size(),
                        100.0 * onSpread.retained() / CONSTRAINTS.size(),
                        onSpread.summarizerCalls(), onSpread.finalSize(),
                        offEarly.lost(), onEarly.lost(), onSpread.lost(),
                        100.0 * onEarly.retained() / CONSTRAINTS.size(),
                        200,
                        100.0 * onSpread.retained() / CONSTRAINTS.size())));
    }

    /* ==================== 基准 2：纯工具噪声短路省下的调用 ==================== */

    @Test
    @Order(2)
    @DisplayName("★基准2：纯工具噪声短路省下的摘要调用")
    void toolNoiseShortCircuitSavings() {
        // 一次 10 步 ReAct = 20 条工具 trace，其间没有任何真实对话
        int folds = 6;
        AtomicInteger calls = new AtomicInteger();
        ConversationMemory memory = new ConversationMemory();
        ContextCompactor compactor = newCompactor(memory, countingLlm(calls));
        String sid = "noise";

        for (int i = 0; i < folds * 10; i++) {
            memory.append(sid, "assistant", "{\"tool\":\"task.search\",\"arguments\":{}}");
            memory.append(sid, "user", "[tool_result task.search]\n{\"items\":[]}");
            compactor.compactIfNeeded(sid, outcome());
        }

        assertEquals(0, calls.get(),
                "整段都是工具噪声时一次 LLM 都不该付——否则每轮 ReAct 都在为无语义内容烧钱");

        REPORT.add(section("基准 2 · 纯工具噪声短路",
                """
                场景：连续 %d 条工具 trace（%d 步 ReAct 的量级），其间无任何真实对话，
                期间反复触发折叠判定。

                | 指标 | 数值 |
                | --- | --- |
                | 触发折叠判定次数 | %d |
                | 实付摘要 LLM 调用 | %d |

                读法：不做短路的话，每次折叠都会为一段「`{"tool":...}` + `[tool_result ...]`」
                付一次 LLM 调用，而摘要出来的东西对用户零价值。短路把这笔钱直接归零。
                """.formatted(folds * 20, folds * 10, folds * 10, calls.get())));
    }

    /* ==================== 基准 3：stable facts 的 system 段字节稳定性 ==================== */

    @Test
    @Order(3)
    @DisplayName("★基准3：stable-apply-granularity 对 system 段字节稳定性的影响")
    void stableFactsPrefixStability() {
        int turns = 40;
        int newFactsToday = 8;

        int immediate = distinctSystemSnippets("IMMEDIATE", turns, newFactsToday);
        int day = distinctSystemSnippets("DAY", turns, newFactsToday);

        assertEquals(1, day, "DAY 粒度下 system 段在一天内必须字节恒定，否则前缀缓存白做");
        assertTrue(immediate > day, "IMMEDIATE 每落一条新 fact 就换一次 system 段");

        REPORT.add(section("基准 3 · stable facts 与前缀缓存",
                """
                场景：一天内跑 %d 轮，期间陆续抽出 %d 条新的稳定 fact。
                稳定 facts 拼进 system prompt 并参与 memoHash —— 它一变，上游 prompt cache 的
                前缀就被打穿。

                | granularity | system 段出现的不同字节版本数 | 当天前缀缓存可命中 |
                | --- | --- | --- |
                | IMMEDIATE（立即生效） | %d | 否，每落一条 fact 断一次 |
                | DAY（攒到次日，现配置） | %d | 是，全天恒定 |

                读法：DAY 买到的是「system 段一整天不变」，代价是新抽到的稳定偏好当天不进
                上下文。这个取舍成立的前提是**稳定偏好本就是长期的**，晚一天无妨；而前缀
                缓存是每一轮都在付的成本。需要立刻生效的约束应该被抽成 VOLATILE，
                走 history 首条注入 —— 那条路径不参与 memoHash，改多少次都不打穿缓存。
                """.formatted(turns, newFactsToday, immediate, day)));
    }

    /* ==================== 落盘 ==================== */

    @Test
    @Order(99)
    @DisplayName("汇总落盘到 build/agent-eval/context-engineering.md")
    void writeReport() throws IOException {
        assertEquals(3, REPORT.size(), "三个基准都应已运行（本方法靠 @Order 排在最后）");

        Path out = Path.of("build", "agent-eval", "context-engineering.md");
        Files.createDirectories(out.getParent());
        String doc = """
                # 上下文工程 P1 · 开关前后对比

                > 由 `ContextEngineeringBenchmark` 自动生成，`./gradlew test --tests '*ContextEngineeringBenchmark*'` 可复现。
                >
                > **口径声明**：窗口淘汰、折叠触发与级联、200 字截断、噪声短路、DAY 粒度卡点
                > 均为被测代码的真实行为；**摘要器本身是抽取式桩**，因此留存率量的是
                > 「折叠机制能否把约束带过窗口边界」，不是某个模型的摘要质量。

                """ + String.join("\n", REPORT);
        Files.writeString(out, doc);

        assertTrue(Files.size(out) > 0);
        System.out.println("[bench] 上下文工程对比报告已写入 " + out.toAbsolutePath());
    }

    /* ==================== 内部：场景驱动 ==================== */

    private record Retention(int retained, List<String> lost, int summarizerCalls, int finalSize) {
    }

    /**
     * 跑一段 {@code turns} 轮的对话，返回末态的约束留存情况。
     *
     * <p>折叠调用点刻意放在<strong>轮首</strong>，与 {@code AgentOrchestrator.handleUserTurn}
     * 一致——这条路径上没有工具调用，若只在 appendToolTrace 后折叠，纯聊天会话永远
     * 折叠不到，约束照样静默滑出。</p>
     */
    private Retention runConversation(int turns, boolean compactionEnabled, int spread) {
        ConversationMemory memory = new ConversationMemory();
        AtomicInteger calls = new AtomicInteger();
        ContextCompactor compactor = newCompactor(memory, extractiveLlm(calls));
        ReflectionTestUtils.setField(compactor, "enabled", compactionEnabled);
        String sid = "bench";

        for (int turn = 1; turn <= turns; turn++) {
            compactor.compactIfNeeded(sid, outcome());
            // 第 i 条约束落在第 1 + i*spread 轮；spread=1 即集中在开头
            int slot = (turn - 1) % spread == 0 ? (turn - 1) / spread : -1;
            String userMsg = slot >= 0 && slot < CONSTRAINTS.size()
                    ? CONSTRAINTS.get(slot).sentence()
                    : "第 " + turn + " 轮：那我们继续看看别的吧，你怎么想。";
            memory.append(sid, "user", userMsg);
            memory.append(sid, "assistant", "第 " + turn + " 轮：好的，我记下了，继续。");
        }

        String context = String.join("\n",
                memory.history(sid).stream().map(ConversationMemory.Msg::content).toList());
        List<String> lost = new ArrayList<>();
        int retained = 0;
        for (Constraint c : CONSTRAINTS) {
            if (context.contains(c.marker())) retained++;
            else lost.add(c.marker());
        }
        return new Retention(retained, lost, calls.get(), memory.size(sid));
    }

    /** 统计 {@code stableSnippet} 在一天内产生过多少种不同字节内容。 */
    private int distinctSystemSnippets(String granularity, int turns, int newFactsToday) {
        AgentFactRepository repo = mock(AgentFactRepository.class);
        FactService facts = new FactService(repo, mock(LlmGateway.class), new ObjectMapper());
        ReflectionTestUtils.setField(facts, "enabled", true);
        ReflectionTestUtils.setField(facts, "maxStable", 20);
        ReflectionTestUtils.setField(facts, "maxVolatile", 15);
        ReflectionTestUtils.setField(facts, "minConfidenceRaw", "MEDIUM");
        ReflectionTestUtils.setField(facts, "stableGranularity", granularity);

        // 昨天之前就已存在的稳定 facts：DAY 粒度下当天只看得到这些
        List<AgentFact> settled = List.of(stableFact("习惯早上做深度工作"));
        // 今天陆续新抽出来的：IMMEDIATE 下每落一条就换一次 system 段
        List<AgentFact> growing = new ArrayList<>(settled);

        when(repo.findStableActiveCreatedBefore(eq(1L), any())).thenReturn(settled);
        when(repo.findStableActive(anyLong())).thenAnswer(inv -> List.copyOf(growing));

        Set<String> distinct = new LinkedHashSet<>();
        int factEveryNTurns = Math.max(1, turns / newFactsToday);
        for (int turn = 1; turn <= turns; turn++) {
            if (turn % factEveryNTurns == 0 && growing.size() - settled.size() < newFactsToday) {
                growing.add(stableFact("今天新抽到的偏好 #" + (growing.size() - settled.size() + 1)));
            }
            distinct.add(facts.stableSnippet(1L));
        }
        return distinct.size();
    }

    /* ==================== 内部：桩与工具 ==================== */

    private ContextCompactor newCompactor(ConversationMemory memory, LlmGateway llm) {
        ContextCompactor c = new ContextCompactor(memory, llm);
        ReflectionTestUtils.setField(c, "enabled", true);
        ReflectionTestUtils.setField(c, "triggerRatio", 0.8);
        ReflectionTestUtils.setField(c, "foldSize", 10);
        ReflectionTestUtils.setField(c, "summaryMaxChars", 200);
        ReflectionTestUtils.setField(c, "minDialogue", 6);
        return c;
    }

    /**
     * 抽取式摘要器：按真实 prompt 的指令保留「含具体值的句子」，丢弃寒暄。
     *
     * <p>刻意不做成「原样返回全部对话」——那会让留存率虚高到 100%，量不出折叠级联
     * 与 200 字截断带来的真实衰减。</p>
     */
    private LlmGateway extractiveLlm(AtomicInteger calls) {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.generateText(anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            String prompt = inv.getArgument(0);
            int split = prompt.indexOf("\n\n");
            String dialog = split < 0 ? prompt : prompt.substring(split + 2);
            List<String> kept = new ArrayList<>();
            for (String line : dialog.split("\\R")) {
                String body = line.replaceFirst("^(user|assistant): ", "").strip();
                if (body.isEmpty()) continue;
                if (body.startsWith("[对话摘要]")) continue; // 段头不是内容
                if (CONCRETE.matcher(body).find()) kept.add(body);
            }
            return kept.isEmpty() ? "（本段无约束性内容）" : String.join("；", kept);
        });
        return llm;
    }

    private LlmGateway countingLlm(AtomicInteger calls) {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.generateText(anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return "摘要";
        });
        return llm;
    }

    private AgentFact stableFact(String value) {
        AgentFact f = new AgentFact();
        f.setKind(AgentFact.Kind.STABLE);
        f.setStatus(AgentFact.Status.ACTIVE);
        f.setFactValue(value);
        return f;
    }

    private TurnOutcome outcome() {
        return new TurnOutcome("bench", "chat", "基准");
    }

    private String section(String title, String body) {
        return "## " + title + "\n\n" + body;
    }
}
