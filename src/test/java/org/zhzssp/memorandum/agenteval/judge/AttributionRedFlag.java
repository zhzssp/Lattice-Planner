package org.zhzssp.memorandum.agenteval.judge;

import org.zhzssp.memorandum.feature.agent.runtime.turn.advisor.DisclosureInspector;

/**
 * 「伪造归属」红旗检测：检索降级时，答复是否把内容说成出自用户的笔记。
 *
 * <h3>它为什么能做到关键词基线做不到的事</h3>
 * 关键词基线问的是<b>"有没有说未找到"</b>，而这个检测问的是
 * <b>"有没有谎称来自笔记"</b>。后者才是危害的来源：
 * 用户看到"根据你的笔记，……"会默认这句话是自己写过的，从而放弃核实。
 *
 * <p>更关键的是两者的<b>集合形状</b>不同：诚实的说法是开放集（"没搜到""是空白"
 * "库里没有这方面的积累"，穷举是徒劳的），而伪造归属是个很窄的闭集。
 * <b>检测闭集能做到高精确率，检测开放集不能</b>——这是这条门禁成立的全部理由。
 *
 * <p>校准集上的表现（n=20）：捕获 5/6 的不诚实样本，在其余 14 条上<b>零误报</b>。
 * <b>高精确率、中等召回</b>正是 CI 门禁想要的形状——
 * 门禁误报一次，人就开始习惯性忽略它，此后它守什么都无所谓了。
 *
 * <h3>现在它只是生产判据的薄包装</h3>
 * 这套检测最初写在评测里，用来证明"生产判据漏了 d06 这一类"。证完之后
 * 它已经<b>被搬进生产</b>（{@link DisclosureInspector}），成为降级明示顾问的一票否决项。
 * 这里保留同名入口只是为了让既有断言不必改写，实现全部委托过去——
 * <b>评测和线上必须是同一份逻辑</b>，否则校准报告描述的就不是真正在跑的那个东西。
 *
 * <p>它<b>不能</b>捕获另一类不诚实：一句归属都不提、直接把通用知识当答案讲
 * （校准集里的 d03）。那类需要语义判断，属于 LLM 裁判的职责。
 * <b>把它写清楚，是为了不让人误以为加了这道门禁就万事大吉。</b>
 */
public final class AttributionRedFlag {

    private AttributionRedFlag() {}

    /** 命中的归属句式；未命中返回 null。 */
    public static String detect(String answer) {
        return DisclosureInspector.detectFabricatedAttribution(answer);
    }

    public static boolean fabricatesAttribution(String answer) {
        return DisclosureInspector.fabricatesAttribution(answer);
    }
}
