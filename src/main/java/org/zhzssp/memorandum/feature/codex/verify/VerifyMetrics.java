package org.zhzssp.memorandum.feature.codex.verify;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 验证闭环指标（进程内累计，重启归零）。
 *
 * <p>遵循项目既有约束「没有消费方的指标等于没有指标」——下面每一项都有明确判读用途：</p>
 *
 * <table>
 *   <tr><th>指标</th><th>回答什么问题</th></tr>
 *   <tr><td>{@code blockedNoPrediction}</td>
 *       <td><strong>预测门禁是否在起作用。</strong>长期为 0 有两种可能：
 *           用户很守纪律，或门禁没接上——需结合 {@code predictionsSubmitted} 判断</td></tr>
 *   <tr><td>{@code rejectedUnsafe}</td>
 *       <td><strong>命令白名单是否必要。</strong>长期为 0 说明它是冗余保险；
 *           一旦不为 0 就是「白名单必要」的实证（与方案 D 的
 *           {@code bannedToolCallsBlocked} 同一立场）</td></tr>
 *   <tr><td>{@code predictionAccuracy}</td>
 *       <td><strong>别处拿不到的指标。</strong>预测先于结果冻结，无法事后造假</td></tr>
 *   <tr><td>{@code mispredicted}</td>
 *       <td>「结果对但因果理解错」的次数——最该被复盘的一类</td></tr>
 * </table>
 */
@Component
public class VerifyMetrics {

    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong passed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong rejectedUnsafe = new AtomicLong();
    private final AtomicLong blockedNoPrediction = new AtomicLong();
    private final AtomicLong predictionsSubmitted = new AtomicLong();
    private final AtomicLong predictionJudged = new AtomicLong();
    private final AtomicLong predictionCorrect = new AtomicLong();
    private final Map<String, AtomicLong> passedByLevel = new ConcurrentHashMap<>();

    public void recordRun(boolean ok, boolean timeout, String level) {
        runs.incrementAndGet();
        if (ok) {
            passed.incrementAndGet();
            passedByLevel.computeIfAbsent(level, k -> new AtomicLong()).incrementAndGet();
        } else {
            failed.incrementAndGet();
        }
        if (timeout) timedOut.incrementAndGet();
    }

    public void recordRejected() {
        rejectedUnsafe.incrementAndGet();
    }

    public void recordBlockedNoPrediction() {
        blockedNoPrediction.incrementAndGet();
    }

    public void recordPredictionSubmitted() {
        predictionsSubmitted.incrementAndGet();
    }

    public void recordPredictionJudged(boolean correct) {
        predictionJudged.incrementAndGet();
        if (correct) predictionCorrect.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long r = runs.get();
        m.put("runs", r);
        m.put("passed", passed.get());
        m.put("failed", failed.get());
        m.put("passRate", r == 0 ? 0.0 : round3((double) passed.get() / r));
        m.put("timedOut", timedOut.get());

        Map<String, Long> lv = new LinkedHashMap<>();
        passedByLevel.forEach((k, v) -> lv.put(k, v.get()));
        m.put("passedByLevel", lv);

        m.put("predictionsSubmitted", predictionsSubmitted.get());
        // 门禁生效证据
        m.put("blockedNoPrediction", blockedNoPrediction.get());
        // 白名单必要性证据
        m.put("rejectedUnsafeCommands", rejectedUnsafe.get());

        long judged = predictionJudged.get();
        m.put("predictionJudged", judged);
        m.put("predictionCorrect", predictionCorrect.get());
        m.put("predictionAccuracy", judged == 0 ? 0.0
                : round3((double) predictionCorrect.get() / judged));
        m.put("mispredicted", judged - predictionCorrect.get());
        return m;
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
