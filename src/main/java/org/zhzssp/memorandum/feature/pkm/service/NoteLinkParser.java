package org.zhzssp.memorandum.feature.pkm.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 笔记内的双向链接 [[Title]] 与标签 #tag 解析器。
 *
 * V1 落库规则：保存笔记 → 取出全部 [[Title]] → 逐个查询本用户的同名笔记
 *   → 命中则在 link 表新增 (NOTE→NOTE) 一行，未命中跳过
 *   （用户后续创建该标题笔记时，再次保存任一侧即可补全反链）。
 */
@Component
public class NoteLinkParser {

    private static final Pattern WIKI = Pattern.compile("\\[\\[([^\\[\\]\\n]{1,80})]]");
    /** #tag：避免误匹配 URL 路径中的 #fragment、Markdown 标题 # 等 */
    private static final Pattern TAG = Pattern.compile("(?<![\\w/#])#([\\p{L}\\p{N}_\\-]{1,30})");

    public List<String> extractLinkedTitles(String md) {
        if (md == null || md.isBlank()) return List.of();
        Matcher m = WIKI.matcher(md);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        while (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty()) set.add(t);
        }
        return new ArrayList<>(set);
    }

    public List<String> extractTags(String md) {
        if (md == null || md.isBlank()) return List.of();
        Matcher m = TAG.matcher(md);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        while (m.find()) set.add(m.group(1).toLowerCase(Locale.ROOT));
        return new ArrayList<>(set);
    }
}
