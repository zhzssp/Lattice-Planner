package org.zhzssp.memorandum.feature.agent.tool.visibility;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 会话层工具屏蔽（方案 K，K4）。
 *
 * <p>支持「这一轮对话」临时 deny / pin 工具——用户可能想说「这次别动我的任务」，
 * 而不必切换整个思维模式。内存态，与会话生命周期对齐，不落库。</p>
 *
 * <p><strong>为什么内存态不落库</strong>：会话级屏蔽是「这次对话的临时意愿」，
 * 跨重启保留反而违背直觉（与方案 D「封禁不跨轮」同一思路——临时状态不应
 * 比它所服务的场景活得更久）。要持久化的偏好属于 MODE 层或 {@code UserPreference}。</p>
 */
@Component
public class SessionToolMask {

    private static final Logger log = LoggerFactory.getLogger(SessionToolMask.class);

    private final Cache<String, Mask> masks;

    public SessionToolMask(
            @Value("${agent.tool.visibility.session-mask-expire-minutes:30}") int expireMinutes) {
        this.masks = Caffeine.newBuilder()
                .maximumSize(1024)
                .expireAfterAccess(Duration.ofMinutes(Math.max(1, expireMinutes)))
                .build();
        log.info("[SessionToolMask] 启用，expireAfterAccess={}min", expireMinutes);
    }

    /** 会话规则（deny + pin）。 */
    public record Mask(Set<String> denyTools, Set<String> denyTags, Set<String> pinnedTools) {
        public boolean isEmpty() {
            return denyTools.isEmpty() && denyTags.isEmpty() && pinnedTools.isEmpty();
        }
    }

    /** 为会话追加 deny。 */
    public void deny(String sid, Set<String> tools, Set<String> tags) {
        Mask cur = masks.get(sid, k -> new Mask(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>()));
        cur.denyTools().addAll(tools == null ? Set.of() : tools);
        cur.denyTags().addAll(tags == null ? Set.of() : tags);
        masks.put(sid, cur);
        log.info("[SessionToolMask] sid={} deny tools={} tags={}", sid, cur.denyTools(), cur.denyTags());
    }

    /** 为会话追加 pin（破例）。 */
    public void pin(String sid, Set<String> tools) {
        Mask cur = masks.get(sid, k -> new Mask(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>()));
        cur.pinnedTools().addAll(tools == null ? Set.of() : tools);
        masks.put(sid, cur);
        log.info("[SessionToolMask] sid={} pin tools={}", sid, cur.pinnedTools());
    }

    /** 清空会话规则。 */
    public void clear(String sid) {
        masks.invalidate(sid);
    }

    /** 取会话层规则；无规则时返回空层。 */
    public Mask of(String sid) {
        if (sid == null) return new Mask(Set.of(), Set.of(), Set.of());
        Mask m = masks.getIfPresent(sid);
        return m == null ? new Mask(Set.of(), Set.of(), Set.of()) : m;
    }

    /** 当前活跃的会话掩码数（供指标观测）。 */
    public long size() {
        return masks.estimatedSize();
    }
}
