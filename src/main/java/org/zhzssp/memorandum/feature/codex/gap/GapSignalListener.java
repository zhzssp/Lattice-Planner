package org.zhzssp.memorandum.feature.codex.gap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.entity.KbGap;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointJudgedEvent;
import org.zhzssp.memorandum.feature.pkm.crag.RetrievalDegradedEvent;

/**
 * 三个信号源汇入缺口台账。
 *
 * <h3>为什么全部走监听而不是散落在业务代码里调用</h3>
 * <p>三个信号的产生点分属三个模块（pkm 检索、codex 索引、codex 验证）。
 * 若在每处直接调 {@link GapService}，缺口闭环就变成了三处零散的副作用，
 * 关掉开关时还得指望三处都记得判断。集中在一个监听器里，
 * 「关掉缺口闭环」等于「这个类不做事」，行为边界一目了然。</p>
 *
 * <h3>监听器绝不抛异常</h3>
 * <p>缺口记录是<strong>附加价值</strong>，不是主流程。让它的失败冒泡上去
 * 会导致「检索能用但一提问就报错」这种荒谬情况——
 * 用户失去的是核心功能，换来的是一条本来可以不记的台账。</p>
 */
@Component
public class GapSignalListener {

    private static final Logger log = LoggerFactory.getLogger(GapSignalListener.class);

    private final GapService gapService;
    private final ScopeRecallService scopeRecall;

    public GapSignalListener(GapService gapService, ScopeRecallService scopeRecall) {
        this.gapService = gapService;
        this.scopeRecall = scopeRecall;
    }

    /* ==================== 源一：检索没给出可用结果 ==================== */

    /**
     * CRAG / Git 检索降级 → 缺口。
     *
     * <p>这是三个源里唯一「免费」的：信号本来就在产生，此前只用于让 Agent
     * 调整措辞（「以下基于通用知识」），用完即丢。攒起来就是一份
     * <strong>真实的、按频次排序的个人盲区清单</strong>——
     * 而人是无法凭回忆列出自己盲区的。</p>
     *
     * <p>顺带在同一处做止损线召回：用户提问的文本正是检测「跳过的概念被遇到」
     * 所需的输入，没必要为它再加一个埋点。</p>
     */
    @EventListener
    public void onRetrievalDegraded(RetrievalDegradedEvent e) {
        if (!gapService.enabled() || e.userId() == null) return;
        try {
            // 召回检测对所有提问都做（不限于降级的那些）：
            // 「跳过的概念被问到」与「检索是否命中」是两件独立的事——
            // 完全可能库里有资料、检索也命中了，但这个概念当初被标记为先跳过。
            scopeRecall.observeQuestion(e.userId(), e.repoId(), e.query());

            if (e.signalsGap()) {
                gapService.upsert(e.userId(), e.repoId(), KbGap.Source.CRAG, e.query(), null,
                        "检索通路 " + e.channel() + "，评级 " + e.grade()
                                + "，命中 " + e.hitCount() + " 条");
            }
        } catch (Exception ex) {
            log.warn("[Codex Gap] 处理检索降级信号失败（不影响检索本身）：{}", ex.toString());
        }
    }

    /* ==================== 源三：检验失败 / 预测错 ==================== */

    /**
     * 检验判定 → 缺口。
     *
     * <p>两类分开登记，因为补法完全不同：</p>
     * <ul>
     *   <li>{@code CP_FAIL}：做不出来 → 要补动手；</li>
     *   <li>{@code CP_MISPREDICT}：做出来了但原因想错了 → 要补因果理解。</li>
     * </ul>
     * <p>合成一类会让后续生成的学习计划千篇一律，而这两件事的学习路径几乎相反。</p>
     */
    @EventListener
    public void onCheckpointJudged(CheckpointJudgedEvent e) {
        if (!gapService.enabled() || e.userId() == null) return;
        try {
            if (e.failed()) {
                gapService.upsert(e.userId(), e.repoId(), KbGap.Source.CP_FAIL,
                        e.code() + " 未通过：" + safe(e.title()), null,
                        "落地检验执行失败，说明这一块只是看懂了、还做不出来");
            }
            if (e.mispredicted()) {
                gapService.upsert(e.userId(), e.repoId(), KbGap.Source.CP_MISPREDICT,
                        e.code() + " 通过但预测错：" + safe(e.title()), null,
                        e.divergence() == null || e.divergence().isBlank()
                                ? "结果正确但预测与实际不一致，因果理解有偏差"
                                : "预测偏差：" + trim(e.divergence(), 800));
            }
        } catch (Exception ex) {
            log.warn("[Codex Gap] 处理检验判定信号失败（不影响验证闭环）：{}", ex.toString());
        }
    }

    /* ==================== 源二的数据来源：索引后同步止损线 ==================== */

    /**
     * 索引完成后重新解析「先跳过」清单。
     *
     * <p>挂在索引之后是刻意的：清单写在 guide 的正文里，
     * 文档变了清单就可能变（用户可能把某项从「先跳过」挪到「必学」）。
     * 与索引同频保证两者永远一致。</p>
     */
    @EventListener
    public void onRepoIndexed(RepoSyncService.RepoIndexedEvent e) {
        if (!gapService.enabled()) return;
        try {
            ScopeRecallService.SyncResult r = scopeRecall.syncFromRepo(e.repo());
            if (r.termsFound() == 0) {
                // 一条都没解析出来通常意味着仓库没有「先跳过」小节，
                // 此时 skip 召回是静默失效的——必须说出来，否则用户会以为它在工作
                log.info("[Codex Gap] 仓库「{}」未解析到任何「先跳过」条目，"
                                + "止损线召回对该仓库不会触发。若仓库确有跳过清单，"
                                + "检查其小节标题是否含「先跳过 / 可推迟」等措辞。",
                        e.repo().getName());
            }
        } catch (Exception ex) {
            log.warn("[Codex Gap] 止损线同步失败（不影响索引结果）：{}", ex.toString());
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s, int max) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
