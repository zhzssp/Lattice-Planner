package org.zhzssp.memorandum.feature.codex.index;

import org.zhzssp.memorandum.feature.codex.entity.KbDocument.DocKind;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 仓库布局配置：路径 glob → 文档 kind 的映射。
 *
 * <h3>为什么不硬编码目录名</h3>
 * <p>硬编码 {@code docs/learning-guides/} 之类的路径会让产品只能服务一种仓库结构。
 * 工作仓库的布局必然不同（{@code design/} / {@code runbook/} / {@code postmortem/}），
 * 学习仓库之间也会有差异。映射写进 {@code .lattice/repo.yml} 才能做到「适配用户，而非要求用户适配」。</p>
 *
 * <h3>为什么有内置默认值</h3>
 * <p>这是「零改动接入」的关键：目标仓库当前<strong>没有</strong> {@code .lattice/repo.yml}，
 * 内置默认规则必须能正确识别它的既有结构。要求用户先写配置文件才能用，
 * 等于把第一步门槛抬到「先学一套 schema」——大多数人到这一步就放弃了。</p>
 */
public class RepoLayout {

    /** 一条映射规则。 */
    public record Rule(String glob, Pattern pattern, DocKind kind, String subkind) {

        public boolean matches(String relativePath) {
            return pattern.matcher(relativePath).matches();
        }
    }

    private final List<Rule> rules;
    private final List<Pattern> excludes;
    private final int maxChunksPerDocument;
    private final boolean sectionAware;

    public RepoLayout(List<Rule> rules, List<Pattern> excludes,
                      int maxChunksPerDocument, boolean sectionAware) {
        this.rules = rules;
        this.excludes = excludes;
        this.maxChunksPerDocument = maxChunksPerDocument;
        this.sectionAware = sectionAware;
    }

    /**
     * 内置默认布局。
     *
     * <p>规则顺序即优先级（首个匹配生效），所以精确规则必须排在
     * {@code **&#47;*.md} 兜底规则之前。</p>
     */
    public static RepoLayout defaults(int maxChunksPerDocument, boolean sectionAware) {
        List<Rule> rs = new ArrayList<>();
        rs.add(rule("docs/learning-guides/**/*.md", DocKind.GUIDE, null));
        rs.add(rule("docs/paper-notes/**/*.md", DocKind.GUIDE, "paper-note"));
        rs.add(rule("docs/notes/**/*.md", DocKind.NOTE, null));
        rs.add(rule("docs/checkpoints/**/*.md", DocKind.CHECKPOINT_SET, null));
        rs.add(rule("docs/README.md", DocKind.ROADMAP, null));
        rs.add(rule("README.md", DocKind.ROADMAP, null));
        rs.add(rule("**/*-lab/README.md", DocKind.LAB, null));
        rs.add(rule("*-lab/README.md", DocKind.LAB, null));
        rs.add(rule("**/*-compile/README.md", DocKind.LAB, null));
        rs.add(rule("**/*-dialect/README.md", DocKind.LAB, null));
        rs.add(rule("paper/**/*.pdf", DocKind.SOURCE, null));
        rs.add(rule("**/*.md", DocKind.UNKNOWN, null));

        List<Pattern> ex = new ArrayList<>();
        // lab 产物：内容是运行输出而非知识，且体积大、变动频繁
        ex.add(globToPattern("**/out/**"));
        ex.add(globToPattern("**/.venv/**"));
        ex.add(globToPattern("**/node_modules/**"));
        ex.add(globToPattern("**/build/**"));
        ex.add(globToPattern("**/.git/**"));
        ex.add(globToPattern("**/__pycache__/**"));
        return new RepoLayout(rs, ex, maxChunksPerDocument, sectionAware);
    }

    private static Rule rule(String glob, DocKind kind, String subkind) {
        return new Rule(glob, globToPattern(glob), kind, subkind);
    }

    /** 是否应被索引排除。 */
    public boolean excluded(String relativePath) {
        for (Pattern p : excludes) {
            if (p.matcher(relativePath).matches()) return true;
        }
        return false;
    }

    /** 解析文档 kind；无规则命中返回 null（表示不索引）。 */
    public Rule resolve(String relativePath) {
        for (Rule r : rules) {
            if (r.matches(relativePath)) return r;
        }
        return null;
    }

    public int maxChunksPerDocument() {
        return maxChunksPerDocument;
    }

    public boolean sectionAware() {
        return sectionAware;
    }

    public List<Rule> rules() {
        return rules;
    }

    /**
     * glob → 正则。
     *
     * <p>刻意不使用 {@code FileSystems.getDefault().getPathMatcher("glob:...")}：
     * 它在 Windows 上对 {@code /} 分隔符的处理与 Linux 不一致，而我们统一以正斜杠
     * 相对路径为规范化形式，需要跨平台完全一致的匹配语义。</p>
     *
     * <p>支持的语法：{@code **}（跨目录）、{@code *}（单层内任意）、{@code ?}（单字符）。</p>
     */
    public static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                    if (doubleStar) {
                        boolean slashAfter = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                        if (slashAfter) {
                            // "**/" 应能匹配零级目录：a/**/b.md 既匹配 a/b.md 也匹配 a/x/y/b.md
                            sb.append("(?:.*/)?");
                            i += 3;
                        } else {
                            sb.append(".*");
                            i += 2;
                        }
                    } else {
                        sb.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> {
                    sb.append("[^/]");
                    i++;
                }
                case '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                    sb.append('\\').append(c);
                    i++;
                }
                default -> {
                    sb.append(c);
                    i++;
                }
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }
}
