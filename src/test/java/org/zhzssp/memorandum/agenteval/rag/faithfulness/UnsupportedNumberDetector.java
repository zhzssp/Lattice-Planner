package org.zhzssp.memorandum.agenteval.rag.faithfulness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性检测器：答复里出现了 context 与问题<b>都没有</b>的具体数字。
 *
 * <h3>为什么单挑数字</h3>
 * 忠实度整体上需要裁判模型才判得准，但<b>编造的数字是其中最危险、也最好查的一类</b>：
 * <ul>
 *   <li><b>最危险</b>：数字自带权威感。"选举超时是 150~300ms"读起来像是从笔记里抄的，
 *       用户不会去核对；而一句含糊的话反而会引起警觉</li>
 *   <li><b>最好查</b>：它是字符串包含关系，不需要语义理解，因此<b>能进每次 CI</b></li>
 * </ul>
 * 这是刻意的取舍：<b>用一个覆盖面窄但零成本、零方差的规则，
 * 守住裁判模型跑不起来时的底线</b>。它抓不到的部分由 {@link LlmFaithfulnessJudge} 兜。
 *
 * <h3>为什么把问题文本也算作依据</h3>
 * 用户问"HTTP/2 比 1.1 快多少"，答复复述 "HTTP/2" 里的 2 和 1.1
 * 并不是编造。不排除的话，凡是问题里带数字的样本全会误报。
 *
 * <h3>已知的漏网之处（不打算修）</h3>
 * 中文数字（"三到四层"）、模型自行换算的单位（context 写 0.3 秒、答复写 300 毫秒）
 * 都抓不到。补这些会引入猜测，而<b>这个检测器的全部价值在于它不猜</b>——
 * 一旦开始猜，它就既不比裁判准、又失去了确定性。
 */
public final class UnsupportedNumberDetector {

    /** 带小数点的数字串；不含正负号，避免把范围号"150-300"里的减号吞进来。 */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** 行首的有序列表标记（"1. " / "2）"），是排版不是主张。 */
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*\\d+\\s*[.、)）]\\s");

    private UnsupportedNumberDetector() {}

    /**
     * @return 答复中出现、但问题与 context 里都找不到的数字。空表示没查出问题。
     */
    public static List<String> unsupportedNumbers(String answer, String question, List<String> context) {
        if (answer == null || answer.isBlank()) return List.of();

        String stripped = LIST_MARKER.matcher(answer).replaceAll(" ");
        String evidence = (question == null ? "" : question)
                + "\n" + String.join("\n", context == null ? List.of() : context);

        Set<String> flagged = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(stripped);
        while (m.find()) {
            String num = m.group();
            if (!evidence.contains(num)) flagged.add(num);
        }
        return new ArrayList<>(flagged);
    }

    public static boolean hasUnsupportedNumber(String answer, String question, List<String> context) {
        return !unsupportedNumbers(answer, question, context).isEmpty();
    }
}
