package org.zhzssp.memorandum.feature.agent.tool.visibility;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 一次可见性解析的 scope 链（方案 K）。
 *
 * <p>由近到远依次为：当前层（session/role）、模式层、全局层。
 * {@link #signature()} 对整条链的规则做摘要，作为视图缓存的 key。</p>
 */
public final class ScopeChain {

    /** 从近到远排列的层（不含 GLOBAL，GLOBAL 由 resolver 隐式打底）。 */
    private final List<ToolLayer> layers;

    public ScopeChain(List<ToolLayer> layers) {
        this.layers = layers == null ? List.of() : List.copyOf(layers);
    }

    public List<ToolLayer> layers() {
        return layers;
    }

    /** 是否含 ROLE 层（子代理场景）。 */
    public boolean hasRole() {
        return layers.stream().anyMatch(l -> l.kind() == ToolLayer.ScopeKind.ROLE);
    }

    /** 最靠近当前的层（首个），用于 SESSION 的 pin 等。 */
    public ToolLayer current() {
        return layers.isEmpty() ? null : layers.get(0);
    }

    /**
     * 链签名：所有层的 kind + label + 规则集合的稳定序列化后做 SHA-256。
     * 用于视图缓存命中判定——规则不变则签名不变，视图可复用。
     */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        for (ToolLayer l : layers) {
            sb.append(l.kind()).append('|').append(l.label()).append('|');
            sb.append(sorted(l.allowTags())).append('|')
              .append(sorted(l.allowTools())).append('|')
              .append(sorted(l.denyTags())).append('|')
              .append(sorted(l.denyTools())).append('|')
              .append(sorted(l.pinnedTools())).append(';');
        }
        return sha256(sb.toString());
    }

    private static String sorted(java.util.Set<String> s) {
        List<String> l = new ArrayList<>(s);
        java.util.Collections.sort(l);
        return String.join(",", l);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
