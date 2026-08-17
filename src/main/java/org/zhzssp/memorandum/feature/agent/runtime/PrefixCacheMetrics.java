package org.zhzssp.memorandum.feature.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 前缀缓存可观测指标（Prefix Caching P5）。
 *
 * <p>覆盖计划中的四类指标：
 * <ul>
 *   <li>{@code prefixCacheHit / prefixCacheMiss}——应用侧前缀复用命中情况；</li>
 *   <li>{@code prefixBuildTimeMs}——实际发生的前缀构造耗时（仅 miss 时产生），
 *       配合 hit 数可估算 {@code prefixBuildTimeSavedMs}；</li>
 *   <li>{@code promptCacheHitTokens / promptCacheMissTokens}——上游服务端
 *       automatic prefix caching 的命中 token 数（DeepSeek {@code usage} 字段），
 *       用于验证"前缀字节稳定化"是否真的让服务端缓存命中；</li>
 *   <li>{@code chatCalls}——chat 调用总次数，作为上述比率的分母。</li>
 * </ul>
 *
 * <p>纯 AtomicLong 计数器，无外部依赖，可被任意组件安全注入。</p>
 */
@Component
public class PrefixCacheMetrics {

    /* ---- 应用侧前缀缓存 ---- */
    private final AtomicLong prefixCacheHit = new AtomicLong(0);
    private final AtomicLong prefixCacheMiss = new AtomicLong(0);
    private final AtomicLong prefixBuildCount = new AtomicLong(0);
    private final AtomicLong prefixBuildTimeMs = new AtomicLong(0);

    /* ---- 上游服务端 prompt cache（透传观测，P4）---- */
    private final AtomicLong chatCalls = new AtomicLong(0);
    private final AtomicLong promptCacheHitTokens = new AtomicLong(0);
    private final AtomicLong promptCacheMissTokens = new AtomicLong(0);
    private final AtomicLong usageReportedCalls = new AtomicLong(0);

    public void recordHit() { prefixCacheHit.incrementAndGet(); }

    public void recordMiss() { prefixCacheMiss.incrementAndGet(); }

    /** 记录一次真实的前缀构造（exportSchemas + JSON 序列化 + format）及其耗时。 */
    public void recordBuild(long elapsedMs) {
        prefixBuildCount.incrementAndGet();
        prefixBuildTimeMs.addAndGet(Math.max(0, elapsedMs));
    }

    /** 记录一次 chat 调用（无论上游是否返回 usage）。 */
    public void recordChatCall() { chatCalls.incrementAndGet(); }

    /**
     * 记录上游返回的 prompt cache token 统计。
     * DeepSeek 返回 {@code usage.prompt_cache_hit_tokens / prompt_cache_miss_tokens}；
     * 标准 OpenAI 返回 {@code usage.prompt_tokens_details.cached_tokens}。
     */
    public void recordPromptCacheTokens(long hitTokens, long missTokens) {
        if (hitTokens <= 0 && missTokens <= 0) return;
        promptCacheHitTokens.addAndGet(Math.max(0, hitTokens));
        promptCacheMissTokens.addAndGet(Math.max(0, missTokens));
        usageReportedCalls.incrementAndGet();
    }

    public long hits() { return prefixCacheHit.get(); }
    public long misses() { return prefixCacheMiss.get(); }
    public long builds() { return prefixBuildCount.get(); }
    public long buildTimeMs() { return prefixBuildTimeMs.get(); }
    public long cacheHitTokens() { return promptCacheHitTokens.get(); }
    public long cacheMissTokens() { return promptCacheMissTokens.get(); }

    /** 应用侧前缀缓存命中率（0-1）。 */
    public double hitRate() {
        long total = prefixCacheHit.get() + prefixCacheMiss.get();
        return total == 0 ? 0.0 : (double) prefixCacheHit.get() / total;
    }

    /** 单次构造平均耗时（ms）。 */
    public double avgBuildMs() {
        long n = prefixBuildCount.get();
        return n == 0 ? 0.0 : (double) prefixBuildTimeMs.get() / n;
    }

    /**
     * 估算省下的构造耗时：命中次数 × 平均单次构造耗时。
     * 对应计划里的 {@code prefixBuildTimeSavedMs}。
     */
    public long estimatedSavedMs() {
        return Math.round(prefixCacheHit.get() * avgBuildMs());
    }

    /** 上游 prompt cache 命中率（0-1），仅统计返回了 usage 的调用。 */
    public double promptCacheHitRate() {
        long total = promptCacheHitTokens.get() + promptCacheMissTokens.get();
        return total == 0 ? 0.0 : (double) promptCacheHitTokens.get() / total;
    }

    /** 结构化快照，供 stats 端点输出。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefixCacheHit", prefixCacheHit.get());
        m.put("prefixCacheMiss", prefixCacheMiss.get());
        m.put("prefixCacheHitRate", round4(hitRate()));
        m.put("prefixBuildCount", prefixBuildCount.get());
        m.put("prefixBuildTimeMs", prefixBuildTimeMs.get());
        m.put("avgBuildMs", round2(avgBuildMs()));
        m.put("estimatedBuildTimeSavedMs", estimatedSavedMs());

        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("chatCalls", chatCalls.get());
        upstream.put("usageReportedCalls", usageReportedCalls.get());
        upstream.put("promptCacheHitTokens", promptCacheHitTokens.get());
        upstream.put("promptCacheMissTokens", promptCacheMissTokens.get());
        upstream.put("promptCacheHitRate", round4(promptCacheHitRate()));
        m.put("upstreamPromptCache", upstream);
        return m;
    }

    @Override
    public String toString() {
        return String.format(
                "prefixHit=%d miss=%d rate=%.2f builds=%d avgBuildMs=%.1f savedMs=%d "
                        + "promptCacheHitTokens=%d missTokens=%d",
                prefixCacheHit.get(), prefixCacheMiss.get(), hitRate(),
                prefixBuildCount.get(), avgBuildMs(), estimatedSavedMs(),
                promptCacheHitTokens.get(), promptCacheMissTokens.get());
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
