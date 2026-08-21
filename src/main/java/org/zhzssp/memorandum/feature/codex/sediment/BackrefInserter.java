package org.zhzssp.memorandum.feature.codex.sediment;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.index.MarkdownStructureParser;

import java.util.List;
import java.util.Locale;

/**
 * 在知识文档中插入一行速记引用。
 *
 * <h3>为什么这是唯一允许修改既有文档的操作</h3>
 * <p>用户的 6 篇主干 guide 累计 40 万字符，是数月工作的产物。
 * 给模型「改写文档」的能力，收益是省几分钟排版，风险是一次幻觉造成不可逆的内容损失。
 * 所以这里把能力压到最小：<strong>只在指定章节末尾插入一行，不删除、不改写任何既有字符</strong>。</p>
 *
 * <p>结果是「Agent 弄坏我的 guide」在结构上不可能发生——
 * 最坏情况是多了一行无用的引用，一眼可见、一键可删。</p>
 *
 * <h3>为什么现场重新解析章节结构，而不用 kb_section 的偏移量</h3>
 * <p>{@code kb_section.char_start/char_end} 是索引时的快照。用户可能在那之后编辑过文件，
 * 而<strong>拿过期偏移量去写文件会把内容插进句子中间</strong>——这种损坏还很难被发现，
 * 因为 Markdown 不会因此报错。现场解析多花几毫秒，换来的是「写入位置一定正确」。</p>
 */
@Component
public class BackrefInserter {

    /** 插入结果状态。 */
    public enum Outcome {
        INSERTED,
        /** 该章节已存在指向同一篇笔记的引用——重复插入只会制造噪音。 */
        ALREADY_PRESENT,
        ANCHOR_NOT_FOUND
    }

    /**
     * @param newContent  插入后的完整内容（未变更时为 null）
     * @param line        插入位置行号（1-based）
     * @param anchorUsed  实际命中的 anchor
     */
    public record Result(Outcome outcome, String newContent, Integer line,
                         String anchorUsed, String message, List<String> availableAnchors) {}

    private final MarkdownStructureParser structure;

    public BackrefInserter(MarkdownStructureParser structure) {
        this.structure = structure;
    }

    /**
     * 在 {@code anchor} 指定章节的正文末尾插入 {@code backrefLine}。
     *
     * @param content     知识文档当前完整内容
     * @param bodyStart   front-matter 之后的偏移
     * @param anchor      目标章节 anchor
     * @param backrefLine 完整的速记行（由 {@link NoteTemplate#backrefLine} 生成）
     * @param notePath    笔记路径，用于幂等判定
     */
    public Result insert(String content, int bodyStart, String anchor,
                         String backrefLine, String notePath) {
        if (content == null || content.isEmpty()) {
            return new Result(Outcome.ANCHOR_NOT_FOUND, null, null, null,
                    "文档内容为空。", List.of());
        }
        MarkdownStructureParser.Result parsed = structure.parse(content, bodyStart);
        MarkdownStructureParser.Section target = null;
        String want = anchor == null ? "" : anchor.strip().toLowerCase(Locale.ROOT);
        for (MarkdownStructureParser.Section s : parsed.sections()) {
            if (s.anchor().toLowerCase(Locale.ROOT).equals(want)) {
                target = s;
                break;
            }
        }
        if (target == null) {
            return new Result(Outcome.ANCHOR_NOT_FOUND, null, null, null,
                    "文档中不存在 anchor「" + anchor + "」。锚点必须来自 doc.outline 的输出，"
                            + "不能凭标题猜测——猜错会把引用插到无关章节。",
                    parsed.sections().stream().limit(60)
                            .map(MarkdownStructureParser.Section::anchor).toList());
        }

        int from = Math.max(0, Math.min(target.charStart(), content.length()));
        int to = Math.max(from, Math.min(target.charEnd(), content.length()));
        String region = content.substring(from, to);

        // 幂等：同一章节已引用同一篇笔记则不再插入
        if (notePath != null && !notePath.isBlank() && regionReferences(region, notePath)) {
            return new Result(Outcome.ALREADY_PRESENT, null, null, target.anchor(),
                    "该章节已存在指向 " + notePath + " 的引用，未重复插入。", List.of());
        }

        // 定位章节正文最后一个非空白字符之后的位置
        int insertAt = to;
        while (insertAt > from && Character.isWhitespace(content.charAt(insertAt - 1))) insertAt--;

        // 章节完全为空（标题下面直接是下一个标题）时，插在标题行之后
        if (insertAt <= from) {
            String text = "\n" + backrefLine + "\n";
            String merged = content.substring(0, from) + text + content.substring(from);
            return new Result(Outcome.INSERTED, merged, lineAt(merged, from + 1),
                    target.anchor(), "章节正文为空，已插在标题之后。", List.of());
        }

        String lastLine = lastLineBefore(content, insertAt);
        // 连续的速记行在语料里是紧邻排布的（不夹空行），保持一致
        boolean adjacentToBackref = lastLine.stripLeading().startsWith(NoteTemplate.BACKREF_PREFIX);
        String text = (adjacentToBackref ? "\n" : "\n\n") + backrefLine;

        String merged = content.substring(0, insertAt) + text + content.substring(insertAt);
        return new Result(Outcome.INSERTED, merged, lineAt(merged, insertAt + text.length()),
                target.anchor(), "已在 §" + target.heading() + " 末尾插入速记引用。", List.of());
    }

    /** 该章节内是否已有指向该笔记的链接。 */
    private boolean regionReferences(String region, String notePath) {
        String file = notePath.replace('\\', '/');
        int i = file.lastIndexOf('/');
        String fileName = i < 0 ? file : file.substring(i + 1);
        // 用文件名而非完整相对路径判定：同一篇笔记在不同 guide 里的相对路径不同
        // （../notes/x.md vs ./x.md），只比全路径会漏判成"未引用"而重复插入
        return region.contains(fileName);
    }

    private String lastLineBefore(String content, int pos) {
        int start = content.lastIndexOf('\n', pos - 1) + 1;
        return content.substring(start, pos);
    }

    private int lineAt(String content, int offset) {
        int line = 1;
        int limit = Math.min(offset, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
