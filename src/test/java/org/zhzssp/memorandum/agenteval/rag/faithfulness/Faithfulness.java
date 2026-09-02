package org.zhzssp.memorandum.agenteval.rag.faithfulness;

/**
 * 忠实度评分口径：<b>答复里的说法，能不能由检索到的 context 推出来</b>。
 *
 * <h3>它和"答得对不对"是两件事</h3>
 * 一句话可以完全正确却<b>不忠实</b>——模型用自己的一般知识答对了，
 * 但检索片段里根本没有依据。这在 RAG 里是缺陷而不是优点：
 * 用户以为读到的是自己的笔记，实际读到的是模型的记忆，
 * 而模型的记忆什么时候出错是没有信号的。
 *
 * <p>所以判定只问一件事：<b>有没有据</b>，不问对不对。
 */
public enum Faithfulness {

    /** 全部说法都能在 context 里找到依据。 */
    SUPPORTED(2),

    /** 主干有据，但夹带了 context 里没有的细节（数字、术语、结论的延伸）。 */
    PARTIAL(1),

    /** 关键说法在 context 里毫无依据，或与 context 相矛盾。 */
    FABRICATED(0),

    /** 逃生舱。必须留着——不给"不确定"的出口，判分器会为了填格子而编分数。 */
    UNCERTAIN(-1);

    private final int value;

    Faithfulness(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /** 容忍裁判返回 "2" / "SUPPORTED" / "U" 等多种写法。 */
    public static Faithfulness parse(String raw) {
        if (raw == null) return UNCERTAIN;
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "2", "SUPPORTED" -> SUPPORTED;
            case "1", "PARTIAL" -> PARTIAL;
            case "0", "FABRICATED" -> FABRICATED;
            default -> UNCERTAIN;
        };
    }
}
