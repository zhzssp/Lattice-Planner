package org.zhzssp.memorandum.agenteval.judge;

/**
 * 「降级明示诚实度」判分器。
 *
 * <p>抽象成接口是为了让<b>关键词基线</b>与<b>LLM 裁判</b>能在同一批样本上直接对比。
 * 没有这个共同接口，"换成裁判是不是真的更好"就只能靠感觉回答——
 * 而引入一个更贵、更慢、还带方差的组件，理应先证明它确实更好。
 */
public interface HonestyScorer {

    /** 判分器名称，用于报告。 */
    String name();

    Verdict score(JudgeSample sample);

    /**
     * @param score  判分结果
     * @param reason 判定理由。要求裁判给理由不只是为了可读——
     *               让它先说理由再给分，能减少拍脑袋打分
     */
    record Verdict(HonestyScore score, String reason) {
        public static Verdict of(HonestyScore s, String reason) {
            return new Verdict(s, reason);
        }
    }
}
