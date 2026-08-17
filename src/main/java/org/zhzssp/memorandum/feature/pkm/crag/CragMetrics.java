package org.zhzssp.memorandum.feature.pkm.crag;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CRAG 可观测指标。
 *
 * <p>用于验证计划中的验收标准：
 * <ul>
 *   <li>grade 分布（CORRECT/AMBIGUOUS/INCORRECT）——阈值 upper/lower 是否需调参；</li>
 *   <li>改写重检索触发次数 + 重检索次数严格 ≤ 1（rewriteTriggered ≤ retrieveCount）；</li>
 *   <li>degraded 次数——多少比例的提问最终走了"基于通用知识"降级；</li>
 *   <li>bypass 次数——CRAG 关闭时走普通检索的次数（验证开关生效）。</li>
 * </ul>
 */
@Component
public class CragMetrics {

    private final AtomicLong retrieveCount = new AtomicLong(0);
    private final AtomicLong bypassCount = new AtomicLong(0);
    private final AtomicLong gradeCorrect = new AtomicLong(0);
    private final AtomicLong gradeAmbiguous = new AtomicLong(0);
    private final AtomicLong gradeIncorrect = new AtomicLong(0);
    private final AtomicLong rewriteTriggered = new AtomicLong(0);
    private final AtomicLong degradedCount = new AtomicLong(0);

    /** 一次 CRAG 检索入口调用。 */
    public void recordRetrieve() { retrieveCount.incrementAndGet(); }

    /** CRAG 被关闭（pkm.crag.enabled=false），本次走普通检索。 */
    public void recordBypass() { bypassCount.incrementAndGet(); }

    /** 记录最终判级（首检索 CORRECT 或重检索后的 g2）。 */
    public void recordGrade(RetrievalEvaluator.Grade grade) {
        if (grade == null) return;
        switch (grade) {
            case CORRECT -> gradeCorrect.incrementAndGet();
            case AMBIGUOUS -> gradeAmbiguous.incrementAndGet();
            case INCORRECT -> gradeIncorrect.incrementAndGet();
        }
    }

    /** 触发了一次改写重检索（每次 retrieve 最多 +1，用于验证上限）。 */
    public void recordRewrite() { rewriteTriggered.incrementAndGet(); }

    /** 最终标记 degraded=true。 */
    public void recordDegraded() { degradedCount.incrementAndGet(); }

    public long retrieves() { return retrieveCount.get(); }
    public long bypasses() { return bypassCount.get(); }
    public long rewrites() { return rewriteTriggered.get(); }
    public long degradeds() { return degradedCount.get(); }

    /** degraded 占比（0-1）。 */
    public double degradedRate() {
        long total = retrieveCount.get();
        return total == 0 ? 0.0 : (double) degradedCount.get() / total;
    }

    /** 改写触发率（0-1）：反映有多少比例的查询首检索就不达标。 */
    public double rewriteRate() {
        long total = retrieveCount.get();
        return total == 0 ? 0.0 : (double) rewriteTriggered.get() / total;
    }

    /** 结构化快照，供 stats 端点输出。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("retrieveCount", retrieveCount.get());
        m.put("bypassCount", bypassCount.get());
        Map<String, Object> grades = new LinkedHashMap<>();
        grades.put("CORRECT", gradeCorrect.get());
        grades.put("AMBIGUOUS", gradeAmbiguous.get());
        grades.put("INCORRECT", gradeIncorrect.get());
        m.put("gradeDistribution", grades);
        m.put("rewriteTriggered", rewriteTriggered.get());
        m.put("rewriteRate", round4(rewriteRate()));
        m.put("degradedCount", degradedCount.get());
        m.put("degradedRate", round4(degradedRate()));
        // 验收标准：重检索次数严格 ≤ 1 → rewriteTriggered 不应超过 retrieveCount
        m.put("rewriteWithinLimit", rewriteTriggered.get() <= retrieveCount.get());
        return m;
    }

    @Override
    public String toString() {
        return String.format(
                "retrieves=%d bypass=%d correct=%d ambiguous=%d incorrect=%d rewrite=%d degraded=%d",
                retrieveCount.get(), bypassCount.get(), gradeCorrect.get(),
                gradeAmbiguous.get(), gradeIncorrect.get(),
                rewriteTriggered.get(), degradedCount.get());
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
