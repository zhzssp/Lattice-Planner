package org.zhzssp.memorandum.feature.codex.sediment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.index.RepoIndexer;
import org.zhzssp.memorandum.feature.codex.index.RepoLayout;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 写入知识仓库前的闸门。
 *
 * <h3>核心约束：Agent 能新建笔记，但只能对既有文档做外科式插入</h3>
 * <p>写入路径白名单默认只含 {@code docs/notes/**}。这不是限制功能，而是划定损失边界：</p>
 * <ul>
 *   <li>在 {@code notes/} 下<strong>新建</strong>文件，最坏后果是多一篇没用的笔记，删掉即可；</li>
 *   <li>而用户的 6 篇主干 guide 累计 40 万字符、耗时数月写成，
 *       让模型有能力整体重写它们，一次幻觉就可能造成不可逆的内容损失。</li>
 * </ul>
 * <p>因此 guide 只能通过 {@code doc.insert_backref} 修改，且那条路径<strong>只插入一行</strong>
 * （见 {@link BackrefInserter}）。这条边界让「Agent 破坏语料」在结构上不可能，
 * 而不是依赖 prompt 里写「请不要改动无关章节」。</p>
 *
 * <h3>示例入库门禁是执行层强制</h3>
 * <p>SKILL.md 把「问答里的示例必须一并写入笔记」列为硬性约束。
 * 靠 prompt 提醒是不够的——模型天然倾向于把内容压缩得更"整洁"，
 * 而被压掉的恰恰是让人半年后还能重新看懂的那部分。所以这里做成拒绝写入。</p>
 *
 * <h3>★P4 新增：「只准新建」白名单（create-only）</h3>
 * <p>P4 的蒸馏与出题必须能写 {@code docs/paper-notes/} 与 {@code docs/checkpoints/}。
 * 但这两个目录里<strong>已有用户手写的高质量内容</strong>（9 册检验、86 条题目）。
 * 若简单把它们加进上面那份白名单，「Agent 不可能弄坏语料」这条结构性保证会
 * <strong>当场作废</strong>——{@code REPLACE} 模式立刻就能整体改写它们。</p>
 *
 * <p>所以引入第二类白名单：<strong>路径允许写，但仅当目标文件不存在</strong>。
 * 于是「新建一篇蒸馏草稿」可行，「改写一册既有检验」在结构上不可能。
 * 判断放在闸门里而不是各个服务里：服务会有多条写入路径，闸门只有一处，
 * 而这种规则一旦有一条路径漏判就等于没有。</p>
 */
@Component
public class DocWriteGuard {

    private static final Logger log = LoggerFactory.getLogger(DocWriteGuard.class);

    private static final Pattern FENCE = Pattern.compile("(?m)^\\s*(```|~~~)");
    private static final Pattern TABLE_SEP =
            Pattern.compile("(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$");

    /**
     * 判定结果。
     *
     * @param code    机器可判的拒绝原因码——让 Agent 能据此改正而不是重试同样的调用
     * @param message 给人看的说明
     * @param hint    怎么办
     */
    public record Decision(boolean allowed, String code, String message, String hint) {

        private static final Decision OK = new Decision(true, "OK", null, null);

        public static Decision ok() {
            return OK;
        }

        public static Decision deny(String code, String message, String hint) {
            return new Decision(false, code, message, hint);
        }
    }

    private final RepoRegistryService registry;
    private final GitClient git;

    @Value("${codex.write.enabled:false}")
    private boolean writeEnabled;

    @Value("${codex.write.allowed-paths:docs/notes/**/*.md}")
    private String allowedPathsRaw;

    /**
     * ★只准新建的路径（P4）。
     *
     * <p>默认只含蒸馏产物与检验册两个目录。它们<strong>不在</strong>
     * {@link #allowedPathsRaw} 里，所以既有文件永远无法被覆盖或追加。</p>
     *
     * <p>刻意<strong>不含</strong> {@code docs/learning-guides/}，尽管蒸馏官方文档时
     * 放那里更自然。理由是白名单能不加就不加：多一个目录，就多一批
     * 「只调了 {@code checkPath} 没调 {@code checkCreatable}」的写入路径需要逐个复核，
     * 而 P2 那句「既有 guide 一律不可写」的断言也就不再是逐字成立的了。
     * 蒸馏产物先落在 paper-notes，用户想挪到 learning-guides 时在 IDE 里移动一下——
     * 那本来就该是他的决定。</p>
     */
    @Value("${codex.write.create-only-paths:docs/paper-notes/**/*.md,docs/checkpoints/**/*.md}")
    private String createOnlyPathsRaw;

    @Value("${codex.write.branch-prefix:lattice/}")
    private String branchPrefix;

    @Value("${codex.write.max-note-chars:20000}")
    private int maxNoteChars;

    /**
     * 蒸馏产物的体积上限。
     *
     * <p>比笔记宽松得多：一篇 guide 本就该长（用户既有 guide 单篇 4 万~8 万字符）。
     * 用同一个上限会让蒸馏在最后一步被自己的门禁拒掉——那种失败最令人困惑，
     * 因为前面每一步都成功了。</p>
     */
    @Value("${codex.write.max-guide-chars:80000}")
    private int maxGuideChars;

    @Value("${codex.write.allow-on-default-branch:false}")
    private boolean allowOnDefaultBranch;

    private volatile List<Pattern> allowedPatterns;
    private volatile List<Pattern> createOnlyPatterns;

    public DocWriteGuard(RepoRegistryService registry, GitClient git) {
        this.registry = registry;
        this.git = git;
    }

    public boolean enabled() {
        return writeEnabled;
    }

    public String branchPrefix() {
        return branchPrefix == null || branchPrefix.isBlank() ? "lattice/" : branchPrefix;
    }

    public List<String> allowedPaths() {
        return splitGlobs();
    }

    public List<String> createOnlyPaths() {
        return splitCreateOnlyGlobs();
    }

    public int maxGuideChars() {
        return maxGuideChars;
    }

    /* ==================== 闸门 1：总开关 ==================== */

    public Decision checkEnabled() {
        if (!registry.enabled()) {
            return Decision.deny("CODEX_DISABLED",
                    "知识仓库功能未启用（codex.enabled=false）。", "在配置中开启后重启。");
        }
        if (!registry.operational()) {
            return Decision.deny("GIT_UNAVAILABLE",
                    "系统未安装 git 或不在 PATH 中。", "安装 git 后重启；当前检测："
                            + registry.gitVersion());
        }
        if (!writeEnabled) {
            return Decision.deny("WRITE_DISABLED",
                    "知识仓库写入未启用（codex.write.enabled=false）。",
                    "写入会真实修改用户的 git 工作副本，故默认关闭；确认后在配置中开启。");
        }
        return Decision.ok();
    }

    /* ==================== 闸门 2：路径沙箱 ==================== */

    /**
     * 校验目标路径。
     *
     * <p>四层依次收紧：语法（正斜杠、无 {@code ..}、必须 {@code .md}）→
     * 白名单 glob → 真实路径包含关系 → 体积上限。</p>
     *
     * <p>真实路径这一层不能省：字符串前缀比较会被符号链接绕出仓库外
     * （{@code docs/notes} 若是指向别处的 symlink，前缀判断仍会通过）。</p>
     */
    public Decision checkPath(KnowledgeRepo repo, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return Decision.deny("PATH_EMPTY", "路径为空。", "给出仓库内相对路径，如 docs/notes/x.md。");
        }
        String p = relPath.replace('\\', '/').strip();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) {
            return Decision.deny("PATH_EMPTY", "路径为空。", null);
        }
        if (relPath.contains("\u0000")) {
            return Decision.deny("PATH_ILLEGAL", "路径含非法字符。", null);
        }
        // 先规范化再检查 ..：直接查原串会漏掉 a/b/../../../etc 这类形式
        String normalized = RepoIndexer.normalizeSlashes(p);
        if (normalized.startsWith("..") || normalized.contains("/../")
                || normalized.equals("..") || Path.of(normalized).isAbsolute()) {
            return Decision.deny("PATH_ESCAPE", "路径试图越出仓库：" + relPath,
                    "只允许仓库内的相对路径。");
        }
        if (!normalized.toLowerCase().endsWith(".md")) {
            return Decision.deny("PATH_NOT_MARKDOWN", "只允许写 Markdown 文件：" + normalized,
                    "知识资产的权威形态是 Markdown；其他格式请手动放入仓库。");
        }
        if (!matchesAllowed(normalized) && !matchesCreateOnly(normalized)) {
            return Decision.deny("PATH_NOT_ALLOWED",
                    "路径不在写入白名单内：" + normalized,
                    "可覆盖写入：" + String.join("、", splitGlobs())
                            + "；仅可新建：" + String.join("、", splitCreateOnlyGlobs())
                            + "。既有 guide 只能通过 doc.insert_backref 插入一行速记引用，"
                            + "不允许整体改写——数十万字的语料一次幻觉就可能不可逆损失。");
        }

        // 真实路径包含性：对不存在的文件用其最近存在的祖先目录判断
        try {
            Path root = registry.rootOf(repo).toRealPath();
            Path target = root.resolve(normalized).normalize();
            Path probe = target;
            while (probe != null && !Files.exists(probe)) probe = probe.getParent();
            if (probe == null || !probe.toRealPath().startsWith(root)) {
                return Decision.deny("PATH_ESCAPE",
                        "解析后的真实路径不在仓库内：" + normalized,
                        "检查路径上是否存在指向仓库外的符号链接。");
            }
        } catch (Exception e) {
            return Decision.deny("PATH_RESOLVE_FAILED",
                    "无法解析路径：" + e.getMessage(), null);
        }
        return Decision.ok();
    }

    public Decision checkSize(String content) {
        return checkSize(content, maxNoteChars);
    }

    /**
     * 体积校验（指定上限）。
     *
     * <p>笔记与 guide 用不同上限：把 guide 卡在笔记的 2 万字符上，
     * 会让蒸馏在最后一步被自己的门禁拒掉。</p>
     */
    public Decision checkSize(String content, int maxChars) {
        if (content == null) {
            return Decision.deny("CONTENT_EMPTY", "内容为空。", null);
        }
        if (content.strip().isEmpty()) {
            return Decision.deny("CONTENT_EMPTY", "内容为空。",
                    "空文件不是有效的知识资产。");
        }
        if (content.length() > maxChars) {
            return Decision.deny("CONTENT_TOO_LARGE",
                    "内容 " + content.length() + " 字符，超过上限 " + maxChars,
                    "笔记应当短：砍的是空话与重复，不是示例。"
                            + "若确实需要长文，它更可能是一篇 guide 而非笔记，请手动创建。");
        }
        return Decision.ok();
    }

    /* ============ 闸门 2b：只准新建的路径不得覆盖既有文件（★P4） ============ */

    /**
     * 校验「这次写入是否被允许落在这个已存在/不存在的文件上」。
     *
     * <p>与 {@link #checkPath} 的分工是刻意的：{@code checkPath} 只判路径本身合法
     * （提交流程需要它，而提交时文件当然已经存在），本方法判<strong>覆盖权</strong>。
     * 把两件事塞进一个方法，会让 {@code commit} 在校验自己刚写好的文件时被拒。</p>
     *
     * @param overwrite 本次写入是否会改动既有内容（APPEND 也算——它同样在改既有文件）
     */
    public Decision checkCreatable(KnowledgeRepo repo, String relPath, boolean overwrite) {
        String normalized = RepoIndexer.normalizeSlashes(
                relPath == null ? "" : relPath.replace('\\', '/').strip());
        if (normalized.isEmpty()) {
            return Decision.deny("PATH_EMPTY", "路径为空。", null);
        }
        boolean createOnly = matchesCreateOnly(normalized) && !matchesAllowed(normalized);
        if (!createOnly) return Decision.ok();

        if (overwrite) {
            return Decision.deny("OVERWRITE_FORBIDDEN",
                    "该路径只允许新建，不允许覆盖或追加：" + normalized,
                    "蒸馏产物与检验册目录里已有你手写的内容。"
                            + "让机器有能力改写它们，收益是省几分钟排版，"
                            + "风险是一次幻觉造成不可逆损失。"
                            + "要修订既有文件请在 IDE 里手动改。");
        }
        try {
            Path target = registry.rootOf(repo).resolve(normalized);
            if (Files.exists(target)) {
                return Decision.deny("FILE_EXISTS_PROTECTED",
                        "文件已存在且该路径只允许新建：" + normalized,
                        "换一个文件名（例如加上论文年份或版本），"
                            + "或在 IDE 里手动把新内容并入既有文件——"
                            + "由你决定怎么合并，比机器替你决定安全。");
            }
        } catch (Exception e) {
            return Decision.deny("PATH_RESOLVE_FAILED",
                    "无法判断文件是否存在：" + e.getMessage(), null);
        }
        return Decision.ok();
    }

    /* ==================== 闸门 3：示例入库（★执行层强制） ==================== */

    /**
     * 校验示例是否被保留。
     *
     * <p>判据是<strong>按类别对齐</strong>而非「有就行」：源里出现代码块，成品也必须有代码块；
     * 源里出现对照表，成品至少要有表格或代码块之一。</p>
     *
     * <p>为什么不简单地要求「成品含任一示例」：那样模型可以用一个无关的短代码块
     * 蒙过检查，却把真正的对照表丢掉。按类别对齐会让偷懒的成本高于照做的成本。</p>
     *
     * @param sourceExcerpt 被认可的原始问答内容（权威来源应由服务端从会话取，而非模型自述）
     * @param body          待写入的笔记正文
     */
    public Decision checkExamples(String sourceExcerpt, String body) {
        if (sourceExcerpt == null || sourceExcerpt.isBlank()) {
            return Decision.deny("MISSING_SOURCE",
                    "未提供被沉淀的原始内容，无法校验示例是否保留。",
                    "沉淀的对象必须是用户已经认可的那次回答。"
                            + "请通过「沉淀这段」入口发起（服务端会从会话取原文），"
                            + "或在调用时提供 sourceExcerpt。");
        }
        boolean srcFence = FENCE.matcher(sourceExcerpt).find();
        boolean srcTable = TABLE_SEP.matcher(sourceExcerpt).find();
        if (!srcFence && !srcTable) return Decision.ok();      // 源里本就没有示例

        String b = body == null ? "" : body;
        boolean bodyFence = FENCE.matcher(b).find();
        boolean bodyTable = TABLE_SEP.matcher(b).find();

        if (srcFence && !bodyFence) {
            return Decision.deny("MISSING_EXAMPLES",
                    "原始问答含代码块，但待写入的笔记正文没有任何代码块——示例被丢弃了。",
                    "把对话中用过的具体 IR / 代码 / 对象树原样写入笔记（可微剪，勿换题重写）。"
                            + "示例正是让人半年后重新看懂的部分，"
                            + "「保持简短」不能作为丢掉示例的理由。");
        }
        if (srcTable && !bodyTable && !bodyFence) {
            return Decision.deny("MISSING_EXAMPLES",
                    "原始问答含对照表，但待写入的笔记正文既无表格也无代码块。",
                    "易混概念的并排对照不能缩成一句抽象结论——对照本身就是知识。");
        }
        return Decision.ok();
    }

    /* ==================== 闸门 4：分支与工作副本 ==================== */

    /**
     * 绝不向默认分支写。
     *
     * <p>这条是执行层校验而非约定：默认分支上的提交不经审阅就进入历史，
     * 而 Agent 产出的内容<strong>必须先被人看过</strong>。
     * 分支 + PR 提供的正是这个天然的审阅位。</p>
     */
    public Decision checkBranch(KnowledgeRepo repo) {
        String current;
        try {
            current = git.currentBranch(registry.rootOf(repo));
        } catch (Exception e) {
            return Decision.deny("BRANCH_UNKNOWN",
                    "无法确定当前分支：" + e.getMessage(), null);
        }
        String def = repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch();
        if (current.equals(def) && !allowOnDefaultBranch) {
            return Decision.deny("ON_DEFAULT_BRANCH",
                    "当前在默认分支 " + def + " 上，禁止直接写入。",
                    "先创建工作分支（repo.branch 或沉淀流程会自动创建 "
                            + branchPrefix() + "… 分支），改动才有审阅位。");
        }
        if (!current.startsWith(branchPrefix()) && !allowOnDefaultBranch) {
            // 只警示不拒绝：用户可能有意在自己的分支上让 Agent 协助整理
            log.info("[Codex] 当前分支「{}」不属于 {} 命名空间，写入仍允许但不会自动清理",
                    current, branchPrefix());
        }
        return Decision.ok();
    }

    /**
     * 工作副本必须干净（本次沉淀自己产生的改动除外）。
     *
     * <p>刻意<strong>不做自动 stash</strong>。stash 会把用户正在编辑、尚未想清楚的内容
     * 挪进一个他不知道存在的地方；等他发现文件"变回去了"时，
     * 第一反应是软件弄丢了他的修改。数据安全优先于便利。</p>
     *
     * @param ownedPaths 本次流程自己写过的路径，这些脏文件属预期
     */
    public Decision checkWorkingTree(KnowledgeRepo repo, Set<String> ownedPaths) {
        GitClient.WorkingStatus st;
        try {
            st = git.status(registry.rootOf(repo));
        } catch (Exception e) {
            return Decision.deny("STATUS_FAILED",
                    "无法读取 git 状态：" + e.getMessage(), null);
        }
        if (st.clean()) return Decision.ok();

        Set<String> owned = ownedPaths == null ? Set.of() : ownedPaths;
        List<String> foreign = new ArrayList<>();
        for (String p : st.dirtyPaths()) {
            String norm = p.replace('\\', '/');
            if (!owned.contains(norm)) foreign.add(norm);
        }
        if (foreign.isEmpty()) return Decision.ok();

        return Decision.deny("WORKTREE_DIRTY",
                "工作副本有未提交改动（" + foreign.size() + " 个文件），拒绝写入。",
                "请先自行提交或撤销这些改动：" + String.join("、",
                        foreign.subList(0, Math.min(5, foreign.size())))
                        + (foreign.size() > 5 ? " 等" : "")
                        + "。本软件刻意不自动 stash——把你正在编辑的内容挪到你不知道的地方，"
                        + "比拒绝执行糟糕得多。");
    }

    /* ==================== 内部 ==================== */

    private boolean matchesAllowed(String normalized) {
        for (Pattern p : patterns()) {
            if (p.matcher(normalized).matches()) return true;
        }
        return false;
    }

    private boolean matchesCreateOnly(String normalized) {
        for (Pattern p : createOnlyPatternList()) {
            if (p.matcher(normalized).matches()) return true;
        }
        return false;
    }

    private List<Pattern> patterns() {
        List<Pattern> cached = allowedPatterns;
        if (cached != null) return cached;
        List<Pattern> built = new ArrayList<>();
        for (String glob : splitGlobs()) {
            built.add(RepoLayout.globToPattern(glob));
        }
        allowedPatterns = built;
        return built;
    }

    private List<Pattern> createOnlyPatternList() {
        List<Pattern> cached = createOnlyPatterns;
        if (cached != null) return cached;
        List<Pattern> built = new ArrayList<>();
        for (String glob : splitCreateOnlyGlobs()) {
            built.add(RepoLayout.globToPattern(glob));
        }
        createOnlyPatterns = built;
        return built;
    }

    private List<String> splitGlobs() {
        return splitRaw(allowedPathsRaw, "docs/notes/**/*.md");
    }

    private List<String> splitCreateOnlyGlobs() {
        // 留空是有意义的配置：表示「关掉蒸馏写入」，而不是回落到默认值
        if (createOnlyPathsRaw == null || createOnlyPathsRaw.isBlank()) return List.of();
        return splitRaw(createOnlyPathsRaw, "");
    }

    private List<String> splitRaw(String raw, String fallback) {
        String s = (raw == null || raw.isBlank()) ? fallback : raw;
        Set<String> out = new LinkedHashSet<>();
        for (String part : Arrays.asList(s.split(","))) {
            String g = part.strip();
            if (!g.isEmpty()) out.add(g);
        }
        return new ArrayList<>(out);
    }
}
