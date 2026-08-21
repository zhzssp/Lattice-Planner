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
        return m;
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
