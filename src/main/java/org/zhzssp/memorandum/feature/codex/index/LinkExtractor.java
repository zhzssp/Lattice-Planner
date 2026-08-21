package org.zhzssp.memorandum.feature.codex.index;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.entity.KbLink.LinkKind;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Markdown 正文抽取链接。
 *
 * <h3>为什么值得单独做这件事</h3>
 * <p>目标仓库实测有 <strong>1263 条</strong>相对链接，其中 <strong>377 条带 anchor</strong>。
 * 这类链接的失效特性很恶劣：</p>
 * <ul>
 *   <li>IDE 不报错、编译不报错、渲染不报错——只有点击时才 404；</li>
 *   <li>改一个标题就会让所有指向它的 anchor 链接静默失效；</li>
 *   <li>数量到千级后，人工核对不可行。</li>
 * </ul>
 * <p>更重要的是 {@link LinkKind#BACKREF}：它承载「笔记必须挂回知识文档」这条方法论约束。
 * 原本这一步靠 Agent 自觉，漏挂了没人知道，笔记就成了检索不到的孤岛。
 * 抽出来落库之后，双向性才能被机器校验。</p>
 */
@Component
public class LinkExtractor {

    /** Markdown 行内链接 {@code [text](target)}。target 不含空格与右括号。 */
    private static final Pattern MD_LINK =
            Pattern.compile("\\[([^\\]]*)\\]\\(\\s*([^)\\s]+)(?:\\s+\"[^\"]*\")?\\s*\\)");

    /** {@code [[标题]]} 双链写法（与既有 PKM 笔记一致）。 */
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\[\\]]+)]]");

    /** 「速记」回挂行：{@code > **速记**：[notes/x.md](../notes/x.md) —— 摘要}。 */
    private static final Pattern BACKREF_LINE = Pattern.compile("速记");

    /** 代码块围栏。 */
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

    /**
     * 抽出的一条链接。
     *
     * @param rawTarget 原始 target 串
     * @param path      相对路径部分（可能为空 = 同文档内 anchor 跳转）
     * @param anchor    anchor 部分（可能为 null）
     * @param kind      链接类别
     * @param lineNo    行号（1-based），报错时用于定位
     */
    public record Extracted(String rawTarget, String path, String anchor,
                            LinkKind kind, int lineNo) {}

    /**
     * 抽取全部链接。
     *
     * @param content   完整内容
     * @param bodyStart front-matter 之后的偏移
     */
    public List<Extracted> extract(String content, int bodyStart) {
        List<Extracted> out = new ArrayList<>();
        if (content == null || content.isEmpty()) return out;

        int start = Math.max(0, Math.min(bodyStart, content.length()));
        String[] lines = content.substring(start).split("\\R", -1);

        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) continue;

            // 代码块里的链接是示例文本，不是真引用——校验它们只会产生噪音
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;

            boolean isBackrefLine = BACKREF_LINE.matcher(line).find();
            int lineNo = i + 1;

            Matcher m = MD_LINK.matcher(line);
            while (m.find()) {
                String target = m.group(2);
                if (target == null || target.isBlank()) continue;
                Extracted e = classify(target, isBackrefLine, lineNo);
                if (e != null) out.add(e);
            }

            Matcher w = WIKI_LINK.matcher(line);
            while (w.find()) {
                String title = w.group(1).strip();
                if (title.isEmpty()) continue;
                out.add(new Extracted("[[" + title + "]]", title, null, LinkKind.WIKI, lineNo));
            }
        }
        return out;
    }

    private Extracted classify(String target, boolean backrefLine, int lineNo) {
        String t = target.strip();

        // 外部链接：记录但不校验可达性（网络校验会让索引变慢且不稳定）
        if (t.startsWith("http://") || t.startsWith("https://")
                || t.startsWith("mailto:") || t.startsWith("//")) {
            return new Extracted(t, null, null, LinkKind.EXTERNAL, lineNo);
        }
        // 纯 anchor：同文档内跳转
        if (t.startsWith("#")) {
            return new Extracted(t, null, decodeAnchor(t.substring(1)), LinkKind.REF, lineNo);
        }

        String path = t;
        String anchor = null;
        int hash = t.indexOf('#');
        if (hash >= 0) {
            path = t.substring(0, hash);
            anchor = decodeAnchor(t.substring(hash + 1));
        }
        // 去掉 URL 编码与查询串
        path = decodePath(path);
        if (path.isBlank()) {
            return new Extracted(t, null, anchor, LinkKind.REF, lineNo);
        }

        LinkKind kind = backrefLine ? LinkKind.BACKREF : kindOfPath(path);
        return new Extracted(t, path, anchor, kind, lineNo);
    }

    private LinkKind kindOfPath(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".pdf")) return LinkKind.CITATION;
        if (lower.contains("/notes/") || lower.startsWith("notes/")) return LinkKind.BACKREF;
        if (lower.endsWith(".sh") || lower.endsWith(".py")
                || lower.contains("/scripts/") || lower.startsWith("scripts/")) {
            return LinkKind.LAB;
        }
        return LinkKind.REF;
    }

    /** anchor 解 URL 编码；GitHub 对 CJK anchor 会做 percent-encoding。 */
    private String decodeAnchor(String s) {
        if (s == null || s.isBlank()) return null;
        return urlDecode(s.strip()).toLowerCase();
    }

    private String decodePath(String s) {
        String p = s.strip();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return urlDecode(p);
    }

    private String urlDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
