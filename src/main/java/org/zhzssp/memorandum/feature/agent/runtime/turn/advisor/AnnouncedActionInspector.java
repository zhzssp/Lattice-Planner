package org.zhzssp.memorandum.feature.agent.runtime.turn.advisor;

import java.util.List;

/**
 * 检测终答里的<b>「宣告了动作却没执行」</b>——模型用自然语言说"让我查一下"，
 * 但没有发出任何工具调用，这句话本身就成了给用户的最终答复。
 *
 * <h3>它守的是什么</h3>
 * ReAct 循环判定收尾的依据是「本次响应解析不出工具调用」。
 * 这个判据把两种截然不同的情况混为一谈：
 * <ul>
 *   <li><b>真的答完了</b>——"你这周有 3 件事：……"；</li>
 *   <li><b>只是在宣告</b>——"让我先查询一下本周的任务情况。"</li>
 * </ul>
 * 后者对用户是一次<b>纯粹的失败</b>：问了日程，拿回一句空头承诺，
 * 而系统还把这一轮记作"干净完成"（{@code FINAL_ANSWER}）。
 *
 * <h3>为什么用确定性短语表，而不是再问一次模型</h3>
 * 与 {@link DisclosureInspector} 同一立场：收尾前的检查必须<b>便宜且可预测</b>。
 * 多一次 LLM 调用去判断"它是不是在放空话"，成本翻倍、还引入新的不确定性。
 *
 * <h3>误报控制</h3>
 * 短语表<b>只收前瞻性表达</b>（"让我查""我先查"），不收完成时（"我查了""已为你查到"）——
 * 后者说明动作已经发生。即便如此仍可能误判，所以调用方
 * （{@link UnfulfilledActionAdvisor}）还叠了一道硬条件：
 * <b>本轮一次工具都没执行过</b>。两个条件同时成立才判定，
 * 单靠文本匹配是不够的。
 */
public final class AnnouncedActionInspector {

    /**
     * 前瞻性动作宣告短语。
     *
     * <p>刻意只放<b>动作意图</b>，不放"稍等""马上"这类纯语气词——
     * 后者出现在正常答复结尾的概率太高（"稍等我还有个建议"），
     * 收进来会把误报率推上去。
     */
    private static final List<String> ANNOUNCEMENTS = List.of(
            "让我查", "让我先查", "让我来查", "让我看看", "让我先看",
            "让我检索", "让我搜索", "让我查询", "让我先查询",
            "我来查", "我先查", "我这就查", "我去查", "我来看看",
            "我需要先查", "我需要查", "我需要先看", "我需要调用",
            "我将查", "我会查", "我将调用", "我会调用",
            "正在查询", "正在检索", "我先了解", "我先确认一下"
    );

    private AnnouncedActionInspector() {
    }

    /**
     * 返回终答里命中的动作宣告短语；没有则返回 null。
     *
     * <p>返回<b>具体短语</b>而不是布尔值，是为了让 steer 消息能把原话引回去
     * （"你上面说了『让我查询』，但没有发出工具调用"）。
     * 指名道姓的纠正比泛泛的"请重试"更容易被模型执行。
     *
     * <p><b>命中多条时取最长的那条。</b>短语表里存在前缀关系
     * （"让我查" ⊂ "让我查询"），按表序返回会引出"让我查"这种<b>被截断的原话</b>，
     * 而这个方法的全部意义就在于把原话<b>照原样</b>引回去——
     * 引错了话，纠正的说服力就打了折。
     */
    public static String detect(String finalAnswer) {
        if (finalAnswer == null || finalAnswer.isBlank()) return null;
        String best = null;
        for (String phrase : ANNOUNCEMENTS) {
            if (finalAnswer.contains(phrase)
                    && (best == null || phrase.length() > best.length())) {
                best = phrase;
            }
        }
        return best;
    }

    /** {@link #detect} 的布尔形式。 */
    public static boolean announcesUnfulfilledAction(String finalAnswer) {
        return detect(finalAnswer) != null;
    }
}
