package org.zhzssp.memorandum.feature.agent.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Agent 前缀缓存（Prefix Caching，P2）。
 *
 * <p>缓存 system prompt 的构造结果（content + hash），key 由 mode、工具集 hash、
 * 长期记忆 hash、当天日期 bucket 组成。同一 turn 内多步 ReAct 循环共享前缀，
 * 跨 turn（同 mode + 同工具集 + 同 memo + 同日）也可命中。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>前缀字节稳定化：key 含 toolsetHash/memoHash/dateBucket，确保同输入同输出；</li>
 *   <li>不缓存可变部分（history），只缓存 system 前缀；</li>
 *   <li>兜底过期（expireAfterWrite），防止长期驻留。</li>
 * </ul>
 */
@Component
public class PrefixCache {

    private static final Logger log = LoggerFactory.getLogger(PrefixCache.class);

    /** 缓存 key：四元组确定一次前缀 */
    public record PrefixKey(String mode, String toolsetHash, String memoHash, String dateBucket) {
        public PrefixKey {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(dateBucket, "dateBucket");
        }
    }

    /** 缓存值：前缀内容 + 字节 hash */
    public record CachedPrefix(String content, String prefixHash) {}

    private final Cache<PrefixKey, CachedPrefix> cache;

    public PrefixCache(@Value("${agent.prefix-cache.enabled:true}") boolean enabled,
                       @Value("${agent.prefix-cache.max-entries:256}") int maxEntries,
                       @Value("${agent.prefix-cache.expire-minutes:120}") int expireMinutes) {
        if (enabled) {
            this.cache = Caffeine.newBuilder()
                    .maximumSize(Math.max(1, maxEntries))
                    .expireAfterWrite(Duration.ofMinutes(Math.max(1, expireMinutes)))
                    .recordStats()
                    .build();
            log.info("[PrefixCache] 启用，maxEntries={}, expireMinutes={}", maxEntries, expireMinutes);
        } else {
            this.cache = null;
            log.info("[PrefixCache] 已禁用");
        }
    }

    /** 是否启用。禁用时 get 返回 null（由调用方自行构造）。 */
    public boolean enabled() {
        return cache != null;
    }

    /**
     * 取缓存（启用时返回 Caffeine 语义：命中返回已有值，未命中用 loader 构造并回填）。
     * 禁用时直接返回 loader 结果且不缓存。
     */
    public CachedPrefix getOrCompute(PrefixKey key, java.util.function.Supplier<CachedPrefix> loader) {
        if (!enabled()) return loader.get();
        return cache.get(key, k -> loader.get());
    }

    /** 暴露统计信息。 */
    public String stats() {
        if (cache == null) return "disabled";
        return cache.stats().toString() + ", size=" + cache.estimatedSize();
    }
}
