package org.zhzssp.memorandum.feature.codex.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Codex 运行时指标（进程内累计，重启归零）。
 *
 * <p>遵循项目既有约束：<strong>没有消费方的指标等于没有指标</strong>。
 * 这里每个计数器都有明确的判读用途：</p>
 * <ul>
 *   <li>{@code searchDegraded} —— 检索降级次数。持续 &gt; 0 说明 embedding 或
 *       FULLTEXT 有一路长期不可用，而用户只会感觉「搜得不太准」；</li>
 *   <li>{@code truncationWarned} —— 触发过截断告警的次数，用来验证 P0a 修复是否真的在发声；</li>
 *   <li>{@code notFoundAnswered} —— 检索无命中的次数。它与「知识体系是否在长」直接相关，
 *       是后续 Gap 闭环（P3）的立项依据。</li>
 * </ul>
 */
@Component
public class CodexMetrics {

    private final AtomicLong searchCount = new AtomicLong();
    private final AtomicLong searchDegraded = new AtomicLong();
    private final AtomicLong searchHits = new AtomicLong();
    private final AtomicLong notFoundAnswered = new AtomicLong();
    private final AtomicLong docReads = new AtomicLong();
    private final AtomicLong indexRuns = new AtomicLong();
    private final AtomicLong indexFailures = new AtomicLong();
    private final AtomicLong truncationWarned = new AtomicLong();
    private final AtomicLong embedCalls = new AtomicLong();
    private final AtomicLong docsSkipped = new AtomicLong();
    private final AtomicLong docsReindexed = new AtomicLong();

    // ---- P2 沉淀与 CI ----
    private final AtomicLong sedimentAttempts = new AtomicLong();
    private final AtomicLong sedimentSuccess = new AtomicLong();
    private final AtomicLong exampleGateRejections = new AtomicLong();
    private final AtomicLong backrefsInserted = new AtomicLong();
    private final AtomicLong ciRuns = new AtomicLong();
    private final AtomicLong ciErrorsFound = new AtomicLong();
    private final AtomicLong ciWarnsFound = new AtomicLong();

    public void recordSedimentAttempt() {
        sedimentAttempts.incrementAndGet();
    }

    public void recordSedimentSuccess() {
        sedimentSuccess.incrementAndGet();
    }

    /**
     * 示例入库门禁触发一次拒绝。
     *
     * <p>这个计数器是「执行层强制是否必要」的实证。若长期为 0，说明模型本来就会照做，
     * 门禁属冗余保险；一旦不为 0，就直接证明了<strong>只靠 prompt 提醒是不够的</strong>
     * ——与方案 D 的 bannedToolCallsBlocked 同一判读方式。</p>
     */
    public void recordExampleGateRejection() {
        exampleGateRejections.incrementAndGet();
    }

    public void recordBackrefInserted() {
        backrefsInserted.incrementAndGet();
    }

    public void recordCiRun(int errors, int warns) {
        ciRuns.incrementAndGet();
        ciErrorsFound.addAndGet(Math.max(0, errors));
        ciWarnsFound.addAndGet(Math.max(0, warns));
    }

    // ---- P3 缺口闭环 ----
    private final AtomicLong gapCreated = new AtomicLong();
    private final AtomicLong gapTouched = new AtomicLong();
    private final AtomicLong gapPlanned = new AtomicLong();
    private final AtomicLong gapClosed = new AtomicLong();
    private final AtomicLong gapDismissed = new AtomicLong();
    private final Map<String, AtomicLong> gapBySource = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 记录一次缺口登记。
     *
     * <p>按来源分开计数是必要的：三个信号源的质量差别很大。
     * 若 {@code CRAG} 占了 95%，说明多半是检索质量问题而非真实盲区，
     * 该去调检索而不是排学习计划——这个判断只有分来源统计才能做出来。</p>
     */
    public void recordGapTouched(String source, boolean created) {
        gapTouched.incrementAndGet();
        if (created) {
            gapCreated.incrementAndGet();
            gapBySource.computeIfAbsent(source, k -> new AtomicLong()).incrementAndGet();
        }
    }

    public void recordGapPlanned() {
        gapPlanned.incrementAndGet();
    }

    public void recordGapClosed() {
        gapClosed.incrementAndGet();
    }

    public void recordGapDismissed() {
        gapDismissed.incrementAndGet();
    }

    // ---- P4 蒸馏与出题 ----
    private final AtomicLong distillAttempts = new AtomicLong();
    private final AtomicLong distillWritten = new AtomicLong();
    private final AtomicLong distillSkipTerms = new AtomicLong();
    private final Map<String, AtomicLong> distillRejects = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong examAttempts = new AtomicLong();
    private final AtomicLong examDrafted = new AtomicLong();
    private final AtomicLong examDiscardedBadPath = new AtomicLong();
    private final AtomicLong examDiscardedNoCommand = new AtomicLong();

    public void recordDistillAttempt() {
        distillAttempts.incrementAndGet();
    }

    /**
     * 蒸馏被拒一次。
     *
     * <p>按原因码分开计数的用处很具体：若 {@code SOURCE_LIKELY_SCANNED} 占多数，
     * 该做的是提示用户先 OCR，而不是调 prompt；若 {@code SKIP_UNPARSEABLE} 占多数，
     * 说明 prompt 里关于止损线的要求写得不够硬。两种结论对应完全不同的下一步动作，
     * 只有分原因统计才能区分。</p>
     */
    public void recordDistillRejected(String code) {
        distillRejects.computeIfAbsent(code == null ? "UNKNOWN" : code,
                k -> new AtomicLong()).incrementAndGet();
    }

    public void recordDistillWritten(int skipTerms) {
        distillWritten.incrementAndGet();
        distillSkipTerms.addAndGet(Math.max(0, skipTerms));
    }

    public void recordExamAttempt() {
        examAttempts.incrementAndGet();
    }

    public void recordExamDrafted(int count) {
        examDrafted.addAndGet(Math.max(0, count));
    }

    /**
     * 一道机器出的题因为引用了不存在的路径而被丢弃。
     *
     * <p>这个计数器守的是本产品最核心的那个指标：checkpoint 通过率。
     * 一道验收命令指向不存在脚本的题，跑起来必然失败，而失败原因是「文件没有」
     * 而非「知识没掌握」——它会把「无法造假的通过率」污染成一个没有意义的数字。
     * 所以这类题是<strong>丢弃</strong>而非降级，且必须被计数：
     * 若这个数长期远高于 {@code examDrafted}，说明出题这件事在当前语料上还不成立。</p>
     */
    public void recordExamDiscardedBadPath() {
        examDiscardedBadPath.incrementAndGet();
    }

    public void recordExamDiscardedNoCommand() {
        examDiscardedNoCommand.incrementAndGet();
    }

    public void recordSearch(int hitCount, boolean degraded) {
        searchCount.incrementAndGet();
        searchHits.addAndGet(Math.max(0, hitCount));
        if (degraded) searchDegraded.incrementAndGet();
        if (hitCount == 0) notFoundAnswered.incrementAndGet();
    }

    public void recordDocRead() {
        docReads.incrementAndGet();
    }

    public void recordIndexRun(boolean success, int reindexed, int skipped,
                               int embedCallCount, int truncated) {
        indexRuns.incrementAndGet();
        if (!success) indexFailures.incrementAndGet();
        docsReindexed.addAndGet(Math.max(0, reindexed));
        docsSkipped.addAndGet(Math.max(0, skipped));
        embedCalls.addAndGet(Math.max(0, embedCallCount));
        truncationWarned.addAndGet(Math.max(0, truncated));
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long searches = searchCount.get();
        m.put("searchCount", searches);
        m.put("searchHitsTotal", searchHits.get());
        m.put("avgHitsPerSearch", searches == 0 ? 0.0
                : round3((double) searchHits.get() / searches));
        m.put("searchDegraded", searchDegraded.get());
        m.put("notFoundAnswered", notFoundAnswered.get());
        // 无命中率：随知识体系变完整应当下降，是「体系在长」的量化信号
        m.put("notFoundRate", searches == 0 ? 0.0
                : round3((double) notFoundAnswered.get() / searches));
        m.put("docReads", docReads.get());

        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("runs", indexRuns.get());
        idx.put("failures", indexFailures.get());
        idx.put("docsReindexed", docsReindexed.get());
        idx.put("docsSkipped", docsSkipped.get());
        long touched = docsReindexed.get() + docsSkipped.get();
        // 增量命中率：长期为 0 就该怀疑 blobHash 计算（如行尾转换导致每次都变）
        idx.put("skipRate", touched == 0 ? 0.0 : round3((double) docsSkipped.get() / touched));
        idx.put("embedCalls", embedCalls.get());
        idx.put("truncatedDocs", truncationWarned.get());
        m.put("index", idx);

        Map<String, Object> sed = new LinkedHashMap<>();
        long attempts = sedimentAttempts.get();
        sed.put("attempts", attempts);
        sed.put("succeeded", sedimentSuccess.get());
        sed.put("backrefsInserted", backrefsInserted.get());
        // 门禁拒绝率：证明「示例必须入库」不是靠自觉守住的
        sed.put("exampleGateRejections", exampleGateRejections.get());
        sed.put("exampleGateRejectRate", attempts == 0 ? 0.0
                : round3((double) exampleGateRejections.get() / attempts));
        m.put("sediment", sed);

        Map<String, Object> ci = new LinkedHashMap<>();
        ci.put("runs", ciRuns.get());
        ci.put("errorsFound", ciErrorsFound.get());
        ci.put("warnsFound", ciWarnsFound.get());
        m.put("ci", ci);

        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("created", gapCreated.get());
        gap.put("touched", gapTouched.get());
        gap.put("planned", gapPlanned.get());
        gap.put("closed", gapClosed.get());
        gap.put("dismissed", gapDismissed.get());
        Map<String, Object> bySrc = new LinkedHashMap<>();
        gapBySource.forEach((k, v) -> bySrc.put(k, v.get()));
        gap.put("createdBySource", bySrc);
        // 复发率：同一缺口被反复问到说明它确实挡路（touched 远大于 created 才有意义）
        long created = gapCreated.get();
        gap.put("avgAsksPerGap", created == 0 ? 0.0
                : round3((double) gapTouched.get() / created));
        m.put("gap", gap);

        Map<String, Object> dis = new LinkedHashMap<>();
        long dAtt = distillAttempts.get();
        dis.put("attempts", dAtt);
        dis.put("written", distillWritten.get());
        dis.put("skipTermsHarvested", distillSkipTerms.get());
        Map<String, Object> rej = new LinkedHashMap<>();
        distillRejects.forEach((k, v) -> rej.put(k, v.get()));
        dis.put("rejectsByReason", rej);
        m.put("distill", dis);

        Map<String, Object> exam = new LinkedHashMap<>();
        exam.put("attempts", examAttempts.get());
        exam.put("drafted", examDrafted.get());
        exam.put("discardedBadPath", examDiscardedBadPath.get());
        exam.put("discardedNoCommand", examDiscardedNoCommand.get());
        long produced = examDrafted.get();
        long discarded = examDiscardedBadPath.get() + examDiscardedNoCommand.get();
        // 丢弃率：偏高说明出题在当前语料上还不成立，该停用而非继续调 prompt
        exam.put("discardRate", (produced + discarded) == 0 ? 0.0
                : round3((double) discarded / (produced + discarded)));
        m.put("exam", exam);
        return m;
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
