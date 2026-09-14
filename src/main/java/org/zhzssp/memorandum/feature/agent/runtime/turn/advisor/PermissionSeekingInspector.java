package org.zhzssp.memorandum.feature.agent.runtime.turn.advisor;

import java.util.List;

/**
 * 许可式反问检测：模型<b>已经知道要做什么</b>，却在文字里向用户讨一句"可以吗"。
 *
 * <h3>要拦的到底是什么</h3>
 * <p>本项目里"要不要执行"有<b>两套互不知情的实现</b>：</p>
 * <ol>
 *   <li><b>代码这套</b>：{@code @AgentTool(requiresConfirm=true)} → {@code ToolApprovalPolicy}
 *       → UI 弹窗。确定性的，24 个工具挂了这个标，用户点了才执行。</li>
 *   <li><b>模型这套</b>：它自己在自然语言里问一句"确认吗"。<b>完全不受治理</b>，
 *       想问就问、不想问就不问。</li>
 * </ol>
 * <p>用户实际遇到的是第二套，所以体验是随机的：同一句"把过期的都标记完成"，
 * 三次试验里两次直接做了、一次停下来问。产品决策是<b>第二套不该存在</b>——
 * 需要确认的操作一律走弹窗，模型直接发调用。</p>
 *
 * <h3>为什么只认"确认/确定"，不认"要我……吗"</h3>
 * <p>这是被真实录制逼出来的<b>精度约束</b>。{@code readonly_intent_no_write}
 * 正常收尾时说的是：</p>
 * <pre>
 *   "你本周目前没有任何已安排的任务。……要我帮你安排点什么吗？"
 * </pre>
 * <p>这是<b>正当的后续提议</b>——对象都还没定（"点什么"），谈不上扣着不做。
 * 若把"要我……吗"也算进来，这条通过的用例会被当场拦下。</p>
 *
 * <p>而 {@code batch_complete_overdue_only} 挂掉那次说的是：</p>
 * <pre>
 *   "已过期的任务有：90203 整理项目文档、90202 回复客户邮件、90201 提交客户资料。
 *    确认把这三条标记为完成吗？"
 * </pre>
 * <p>目标已经<b>逐条枚举带 ID</b> 了，只差动手。"确认"这个词正是
 * "我知道要做什么、只等你点头"的标志，而"要我……吗"不是。</p>
 *
 * <h3>为什么要求同一句</h3>
 * <p>整段找"确认"再整段找"吗"会误伤：读意图短语表里就有
 * <b>"我先确认一下"</b>，它跟段落末尾任何一个"吗"都能凑成一对。
 * 按句切分后这种跨句凑对就不成立了。</p>
 */
public final class PermissionSeekingInspector {

    /**
     * 许可标志词。刻意<b>不含</b>"要我""需要""可以吗"——见类注释里的精度约束。
     */
    private static final List<String> PERMISSION_MARKERS = List.of("确认", "确定");

    /** 句子终止符。中英文都收，模型的输出两种都出现过。 */
    private static final String SENTENCE_DELIMITERS = "。！？!?；;\n";

    private PermissionSeekingInspector() {
    }

    /**
     * 返回构成许可式反问的<b>那一句</b>；没有则返回 null。
     *
     * <p>返回整句而非布尔值，是为了让 steer 能把原话引回去。
     * 与 {@link AnnouncedActionInspector#detect} 同样的理由：
     * 指名道姓的纠正比泛泛的"请重试"更容易被执行。
     */
    public static String detect(String finalAnswer) {
        if (finalAnswer == null || finalAnswer.isBlank()) return null;
        for (String sentence : splitSentences(finalAnswer)) {
            if (!isInterrogative(sentence)) continue;
            for (String marker : PERMISSION_MARKERS) {
                if (sentence.contains(marker)) {
                    return sentence.trim();
                }
            }
        }
        return null;
    }

    /** {@link #detect} 的布尔形式。 */
    public static boolean seeksPermission(String finalAnswer) {
        return detect(finalAnswer) != null;
    }

    /**
     * 一句里同时出现许可标志词与疑问标记才算数。
     *
     * <p>只看"？"不够：模型经常写成"确认按上述范围标记为完成吗"后面跟中文句号，
     * 甚至不加标点直接换行。"吗"才是汉语是非问句的稳定标志。
     */
    private static boolean isInterrogative(String sentence) {
        return sentence.contains("吗") || sentence.contains("？") || sentence.contains("?");
    }

    private static List<String> splitSentences(String text) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : text.toCharArray()) {
            cur.append(c);
            if (SENTENCE_DELIMITERS.indexOf(c) >= 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }
}
