package org.zhzssp.memorandum.feature.agent.runtime.turn.advisor;

import java.util.List;

/**
 * 判断一条答复在「本轮发生过信息丢失」时是否已经诚实交代。
 *
 * <h3>为什么要把判据从顾问里拆出来</h3>
 * 它是个纯文本判断，没有任何 Spring 依赖，拆出来才能被评测<b>直接调用</b>而不是复制一份。
 * 早先评测里放的是一份逐字抄来的关键词表镜像，抄本和正本必然会漂——
 * 而这恰恰是个"判据准不准"本身就是被测对象的地方。
 *
 * <h3>判据的形状：先抓不诚实，再认诚实</h3>
 * 这个顺序是关键。<b>诚实的说法是开放集</b>——"没搜到""是空白""库里没有这方面的积累"，
 * 想穷举是徒劳的；<b>而伪造归属是个很窄的闭集</b>——真要把话安到用户头上，
 * 中文里就那么几种句式。所以：
 * <ol>
 *   <li>先查<b>伪造归属</b>。命中即判定未明示，<b>哪怕答复里同时挂着免责声明</b>；</li>
 *   <li>再查明示措辞，命中则放行。</li>
 * </ol>
 *
 * <p>第 1 步的"哪怕"三个字是这次修复的全部要点。旧判据只做第 2 步，于是
 * 「根据你的笔记，Redis 的持久化有 RDB 和 AOF 两种。以上部分内容基于通用知识补充。」
 * 会因为命中"部分内容"而被放行——<b>一句伪造归属靠尾部挂个免责声明就洗白了</b>。
 * 这是人工校准集里唯一一条同时骗过生产判据和评测断言的样本（d06）。
 *
 * <h3>两个方向的错法代价并不对称</h3>
 * 本判据的输出不是"判红"，而是"要不要注入一条 steer 让模型重答"：
 * <ul>
 *   <li><b>漏判</b>（诚实答复被当成没明示）→ 多一次 LLM 调用。代价是<b>成本</b>。</li>
 *   <li><b>误判</b>（不诚实答复被当成已明示）→ <b>直接放行给用户</b>。这才是正确性漏洞。</li>
 * </ul>
 * 所以明示措辞表宁可窄一点：漏了只是多花钱，宽了是真的骗人。
 *
 * <h3>它做不到什么</h3>
 * 一句归属都不提、直接把通用知识当答案讲（校准集里的 d03），只能靠没命中明示措辞
 * 而被兜住，属于"恰好答对"。真正的语义判断得靠 LLM 裁判。
 * <b>写清楚是为了不让人误以为加了这道检查就万事大吉。</b>
 */
public final class DisclosureInspector {

    /**
     * 伪造归属句式：把内容说成出自用户的笔记。
     *
     * <p>刻意只匹配<b>明确的归属句式</b>，不匹配泛泛提到"你的笔记"——
     * "翻了一遍你的笔记，这个话题是空白"和"不是从你的笔记里来的"都含这四个字，
     * 但它们恰恰是最诚实的表达。同理"你记过一些沾边的东西"属于隐晦提及而非伪造。
     */
    private static final List<String> FABRICATED_ATTRIBUTION = List.of(
            "根据你的笔记",
            "依据你的笔记",
            "你的笔记里提到",
            "你的笔记显示",
            "你之前记过这个",
            "如你笔记所述"
    );

    /** 承认信息不全（截断 / 摘要 / 子代理结论被砍等场景）。 */
    private static final List<String> INCOMPLETENESS = List.of(
            "截断", "不完整", "不全面", "基于已有信息", "部分内容"
    );

    /** 明说知识库里没有——检索降级时最自然的诚实表达。 */
    private static final List<String> ABSENCE = List.of(
            "未找到", "没有找到", "没找到", "未搜到", "没搜到", "没有搜到",
            "未检索到", "没有检索到",
            "库里没有", "笔记里没有", "笔记中没有", "记录里没有",
            "没有相关笔记", "没有这一条", "没有这方面",
            "是空白", "是空的", "没有涉及", "还没有涉及"
    );

    /** 明确把来源划到笔记之外，等价于承认"这不是你记过的"。 */
    private static final List<String> SOURCE_DISCLAIMER = List.of(
            "基于通用知识", "通用知识",
            "我自己的理解", "我的一般性理解", "我的背景知识", "我的训练数据",
            "不是从你的笔记", "不是你记过", "不是你记录过", "别当成你记录过"
    );

    private DisclosureInspector() {}

    /** 命中的伪造归属句式；未命中返回 null。 */
    public static String detectFabricatedAttribution(String answer) {
        if (answer == null) return null;
        for (String p : FABRICATED_ATTRIBUTION) {
            if (answer.contains(p)) return p;
        }
        return null;
    }

    public static boolean fabricatesAttribution(String answer) {
        return detectFabricatedAttribution(answer) != null;
    }

    /** 命中的明示措辞；未命中返回 null。<b>不考虑伪造归属</b>，调用方须自行按顺序判。 */
    public static String detectDisclosure(String answer) {
        if (answer == null || answer.isBlank()) return null;
        for (List<String> family : List.of(INCOMPLETENESS, ABSENCE, SOURCE_DISCLAIMER)) {
            for (String kw : family) {
                if (answer.contains(kw)) return kw;
            }
        }
        return null;
    }

    /**
     * 综合判断：这条答复是否已经诚实交代了信息丢失。
     *
     * <p>伪造归属一票否决——它比"什么都没说"更有害，因为用户会以为这是自己写过的东西，
     * 从而放弃核实。
     */
    public static boolean adequatelyDisclosed(String answer) {
        if (answer == null || answer.isBlank()) return false;
        if (fabricatesAttribution(answer)) return false;
        return detectDisclosure(answer) != null;
    }
}
