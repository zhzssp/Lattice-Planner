package org.zhzssp.memorandum.feature.codex.distill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.service.CodexMetrics;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;
import org.zhzssp.memorandum.feature.codex.service.RepoWriteService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 蒸馏编排（DISTILLER）：Source → Guide 草稿。
 *
 * <h3>三段式流程，且第一段刻意不需要写权限</h3>
 * <pre>
 * ① 读原料   SourceReader 拿【原文】（不经摘要）+ 提取质量门禁
 * ② 起草     map（逐段抽要点）→ reduce（汇总成四个小节）→ 模板渲染
 * ③ 落盘     DistillGuard 结构门禁 → create-only 白名单 → 工作分支 → 不 commit
 * </pre>
 * <p>①② 不碰磁盘、不需要 {@code codex.write.enabled}。
 * 这个切分是刻意的：用户可以先在零风险下看几篇草稿，判断蒸馏质量到不到位，
 * 再决定要不要给它写文件的权限。与 P2「先 CI 后写入」、P3「先解析后记录」同一手法——
 * <strong>先建立对判断的信任，再给它改动的权限。</strong></p>
 *
 * <h3>★为什么用分隔符协议而不是要模型输出 JSON</h3>
 * <p>草稿正文里必然含代码块、反斜杠、大量换行。让模型把这些塞进 JSON 字符串，
 * 转义出错的概率远高于漏写一个分隔符；而 JSON 解析失败是<strong>整篇作废</strong>，
 * 分隔符缺失只丢一节、其余照常可用。失败代价的量级不同，载体就该不同。</p>
 */
@Service
public class DistillService {

    private static final Logger log = LoggerFactory.getLogger(DistillService.class);

    /** 分隔符：刻意用不会出现在正文里的形状。 */
    private static final String M_ONELINER = "@@ONELINER@@";
    private static final String M_FRAMEWORK = "@@FRAMEWORK@@";
    private static final String M_FEATURES = "@@FEATURES@@";
    private static final String M_MASTERY = "@@MASTERY@@";
    private static final String M_SKIP = "@@SKIP@@";
    private static final String M_OPEN = "@@OPEN@@";
    private static final String M_END = "@@END@@";

    /** 起草结果（未落盘）。 */
    public record Draft(boolean ok, String code, String message,
                        String title, String targetPath, String content,
                        SourceReader.Source source,
                        DistillGuard.Verdict verdict,
                        int llmCalls, long elapsedMs) {

        static Draft fail(String code, String message) {
            return new Draft(false, code, message, null, null, null, null, null, 0, 0);
        }
    }

    /** 落盘结果。 */
    public record WriteResult(boolean ok, String code, String message,
                              String branch, String path, List<String> changedFiles,
                              String reindex, String nextStep, List<String> skipTerms) {

        static WriteResult fail(String code, String message) {
            return new WriteResult(false, code, message, null, null, List.of(), null, null, List.of());
        }
    }

    private final SourceReader reader;
    private final GuideTemplate template;
    private final DistillGuard guard;
    private final DocWriteGuard writeGuard;
    private final RepoRegistryService registry;
    private final RepoWriteService writeService;
    private final RepoSyncService syncService;
    private final LlmGateway llm;
    private final CodexMetrics metrics;

    @Value("${codex.distill.enabled:false}")
    private boolean distillEnabled;

    @Value("${codex.distill.max-chunks:8}")
    private int maxChunks;

    @Value("${codex.distill.output-dir:docs/paper-notes}")
    private String outputDir;

    public DistillService(SourceReader reader,
                          GuideTemplate template,
                          DistillGuard guard,
                          DocWriteGuard writeGuard,
                          RepoRegistryService registry,
                          RepoWriteService writeService,
                          RepoSyncService syncService,
                          LlmGateway llm,
                          CodexMetrics metrics) {
        this.reader = reader;
        this.template = template;
        this.guard = guard;
        this.writeGuard = writeGuard;
        this.registry = registry;
        this.writeService = writeService;
        this.syncService = syncService;
        this.llm = llm;
        this.metrics = metrics;
    }

    public boolean enabled() {
        return distillEnabled;
    }

    public String outputDir() {
        return trimSlash(outputDir);
    }

    /* ==================== ①② 起草（不落盘） ==================== */

    /**
     * 从原料起草一篇 guide。
     *
     * @param sourceFile 原料绝对路径。必须已通过白名单校验
     * @param title      标题；留空则用文件名
     * @param domain     所属域（写进 front-matter）
     */
    public Draft draft(Long userId, Path sourceFile, String title, String domain) {
        long t0 = System.currentTimeMillis();
        if (!distillEnabled) {
            return Draft.fail("DISTILL_DISABLED",
                    "蒸馏未启用（codex.distill.enabled=false）。"
                            + "它会对原料做多次 LLM 调用，成本不低，故默认关闭。");
        }

        SourceReader.Source src = reader.read(sourceFile);
        if (!src.ok()) {
            metrics.recordDistillRejected(src.code());
            return new Draft(false, src.code(), src.message(), null, null, null,
                    src, null, 0, System.currentTimeMillis() - t0);
        }

        metrics.recordDistillAttempt();

        String finalTitle = (title == null || title.isBlank())
                ? guessTitle(src) : title.strip();

        List<SourceReader.Chunk> chunks = src.chunks();
        boolean chunkTruncated = false;
        if (chunks.size() > Math.max(1, maxChunks)) {
            chunks = chunks.subList(0, Math.max(1, maxChunks));
            chunkTruncated = true;
        }

        // ---- map：逐段抽要点 ----
        int calls = 0;
        List<String> notes = new ArrayList<>();
        for (SourceReader.Chunk c : chunks) {
            try {
                notes.add("【" + c.heading() + "】\n" + llm.generateText(mapPrompt(finalTitle, c)));
                calls++;
            } catch (Exception e) {
                // 单段失败不放弃整篇：少一段素材产出会薄一些，但仍可能过门禁；
                // 而整篇作废会让用户白等前面几次调用的时间
                log.warn("[Codex/Distill] 第 {} 段抽取失败：{}", c.ord(), e.getMessage());
                notes.add("【" + c.heading() + "】（本段抽取失败：" + e.getMessage() + "）");
            }
        }
        if (notes.stream().allMatch(n -> n.contains("（本段抽取失败"))) {
            return new Draft(false, "LLM_UNAVAILABLE",
                    "所有分段的抽取都失败了，多半是 LLM 不可用或密钥未配置。",
                    finalTitle, null, null, src, null, calls,
                    System.currentTimeMillis() - t0);
        }

        // ---- reduce：汇总成四个小节 ----
        String reduced;
        try {
            reduced = llm.generateText(reducePrompt(finalTitle, src, notes));
            calls++;
        } catch (Exception e) {
            return new Draft(false, "LLM_FAILED",
                    "汇总阶段调用失败：" + e.getMessage(), finalTitle, null, null,
                    src, null, calls, System.currentTimeMillis() - t0);
        }

        List<String> openIssues = new ArrayList<>(splitLines(cut(reduced, M_OPEN, M_END)));
        if (chunkTruncated) {
            // 截断必须自报：否则用户会以为这篇 guide 覆盖了整份原料
            openIssues.add("原料被截断：只蒸馏了前 " + chunks.size() + " 段（共 "
                    + src.chunks().size() + " 段），后面的内容未纳入。");
        }

        GuideTemplate.Spec spec = new GuideTemplate.Spec(
                finalTitle,
                src.fileName(),
                relativeIfInside(userId, sourceFile),
                domain,
                cut(reduced, M_ONELINER, M_FRAMEWORK),
                cut(reduced, M_FRAMEWORK, M_FEATURES),
                cut(reduced, M_FEATURES, M_MASTERY),
                cut(reduced, M_MASTERY, M_SKIP),
                splitLines(cut(reduced, M_SKIP, M_OPEN)),
                openIssues,
                src.charCount(), src.pageCount());

        String content = template.render(spec);
        DistillGuard.Verdict verdict = guard.check(content);
        if (!verdict.pass()) {
            metrics.recordDistillRejected(verdict.errors().get(0).code());
        }

        String path = outputDir() + "/" + template.slug(finalTitle) + ".md";
        return new Draft(verdict.pass(),
                verdict.pass() ? "DRAFTED" : "STRUCTURE_REJECTED",
                verdict.summary() + (verdict.pass() ? "" : "：" + verdict.firstError()),
                finalTitle, path, content, src, verdict, calls,
                System.currentTimeMillis() - t0);
    }

    /* ==================== ③ 落盘 ==================== */

    /**
     * 把草稿写入仓库。
     *
     * <p>刻意<strong>不接受调用方直接传内容</strong>：内容必须来自 {@link #draft}
     * 并当场重新过一遍门禁。若允许传任意文本进来，
     * 「必须有止损线」这条约束就退化成了一句建议——绕过它只需换个入口。</p>
     */
    public WriteResult write(Long userId, String repoName, Draft draft, String pathOverride) {
        if (draft == null || draft.content() == null) {
            return WriteResult.fail("NO_DRAFT", "没有可写入的草稿。请先起草。");
        }
        DocWriteGuard.Decision en = writeGuard.checkEnabled();
        if (!en.allowed()) {
            return WriteResult.fail(en.code(), en.message() + " " + nvl(en.hint()));
        }

        // 重新校验：草稿可能是上一次会话产生的，期间配置或规则可能变了
        DistillGuard.Verdict v = guard.check(draft.content());
        if (!v.pass()) {
            return WriteResult.fail("STRUCTURE_REJECTED",
                    "结构校验未通过，拒绝写入：" + v.firstError());
        }

        KnowledgeRepo repo = resolveRepo(userId, repoName);
        if (repo == null) {
            return WriteResult.fail("REPO_NOT_FOUND",
                    "未找到知识仓库" + (repoName == null ? "" : "：" + repoName));
        }

        String path = (pathOverride == null || pathOverride.isBlank())
                ? draft.targetPath() : pathOverride.replace('\\', '/').strip();

        DocWriteGuard.Decision p = writeGuard.checkPath(repo, path);
        if (!p.allowed()) {
            return WriteResult.fail(p.code(), p.message() + " " + nvl(p.hint()));
        }
        // ★create-only：蒸馏永远只新建，不覆盖既有文件
        DocWriteGuard.Decision c = writeGuard.checkCreatable(repo, path, false);
        if (!c.allowed()) {
            return WriteResult.fail(c.code(), c.message() + " " + nvl(c.hint()));
        }
        DocWriteGuard.Decision size = writeGuard.checkSize(draft.content(), writeGuard.maxGuideChars());
        if (!size.allowed()) {
            return WriteResult.fail(size.code(), size.message() + " " + nvl(size.hint()));
        }

        String branchName = writeService.branchNameFor("distill", template.slug(draft.title()));
        RepoWriteService.BranchResult br = writeService.ensureBranch(repo, branchName);
        if (!br.ok()) {
            return WriteResult.fail(br.code(), br.message());
        }

        Path root = registry.rootOf(repo);
        Path target = root.resolve(path);
        // 建分支后文件可能已经从别的分支带过来了，再确认一次
        if (Files.exists(target)) {
            return WriteResult.fail("FILE_EXISTS_PROTECTED",
                    "文件已存在：" + path + "。蒸馏只允许新建，请换个文件名。");
        }
        Set<String> changed = new LinkedHashSet<>();
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, draft.content(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            changed.add(path);
        } catch (Exception e) {
            return WriteResult.fail("WRITE_FAILED", "写入失败：" + e.getMessage());
        }

        String reindex;
        try {
            RepoSyncService.SyncResult sr = syncService.sync(repo, false, false);
            reindex = "已增量重建索引：重建 " + sr.report().docsReindexed() + " 篇";
        } catch (Exception e) {
            reindex = "索引重建失败（文件已写入，可稍后 repo.sync）：" + e.getMessage();
        }

        metrics.recordDistillWritten(v.skipTerms().size());
        return new WriteResult(true, "WRITTEN",
                "已写入 " + path + "（草稿，maturity=draft）",
                br.branch(), path, new ArrayList<>(changed), reindex,
                "下一步按顺序做三件事：①在 IDE 里对着原文核对「待人工核对」那五条；"
                        + "②确认「可以先跳过」的判断——它会进止损线，跳错了几周后会变成挡路的盲区；"
                        + "③核对完成后再提交。改动现在停在未提交状态，提交表达的是「我认可这份产出」。",
                v.skipTerms());
    }

    /* ==================== Prompt ==================== */

    /**
     * map 阶段：只抽事实，不做归纳。
     *
     * <p>刻意要求「原样抄出标识符与数字」：蒸馏最典型的失败不是写错，是写空——
     * 产出一堆「该模块负责处理相关逻辑」这类既无法验证也无法反驳的句子。
     * 在 map 阶段就把具体符号捞出来，reduce 阶段才有东西可用。</p>
     */
    private String mapPrompt(String title, SourceReader.Chunk chunk) {
        return """
                你在为一篇技术学习教材做素材抽取。这是原料的一段，标题：%s。
                目标教材主题：%s

                只做抽取，不做归纳、不写总结段落。按以下四类分别列出（没有就写「无」）：

                1. 机制事实：数据/控制怎么流动，关键对象与函数叫什么，一次典型调用经过哪些站点。
                   ★必须原样抄出原文里的标识符、类型名、张量维度、超参数值——不要改写成中文描述。
                2. 值得学的特性：这段里有哪些是理解这套东西必须掌握的，各自在原文哪个位置（节号/图表号）。
                3. 可以先跳过的内容：这段里哪些属于「知道有这回事就够，遇到再学」。
                   给出具体名字（API 名、子模块名、变体名），不要写「细节」「其他部分」这类空话。
                4. 存疑处：原文表述不清、或你不确定自己读对了的地方。

                原料：
                ---
                %s
                ---
                """.formatted(nvl(chunk.heading()), title, chunk.text());
    }

    /**
     * reduce 阶段：汇总成四个固定小节。
     *
     * <p>prompt 里把「止损线不可省」写成硬要求并说明后果——
     * 但真正保证它的是 {@link DistillGuard}，不是这段话。
     * 提示层负责让模型倾向于照做，执行层负责让不照做时失败。</p>
     */
    private String reducePrompt(String title, SourceReader.Source src, List<String> notes) {
        return """
                你在写一篇技术学习教材（guide），主题：%s。
                原料是一份 %d 页 / %d 字符的文档，下面是从各段抽出的素材。

                写成四个小节，用给定分隔符隔开，严格按顺序输出，不要写任何分隔符之外的话：

                %s
                一句话说清这套东西解决什么问题、以及为什么值得学。不超过 60 字。

                %s
                核心运行框架。要求：
                - 写出真实的对象名/函数名/维度/一次调用的站点顺序，用行内反引号标出；
                - 至少一个代码块或流程块（可以是伪码、IR、对象树、调用链）；
                - 禁止写「负责处理相关逻辑」这类无法验证的句子。

                %s
                必学特性表。必须是 Markdown 表格，三列：特性 | 为什么必学 | 在原文哪一节/哪张图。

                %s
                掌握标准。至少 4 条列表项，每条都要可判定，形如「能画出 X 的数据流」
                「能说清 A 与 B 的区别」「能改一处参数并预先说出结果会怎么变」。
                禁止写「理解本文内容」这类不可判定的条目。

                %s
                可以先跳过的内容。这一节不可省——没有止损线的教材会让人一路深挖到放弃。
                每条一个列表项，形如：
                - `具体名字` 的完整用法（除非要做 …）
                要求：必须给出具体的 API/子模块/变体名字，用反引号或粗体标出；
                不要写「实现细节」「其余部分」这类没有名字的条目（那样无法被系统识别）。
                至少 3 条。

                %s
                你不确定的地方，每条一个列表项。没有就写「无」。

                %s

                素材：
                ---
                %s
                ---
                """.formatted(title, src.pageCount(), src.charCount(),
                M_ONELINER, M_FRAMEWORK, M_FEATURES, M_MASTERY, M_SKIP, M_OPEN, M_END,
                String.join("\n\n", notes));
    }

    /* ==================== 内部 ==================== */

    /** 取两个分隔符之间的内容；缺失返回 null（只丢这一节，不影响其余）。 */
    static String cut(String text, String from, String to) {
        if (text == null) return null;
        int a = text.indexOf(from);
        if (a < 0) return null;
        a += from.length();
        int b = text.indexOf(to, a);
        String seg = (b < 0 ? text.substring(a) : text.substring(a, b)).strip();
        return seg.isEmpty() ? null : seg;
    }

    /** 把一段列表文本切成条目；去掉列表符号与「无」。 */
    static List<String> splitLines(String block) {
        List<String> out = new ArrayList<>();
        if (block == null) return out;
        for (String raw : block.split("\\R")) {
            String s = raw.strip().replaceFirst("^(?:[-*+]|\\d+[.)])\\s*", "").strip();
            if (s.isEmpty() || s.equals("无") || s.equals("None")) continue;
            out.add(s);
        }
        return out;
    }

    private String guessTitle(SourceReader.Source src) {
        String n = src.fileName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return base.replace('_', ' ').replace('-', ' ').strip();
    }

    /** 原料若在某个已登记仓库内，返回仓库内相对路径（写进 front-matter 做溯源）。 */
    private String relativeIfInside(Long userId, Path file) {
        try {
            Path abs = file.toAbsolutePath().normalize();
            for (KnowledgeRepo r : registry.listEnabled(userId)) {
                Path root = registry.rootOf(r).toAbsolutePath().normalize();
                if (abs.startsWith(root)) {
                    return root.relativize(abs).toString().replace('\\', '/');
                }
            }
        } catch (Exception ignored) {
            // 溯源信息缺失不影响蒸馏，只是 front-matter 里少一个 path
        }
        return null;
    }

    private KnowledgeRepo resolveRepo(Long userId, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(userId, repoName.strip()).orElse(null);
        }
        List<KnowledgeRepo> all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    private static String trimSlash(String s) {
        String t = (s == null || s.isBlank()) ? "docs/paper-notes" : s.strip().replace('\\', '/');
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 供 UI 展示用：原料类型是否支持。 */
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf") || n.endsWith(".md") || n.endsWith(".txt")
                || n.endsWith(".docx") || n.endsWith(".xlsx");
    }
}
