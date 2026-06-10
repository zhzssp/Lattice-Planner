package org.zhzssp.memorandum.feature.pkm.service;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 渲染器。
 *
 * 1) 启用 GFM 表格 / 任务列表扩展。
 * 2) escapeHtml=true：屏蔽 raw HTML，PKM XSS 必备。
 * 3) 在送入 commonmark 前先把 [[Title]] 重写为 [Title](/note/by-title/...)，
 *    实现 Obsidian 风格的双向链接（NoteViewController 兜底跳转）。
 */
@Component
public class MarkdownRenderer {

    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\[\\]\\n]{1,80})]]");

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        List<Extension> exts = List.of(
                TablesExtension.create(),
                TaskListItemsExtension.create()
        );
        this.parser = Parser.builder().extensions(exts).build();
        this.renderer = HtmlRenderer.builder()
                .extensions(exts)
                .escapeHtml(true)
                .build();
    }

    public String render(String md) {
        if (md == null || md.isBlank()) {
            return "";
        }
        return renderer.render(parser.parse(rewriteWikiLinks(md)));
    }

    private static String rewriteWikiLinks(String md) {
        Matcher m = WIKI_LINK.matcher(md);
        StringBuilder sb = new StringBuilder(md.length() + 32);
        while (m.find()) {
            String title = m.group(1).trim();
            String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
            // 标准 markdown 链接，由 commonmark 自动转 <a>
            String replacement = "[" + Matcher.quoteReplacement(title)
                    + "](/note/by-title/" + Matcher.quoteReplacement(encoded) + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
