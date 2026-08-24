package org.zhzssp.memorandum.feature.codex.distill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.service.CodexMetrics;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;
import org.zhzssp.memorandum.feature.codex.service.RepoWriteService;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointParser;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 出题编排（EXAMINER）：Guide → Checkpoint 条目。
 *
 * <h3>★核心门禁：验收命令引用的路径必须在磁盘上真实存在</h3>
 * <p>LLM 出题最典型的失效不是题目不好，而是<strong>编出一个不存在的脚本</strong>
 * （{@code bash scripts/all.sh}，而这个 lab 里根本没有 scripts 目录）。
 * 这种题从文件上看完全合规：有验收命令、有通过标准、判据是退出码 0。</p>
 *
 * <p>它的危害是精确的：跑起来必然失败，而失败原因是「文件不存在」而非「知识没掌握」。
 * 于是它会污染 <strong>checkpoint 通过率</strong>——本产品唯一号称无法造假的指标。
 * 一个被污染的无法造假的指标，比没有这个指标更糟，因为用户仍会相信它。</p>
 *
 * <p>所以这里的处置是<strong>丢弃</strong>而不是降级或标注：
 * 一道跑不起来的题没有中间形态可留。丢弃数单独计量，
 * 若长期高于产出数，说明出题这件事在当前语料上还不成立，该停用而不是继续调 prompt。</p>
 *
 * <h3>为什么先渲染成 Markdown 再用既有解析器验，而不是直接验 LLM 输出</h3>
 * <p>因为最终落库走的是「文件 → {@code CheckpointParser} → 数据库」这条路。
 * 只有在这条真实路径上验过，才能保证「校验通过」等于「落库后可运行」。
 * 若在 LLM 输出层校验，中间还隔着模板渲染与解析两道转换，
 * 任何一处形状不匹配都会让校验结论失真——而那正是最难发现的一类问题。</p>
 */
@Service
public class ExamService {

    private static final Logger log = LoggerFactory.getLogger(ExamService.class);

    private static final String M_ITEM = "@@ITEM@@";
    private static final String M_LEVEL = "@@LEVEL@@";
    private static final String M_TITLE = "@@TITLE@@";
    private static final String M_CHECKS = "@@CHECKS@@";
    private static final String M_TASK = "@@TASK@@";
    private static final String M_PREDICT = "@@PREDICT@@";
    private static final String M_CMD = "@@CMD@@";
    private static final String M_CRITERIA = "@@CRITERIA@@";
    private static final String M_BLIND = "@@BLIND@@";
    private static final String M_ITEMEND = "@@ITEMEND@@";

    /** 命令里像路径的 token：含 {@code /} 或带脚本类扩展名。 */
    private static final Pattern PATH_LIKE = Pattern.compile(
            "(?<![\\w./-])((?:[\\w.@-]+/)+[\\w.@-]+|[\\w.@-]+\\.(?:sh|py|cpp|cc|c|h|mlir|ll|td|yaml|yml|json|txt|cmake))");

    /** 明显不是仓库内路径的形状。 */
    private static final Pattern NOT_REPO_PATH = Pattern.compile(
            "^(?:https?://|/|~|\\$|-{1,2})|^[A-Za-z]:[/\\\\]");

    /** 一道被丢弃的题及原因——必须回报，否则用户只看到「出了 1 条」而不知道另 2 条去哪了。 */
    public record Discarded(String code, String reason) {}

    public record Draft(boolean ok, String errorCode, String message,
                        String title, String targetPath, String content,
                        List<String> accepted, List<Discarded> discarded,
                        String guidePath, String labDir, String topicCode) {

        static Draft fail(String code, String message) {
            return new Draft(false, code, message, null, null, null,
                    List.of(), List.of(), null, null, null);
        }
    }

    public record WriteResult(boolean ok, String code, String message,
                              String branch, String path, List<String> changedFiles,
                              int loadedIntoDb, String nextStep) {

        static WriteResult fail(String code, String message) {
            return new WriteResult(false, code, message, null, null, List.of(), 0, null);
        }
    }

    private final CheckpointTemplate template;
    private final CheckpointParser parser;
    private final CheckpointService checkpointService;
    private final DocWriteGuard writeGuard;
    private final RepoRegistryService registry;
    private final RepoWriteService writeService;
    private final RepoSyncService syncService;
    private final KbDocumentRepository docRepo;
    private final LlmGateway llm;
    private final CodexMetrics metrics;

    @Value("${codex.exam.enabled:false}")
    private boolean examEnabled;

    @Value("${codex.exam.max-items:4}")
    private int maxItems;

    @Value("${codex.exam.output-dir:docs/checkpoints}")
    private String outputDir;

    @Value("${codex.exam.guide-max-chars:30000}")
    private int guideMaxChars;

    public ExamService(CheckpointTemplate template,
                       CheckpointParser parser,
                       CheckpointService checkpointService,
                       DocWriteGuard writeGuard,
                       RepoRegistryService registry,
                       RepoWriteService writeService,
                       RepoSyncService syncService,
                       KbDocumentRepository docRepo,
                       LlmGateway llm,
                       CodexMetrics metrics) {
        this.template = template;
        this.parser = parser;
        this.checkpointService = checkpointService;
        this.writeGuard = writeGuard;
        this.registry = registry;
        this.writeService = writeService;
        this.syncService = syncService;
        this.docRepo = docRepo;
        this.llm = llm;
        this.metrics = metrics;
    }

    public boolean enabled() {
        return examEnabled;
    }

    /* ==================== 起草 ==================== */

    /**
     * 为一篇知识文档出题。
     *
     * @param guidePath 仓库内相对路径，必须已在索引中
     * @param labDir    对应动手项目目录（仓库内相对路径）。<strong>没有 lab 就无法出可执行的题</strong>
     * @param count     期望条数
     */
    public Draft draft(Long userId, String repoName, String guidePath,
                       String labDir, Integer count) {
        if (!examEnabled) {
            return Draft.fail("EXAM_DISABLED",
                    "出题未启用（codex.exam.enabled=false）。机器出的题会进入通过率统计，"
                            + "影响本产品最核心的指标，故默认关闭。");
        }
        KnowledgeRepo repo = resolveRepo(userId, repoName);
        if (repo == null) {
            return Draft.fail("REPO_NOT_FOUND", "未找到知识仓库。");
        }
        if (guidePath == null || guidePath.isBlank()) {
            return Draft.fail("GUIDE_REQUIRED", "未指定要出题的知识文档。");
        }
        String gp = guidePath.replace('\\', '/').strip();
        KbDocument doc = docRepo.findByRepoIdAndPath(repo.getId(), gp).orElse(null);
        if (doc == null) {
            return Draft.fail("GUIDE_NOT_FOUND",
                    "索引中没有这篇文档：" + gp + "。可先 repo.sync。");
        }

        Path root = registry.rootOf(repo);

        // ---- lab 必须真实存在：没有它，任何"可执行"的题都是编的 ----
        String lab = labDir == null || labDir.isBlank() ? null
                : labDir.replace('\\', '/').strip().replaceAll("/+$", "");
        if (lab == null) {
            return Draft.fail("LAB_REQUIRED",
                    "未指定对应的动手项目目录。没有 lab 就无法出可执行的题——"
                            + "模型只能凭想象编一个命令，而那种题跑起来的失败与掌握程度无关。"
                            + "若这篇文档暂无配套 lab，请先手动建一个最小可跑的目录。");
        }
        if (!Files.isDirectory(root.resolve(lab))) {
            return Draft.fail("LAB_NOT_FOUND",
                    "动手项目目录不存在：" + lab
                            + "。（这条校验挡的正是「命令看起来对但环境根本不在」这类题。）");
        }

        String content;
        try {
            content = Files.readString(root.resolve(gp), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return Draft.fail("GUIDE_READ_FAILED", "读取文档失败：" + e.getMessage());
        }
        if (content.length() > guideMaxChars) {
            content = content.substring(0, guideMaxChars);
        }

        int want = clamp(count == null ? 3 : count, 1, Math.max(1, maxItems));
        List<String> labFiles = listLabFiles(root.resolve(lab), lab);
        if (labFiles.isEmpty()) {
            return Draft.fail("LAB_EMPTY",
                    "动手项目目录 " + lab + " 下没有可执行的脚本或源文件，无法据此出题。");
        }

        metrics.recordExamAttempt();

        String raw;
        try {
            raw = llm.generateText(prompt(doc, content, lab, labFiles, want));
        } catch (Exception e) {
            return Draft.fail("LLM_FAILED", "出题调用失败：" + e.getMessage());
        }

        String topic = topicCodeOf(doc, gp);
        List<CheckpointTemplate.Item> items = parseItems(raw, want);
        if (items.isEmpty()) {
            return Draft.fail("NO_ITEMS_PARSED",
                    "模型输出里解析不到任何合法条目（可能没有按分隔符格式输出）。");
        }

        // ---- 逐条渲染 → 用【既有解析器】读回 → 校验路径 ----
        List<CheckpointTemplate.Item> accepted = new ArrayList<>();
        List<String> acceptedCodes = new ArrayList<>();
        List<Discarded> discarded = new ArrayList<>();
        int ord = 1;
        for (CheckpointTemplate.Item it : items) {
            CheckpointTemplate.Item numbered = withOrd(it, ord);
            String code = it.level() + "-" + topic + "-" + String.format("%02d", ord);

            String md = template.renderItem(topic, numbered);
            List<CheckpointParser.Parsed> back = parser.parse(md, lab);
            if (back.isEmpty()) {
                // 渲染出来自己的解析器都读不回去：说明模板与解析器失配，这是我们的 bug 不是模型的
                discarded.add(new Discarded(code,
                        "渲染后无法被 CheckpointParser 读回——模板与解析器失配，请报告此问题"));
                metrics.recordExamDiscardedNoCommand();
                continue;
            }
            CheckpointParser.Parsed p = back.get(0);
            if (!p.hasCommand() || p.verifyJson() == null) {
                discarded.add(new Discarded(code,
                        "没有可执行的验收命令。无判据的题无法被运行，也就无法证明任何事"));
                metrics.recordExamDiscardedNoCommand();
                continue;
            }
            String bad = firstMissingPath(root, lab, numbered.command());
            if (bad != null) {
                discarded.add(new Discarded(code,
                        "验收命令引用了不存在的路径「" + bad + "」。"
                                + "这类题跑起来的失败原因是环境不对而非没掌握，会污染通过率，故丢弃"));
                metrics.recordExamDiscardedBadPath();
                continue;
            }
            accepted.add(numbered);
            acceptedCodes.add(code);
            ord++;
        }

        if (accepted.isEmpty()) {
            metrics.recordExamDrafted(0);
            return new Draft(false, "ALL_DISCARDED",
                    "全部 " + items.size() + " 条都没通过校验，没有可用的题。"
                            + "多数情况是模型引用了不存在的脚本——"
                            + "这说明它对这个 lab 的实际内容不了解，"
                            + "而不是题目思路不好。可尝试补全 lab 的 README 后重试。",
                    null, null, null, List.of(), discarded, gp, lab, topic);
        }

        String bookTitle = (doc.getTitle() == null || doc.getTitle().isBlank()
                ? topic : doc.getTitle()) + " · 落地检验（AI 起草）";
        CheckpointTemplate.Book book = new CheckpointTemplate.Book(
                bookTitle, topic, gp, lab, accepted);
        String md = template.render(book);
        String path = trimSlash(outputDir) + "/" + slug(topic) + "-agent.md";

        metrics.recordExamDrafted(accepted.size());
        return new Draft(true, null,
                "已起草 " + accepted.size() + " 条（丢弃 " + discarded.size() + " 条）",
                bookTitle, path, md, acceptedCodes, discarded, gp, lab, topic);
    }

    /* ==================== 落盘 ==================== */

    public WriteResult write(Long userId, String repoName, Draft draft) {
        if (draft == null || draft.content() == null) {
            return WriteResult.fail("NO_DRAFT", "没有可写入的题目草稿。");
        }
        DocWriteGuard.Decision en = writeGuard.checkEnabled();
        if (!en.allowed()) {
            return WriteResult.fail(en.code(), en.message() + " " + nvl(en.hint()));
        }
        KnowledgeRepo repo = resolveRepo(userId, repoName);
        if (repo == null) {
            return WriteResult.fail("REPO_NOT_FOUND", "未找到知识仓库。");
        }
        String path = draft.targetPath();

        DocWriteGuard.Decision p = writeGuard.checkPath(repo, path);
        if (!p.allowed()) {
            return WriteResult.fail(p.code(), p.message() + " " + nvl(p.hint()));
        }
        // ★只准新建：用户手写的 9 册检验永远不可能被机器覆盖
        DocWriteGuard.Decision c = writeGuard.checkCreatable(repo, path, false);
        if (!c.allowed()) {
            return WriteResult.fail(c.code(), c.message() + " " + nvl(c.hint()));
        }

        RepoWriteService.BranchResult br = writeService.ensureBranch(repo,
                writeService.branchNameFor("exam", slug(draft.topicCode())));
        if (!br.ok()) {
            return WriteResult.fail(br.code(), br.message());
        }

        Path root = registry.rootOf(repo);
        Path target = root.resolve(path);
        if (Files.exists(target)) {
            return WriteResult.fail("FILE_EXISTS_PROTECTED",
                    "文件已存在：" + path + "。出题只允许新建。");
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

        // 先建索引（syncFromRepo 只扫 kind=CHECKPOINT_SET 的已索引文档），再灌库
        int loaded = 0;
        try {
            syncService.sync(repo, false, false);
            CheckpointService.SyncResult sr = checkpointService.syncFromRepo(repo);
            loaded = sr.created();
        } catch (Exception e) {
            log.warn("[Codex/Exam] 写入成功但落库失败：{}", e.getMessage());
        }

        return new WriteResult(true, "WRITTEN",
                "已写入 " + path + "，并载入 " + loaded + " 条检验（标记为 AGENT_DRAFT）",
                br.branch(), path, new ArrayList<>(changed), loaded,
                "这些题在检验面板里会标注「判据未经人工验证」。"
                        + "★先自己把每条的验收命令跑一遍，确认它失败时失败在知识上而不是环境上；"
                        + "确认后再改 maturity 并提交。统计口径上 AGENT_DRAFT 与人写的题分开计算，"
                        + "所以不会污染你原有的通过率。");
    }

    /* ==================== 路径校验（★门禁核心） ==================== */

    /**
     * 找出命令里第一个不存在的仓库内路径；全部存在返回 null。
     *
     * <p>只校验<strong>看起来像仓库内相对路径</strong>的 token：绝对路径、URL、
     * 变量展开、命令选项都跳过——它们不是我们能判断的东西，
     * 而在这里"从严"会把合法的题误杀，那种误杀用户看不出原因。</p>
     *
     * <p>公开是为了让这条判据可被独立测试。它是 P4 唯一一条守着
     * 「通过率不被污染」的规则，必须有直接针对它的测试，
     * 而不是只能通过一次完整的出题流程间接验证。</p>
     */
    public String firstMissingPath(Path root, String labDir, String command) {
        if (command == null || command.isBlank()) return null;
        Path cwd = root.resolve(labDir == null ? "" : labDir);
        for (String raw : command.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = PATH_LIKE.matcher(line);
            while (m.find()) {
                String token = m.group(1);
                if (NOT_REPO_PATH.matcher(token).find()) continue;
                if (token.contains("*") || token.contains("$")) continue;   // glob / 变量不判
                // 依次按 lab 内、仓库根解析；命令里也常出现 ../ 形式
                if (existsAny(cwd, root, token)) continue;
                return token;
            }
        }
        return null;
    }

    private boolean existsAny(Path cwd, Path root, String token) {
        for (Path base : List.of(cwd, root)) {
            try {
                Path candidate = base.resolve(token).normalize();
                if (!candidate.startsWith(root)) continue;      // 越出仓库的一律不算存在
                if (Files.exists(candidate)) return true;
            } catch (Exception ignored) {
                // 非法路径形状按不存在处理
            }
        }
        return false;
    }

    /** 列出 lab 下可作为验收入口的文件（相对 lab 的路径）。 */
    List<String> listLabFiles(Path labRoot, String labDir) {
        List<String> out = new ArrayList<>();
        try (var walk = Files.walk(labRoot, 3)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                String rel = labRoot.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith("out/") || rel.contains("/out/")
                        || rel.startsWith(".") || rel.contains("__pycache__")) {
                    return;
                }
                String low = rel.toLowerCase(Locale.ROOT);
                if (low.endsWith(".sh") || low.endsWith(".py") || low.endsWith(".mlir")
                        || low.endsWith(".ll") || low.endsWith(".cpp") || low.endsWith(".cc")
                        || low.endsWith(".td") || low.endsWith("readme.md")
                        || low.endsWith("makefile") || low.endsWith("cmakelists.txt")) {
                    out.add(rel);
                }
            });
        } catch (Exception e) {
            log.debug("[Codex/Exam] 扫描 lab 失败 {}：{}", labDir, e.getMessage());
        }
        out.sort(String::compareTo);
        return out.size() > 60 ? out.subList(0, 60) : out;
    }

    /* ==================== Prompt 与解析 ==================== */

    /**
     * 出题 prompt。
     *
     * <p>把 lab 的<strong>真实文件清单</strong>塞进 prompt，是让「命令必须指向存在的文件」
     * 从一句要求变成可能做到的事。只写「不要编脚本名」而不给清单，
     * 等于要求模型凭空猜对——它会照写一个最常见的名字，然后被门禁丢掉。</p>
     */
    private String prompt(KbDocument doc, String guideContent, String lab,
                          List<String> labFiles, int count) {
        return """
                你在为一篇技术教材出「知识落地检验」题目。要求非常具体，请严格遵守。

                教材：%s（%s）
                对应动手项目目录：%s
                该目录下实际存在的文件（★验收命令只能引用这些文件，一个字都不能改）：
                %s

                出 %d 道题，分级含义：
                - L0 复现：跑通既有脚本、读懂产物。门槛，不算掌握。
                - L1 改一处：改一个参数或规则，先预测再验证。证明知道因果方向。
                - L2 加组件：新增一个 op / pass / pattern / 策略并补测试。★这是主判据。
                - L3 打通：跨层或端到端。
                请至少包含一道 L1 和一道 L2。

                每道题按下面格式输出，不要写格式之外的任何话：

                %s
                %sL1
                %s一句话标题（不含级别前缀）
                %s这条通过意味着掌握了哪个具体知识点
                %s要做什么，写清改哪个文件的哪一处
                %s动手前必须先回答的 2~3 个问题（列表项）。★问题要问「结果会怎么变」而不是「是什么」
                %s
                第一行可以是 cd 到子目录，其后是可直接执行的命令。
                ★只能引用上面清单里出现过的文件路径；不确定某个文件存在就不要写它。
                ★不要用管道、重定向、&&、heredoc——解析器只取能安全单独执行的行。
                %s通过标准，必须机器可判定（退出码 / 输出里必须出现的字符串 / 生成了哪个文件）
                %s常见失败 → 对应的盲点是什么，回指教材哪一节
                %s

                教材内容：
                ---
                %s
                ---
                """.formatted(
                doc.getTitle() == null ? doc.getPath() : doc.getTitle(), doc.getPath(),
                lab, "- " + String.join("\n- ", labFiles), count,
                M_ITEM, M_LEVEL, M_TITLE, M_CHECKS, M_TASK, M_PREDICT, M_CMD,
                M_CRITERIA, M_BLIND, M_ITEMEND,
                guideContent);
    }

    public List<CheckpointTemplate.Item> parseItems(String raw, int want) {
        List<CheckpointTemplate.Item> out = new ArrayList<>();
        if (raw == null) return out;
        String[] blocks = raw.split(Pattern.quote(M_ITEM));
        for (String b : blocks) {
            if (!b.contains(M_CMD)) continue;
            String level = normalizeLevel(DistillService.cut(b, M_LEVEL, M_TITLE));
            String title = DistillService.cut(b, M_TITLE, M_CHECKS);
            String checks = DistillService.cut(b, M_CHECKS, M_TASK);
            String task = DistillService.cut(b, M_TASK, M_PREDICT);
            String predict = DistillService.cut(b, M_PREDICT, M_CMD);
            String cmd = stripFences(DistillService.cut(b, M_CMD, M_CRITERIA));
            String criteria = DistillService.cut(b, M_CRITERIA, M_BLIND);
            String blind = DistillService.cut(b, M_BLIND, M_ITEMEND);
            if (title == null || cmd == null || cmd.isBlank()) continue;
            out.add(new CheckpointTemplate.Item(level, out.size() + 1, title, checks,
                    null, task, predict, cmd, criteria, blind, "local", null));
            if (out.size() >= want) break;
        }
        return out;
    }

    /** 模型常把命令再包一层 ``` ——去掉，否则渲染后会出现嵌套围栏。 */
    private String stripFences(String s) {
        if (s == null) return null;
        String t = s.strip();
        t = t.replaceAll("(?m)^\\s*(```|~~~)[a-zA-Z0-9]*\\s*$", "").strip();
        return t.isEmpty() ? null : t;
    }

    private String normalizeLevel(String s) {
        if (s == null) return "L1";
        String t = s.strip().toUpperCase(Locale.ROOT);
        Matcher m = Pattern.compile("L[0-3]").matcher(t);
        return m.find() ? m.group() : "L1";
    }

    private CheckpointTemplate.Item withOrd(CheckpointTemplate.Item it, int ord) {
        return new CheckpointTemplate.Item(it.level(), ord, it.title(), it.checksWhat(),
                it.prerequisite(), it.task(), it.prediction(), it.command(),
                it.criteria(), it.blindSpots(), it.resource(), it.estHours());
    }

    /**
     * 主题代码：只允许字母数字（{@code CheckpointParser.ENTRY} 的分组要求）。
     *
     * <p>用 {@code -AGENT} 之类的后缀区分机器出的题是很诱人的，但不行：
     * 那会让同一主题的人写题与机器题在代码上分属两个 topic，
     * 而 topic 是用户心里的「这一册讲什么」。区分靠 {@code verifySource}，不靠代码。</p>
     */
    private String topicCodeOf(KbDocument doc, String path) {
        String base = path.substring(path.lastIndexOf('/') + 1)
                .replaceAll("\\.md$", "")
                .replaceAll("-learning-guide$", "")
                .replaceAll("-guide$", "");
        String code = base.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (code.isEmpty()) code = "TOPIC";
        return code.length() > 16 ? code.substring(0, 16) : code;
    }

    private String slug(String s) {
        String t = (s == null ? "topic" : s).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return t.isEmpty() ? "topic" : t;
    }

    private KnowledgeRepo resolveRepo(Long userId, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(userId, repoName.strip()).orElse(null);
        }
        List<KnowledgeRepo> all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String trimSlash(String s) {
        String t = (s == null || s.isBlank()) ? "docs/checkpoints" : s.strip().replace('\\', '/');
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
