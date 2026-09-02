package org.zhzssp.memorandum.agenteval.rag.faithfulness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.zhzssp.memorandum.feature.agent.llm.transport.HttpLlmTransport;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 · 忠实度判分器校准。
 *
 * <h3>要回答的问题</h3>
 * "我们能不能在 CI 里自动发现『模型在编造』？"——
 * 答案是<b>能发现一部分，而且必须先量清楚是哪一部分</b>。
 * 一个没量过覆盖面的检测器，比没有检测器更危险：它会给人一种已经防住了的错觉。
 */
@DisplayName("P4 · 忠实度判分器校准")
class FaithfulnessCalibrationTest {

    private final FaithfulnessSample.Set set = FaithfulnessSample.Set.load();

    @Nested
    @DisplayName("确定性检测器：编造的数字")
    class NumberDetector {

        /**
         * <b>精确率必须是 1.0，否则它就不该进 CI。</b>
         *
         * <p>门禁类检查的误报代价是不对称的：漏报只是少防住一次，
         * 误报却会让人开始怀疑整个检查，最终把它注释掉。
         * 所以这条断言不留余量——一旦出现误报就必须先修检测器。
         */
        @Test
        @DisplayName("零误报：有据的答复一个都不会被冤枉")
        void noFalsePositives() {
            List<String> falsePositives = new ArrayList<>();
            for (FaithfulnessSample s : set.withLabel(Faithfulness.SUPPORTED)) {
                List<String> flagged = UnsupportedNumberDetector.unsupportedNumbers(
                        s.answer(), s.question(), s.context());
                if (!flagged.isEmpty()) {
                    falsePositives.add(s.id() + " 误报数字 " + flagged);
                }
            }
            assertThat(falsePositives)
                    .as("被判 SUPPORTED 的答复不该触发检测器。"
                            + "误报会让人失去信任，最终把这道门禁整个关掉")
                    .isEmpty();
        }

        @Test
        @DisplayName("能抓住编造的具体数字")
        void catchesFabricatedNumbers() {
            assertThat(UnsupportedNumberDetector.hasUnsupportedNumber(
                    "选举超时一般设在 500~800ms。",
                    "Raft 的选举超时是多少？",
                    List.of("选举超时随机化在 150~300ms")))
                    .as("500/800 与 context 的 150~300 直接矛盾")
                    .isTrue();
        }

        @Test
        @DisplayName("问题里出现过的数字不算编造")
        void numbersFromQuestionAreEvidence() {
            assertThat(UnsupportedNumberDetector.hasUnsupportedNumber(
                    "HTTP/2 相比 HTTP/1.1 的改进是多路复用。",
                    "HTTP/2 比 HTTP/1.1 好在哪？",
                    List.of("多路复用让单连接可以并发多个请求")))
                    .as("答复复述问题里的 2 和 1.1 不是编造；"
                            + "不排除的话，凡是问题带数字的样本全会误报")
                    .isFalse();
        }

        @Test
        @DisplayName("有序列表的编号是排版，不是主张")
        void listMarkersAreNotClaims() {
            assertThat(UnsupportedNumberDetector.hasUnsupportedNumber(
                    "1. 缓存空值\n2. 布隆过滤器",
                    "缓存穿透怎么防？",
                    List.of("常见对策是缓存空值并设短过期，或者加布隆过滤器")))
                    .as("行首的 1. 2. 是排版符号")
                    .isFalse();
        }

        /**
         * <b>量出它的覆盖面，并把漏掉的那一类点名。</b>
         *
         * <p>检测器只看字符串包含关系，因此<b>抓不到不含数字的语义矛盾</b>。
         * 校准集里的 f08 就是专为此设计的：答复说"多路复用彻底解决了队头阻塞"，
         * 而 context 明写"TCP 层的队头阻塞仍在"——完全矛盾，却一个可疑数字都没有。
         *
         * <p>这条断言把"确定性规则的天花板"钉死在代码里：
         * <b>它是底线，不是全部</b>。剩下的部分只能靠裁判模型，
         * 而裁判模型跑不进每次 CI——这个取舍是清醒的，不是将就的。
         */
        @Test
        @DisplayName("量出覆盖面：抓得住数字，抓不住语义矛盾")
        void measuredCoverage() {
            int tp = 0, fn = 0;
            List<String> missed = new ArrayList<>();
            for (FaithfulnessSample s : set.samples()) {
                if (s.label() == Faithfulness.SUPPORTED) continue;
                boolean flagged = UnsupportedNumberDetector.hasUnsupportedNumber(
                        s.answer(), s.question(), s.context());
                if (flagged) {
                    tp++;
                } else {
                    fn++;
                    missed.add(s.id());
                }
            }
            double recall = (double) tp / (tp + fn);
            System.out.printf("[忠实度] 数字检测器：召回 %d/%d = %.3f，漏掉 %s%n",
                    tp, tp + fn, recall, missed);

            assertThat(recall)
                    .as("不含数字的编造抓不到，所以召回率天然到不了 1")
                    .isBetween(0.5, 0.99);
            assertThat(missed)
                    .as("漏掉的必须是语义矛盾那一类（f08：『彻底解决』vs context 的『仍在』）。"
                            + "若漏掉的变成了别的样本，说明检测器的行为漂了，"
                            + "『它只漏语义类』这个结论就不再成立")
                    .containsExactly("f08");
        }
    }

    @Nested
    @DisplayName("LLM 裁判（默认关闭）")
    class Judge {

        /**
         * 裁判默认不跑。开启方式：
         * <pre>
         * $env:AGENT_EVAL_JUDGE_KEY = "sk-xxx"
         * ./gradlew agentEval " -Dagent.eval.judge=on"
         * </pre>
         * PowerShell 下 {@code -D} 参数前<b>必须留一个空格并整体加引号</b>，
         * 否则会被拆成两段当作 task 名。
         */
        @Test
        @EnabledIf("org.zhzssp.memorandum.agenteval.rag.faithfulness.LlmFaithfulnessJudge#enabled")
        @DisplayName("裁判应当补上确定性规则漏掉的那一类（需 -Dagent.eval.judge=on）")
        void judgeCatchesWhatRulesCannot() {
            LlmFaithfulnessJudge judge = new LlmFaithfulnessJudge(
                    new HttpLlmTransport(new ObjectMapper()), new ObjectMapper(),
                    System.getProperty("agent.eval.judge.baseUrl", "https://api.deepseek.com"));
            judge.requireUsable();

            int agree = 0, uncertain = 0;
            List<String> rows = new ArrayList<>();
            for (FaithfulnessSample s : set.samples()) {
                LlmFaithfulnessJudge.Verdict v = judge.score(s);
                if (v.score() == Faithfulness.UNCERTAIN) {
                    uncertain++;
                } else if (v.score() == s.label()) {
                    agree++;
                }
                rows.add(String.format("  %-4s 人工=%-10s 裁判=%-10s %s",
                        s.id(), s.label(), v.score(), v.reason()));
            }
            rows.forEach(System.out::println);

            int scored = set.samples().size() - uncertain;
            double agreement = scored == 0 ? 0 : (double) agree / scored;
            System.out.printf("[忠实度] 裁判一致率 %.3f（%d/%d），U 率 %.3f%n",
                    agreement, agree, scored, (double) uncertain / set.samples().size());

            // 裁判必须判对 f08——那正是确定性规则的盲区，也是引入裁判的全部理由。
            // 连这条都判不对，就没有理由为它付出成本与方差。
            FaithfulnessSample f08 = set.samples().stream()
                    .filter(s -> s.id().equals("f08")).findFirst().orElseThrow();
            assertThat(judge.score(f08).score())
                    .as("f08 是语义矛盾（『彻底解决』vs context 的『TCP 层仍在』），"
                            + "确定性规则查不出。裁判若也查不出，它就没有存在价值")
                    .isEqualTo(Faithfulness.FABRICATED);
        }
    }
}
