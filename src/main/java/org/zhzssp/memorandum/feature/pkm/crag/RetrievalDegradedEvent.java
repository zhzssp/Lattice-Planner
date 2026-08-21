package org.zhzssp.memorandum.feature.pkm.crag;

/**
 * 「一次检索没能给出可用结果」这个<strong>事实</strong>。
 *
 * <h3>为什么定义在 pkm 而不是 codex</h3>
 * <p>本事件描述的是检索层发生的事实，不含任何「该拿它做什么」的判断——
 * 消费者可以拿它记知识缺口（P3 的做法），也可以拿它做检索质量监控。
 * 定义在 pkm 使依赖方向保持为 {@code codex → pkm}，与既有结构一致；
 * 若把它放进 codex，就会出现 {@code pkm/agent → codex} 的反向耦合。</p>
 *
 * <h3>为什么不在 CorrectiveRetriever 内部直接发</h3>
 * <p>那里没有「这次检索是谁为了什么发起的」这层信息，而这层信息是决定性的：</p>
 * <ul>
 *   <li>后台批量检索、索引自检、<strong>评测套件跑的 47 个用例</strong>都会经过
 *       {@code CorrectiveRetriever}。若在那里发事件，跑一次 {@code agentEval}
 *       就会往缺口台账灌进几十条来自测试用例的假缺口。</li>
 *   <li>只有「用户在对话里真的问了，而库里答不上来」才构成知识缺口。
 *       这个语义只有工具调用层知道。</li>
 * </ul>
 * <p>所以事件由工具层（{@code kb.semantic_search} / {@code doc.search}）发布。</p>
 *
 * @param userId    提问用户
 * @param query     原始查询
 * @param grade     检索质量评级（CORRECT / AMBIGUOUS / INCORRECT）
 * @param degraded  是否最终降级为「基于通用知识回答」
 * @param hitCount  命中条数
 * @param channel   检索通路：{@code NOTE}（随手笔记）或 {@code GIT_DOC}（知识仓库）
 * @param repoId    知识仓库 id；笔记通路为 null
 */
public record RetrievalDegradedEvent(Long userId,
                                     String query,
                                     String grade,
                                     boolean degraded,
                                     int hitCount,
                                     String channel,
                                     Long repoId) {

    public static final String CHANNEL_NOTE = "NOTE";
    public static final String CHANNEL_GIT_DOC = "GIT_DOC";

    /**
     * 是否构成知识缺口信号。
     *
     * <p>判据刻意比 {@code degraded} 宽一点点：命中数为 0 时即便 CRAG 关闭
     * （此时 {@code degraded} 恒为 false）也应算缺口——「一条都没搜到」
     * 是比任何评级都直接的证据。</p>
     */
    public boolean signalsGap() {
        return degraded || hitCount == 0 || "INCORRECT".equals(grade);
    }
}
