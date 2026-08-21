package org.zhzssp.memorandum.feature.codex.ci;

import java.util.List;

/**
 * 知识 CI 的结果模型。
 *
 * <h3>为什么知识需要 CI</h3>
 * <p>用户的方法论里有几条硬约束（笔记必须挂回 guide、示例必须入库、引用必须可达），
 * 但它们目前<strong>全靠人自觉</strong>：漏挂了没人知道，链接断了 IDE 不报错、
 * 编译不报错、渲染不报错，只有点击时才 404。语料到千级链接之后，人工核对不可行。</p>
 *
 * <p>CI 把这些约定从「自觉」变成「门禁」。这是本方案里少数
 * <em>纯机器可判、零主观</em>的部分——也正因如此，它是最先该做的。</p>
 *
 * <h3>三级严重度的划分标准</h3>
 * <ul>
 *   <li>{@link Severity#ERROR}：<strong>已经坏了</strong>。链接点不开、笔记检索不到，
 *       是确定性的功能损失，不存在「见解不同」的空间。</li>
 *   <li>{@link Severity#WARN}：<strong>可能坏</strong>或违反约定但不影响可达性。</li>
 *   <li>{@link Severity#INFO}：<strong>可改进</strong>。缺 front-matter 属于这一档——
 *       现存语料一篇都没写，若判成 ERROR 会在第一天刷出满屏红色，
 *       而<em>满屏红色的 CI 等于没有 CI</em>，用户会直接无视它。</li>
 * </ul>
 */
public final class CiCheck {

    public enum Severity { ERROR, WARN, INFO }

    /** 单项检查的执行结果状态。 */
    public enum Status {
        /** 跑了，无问题。 */
        OK,
        /** 跑了，有发现。 */
        FINDINGS,
        /**
         * 前提不满足，未跑。
         *
         * <p>刻意与 OK 区分：把「没检查」显示成「通过」是最糟的设计——
         * 用户会以为已经验过了。{@code skipReason} 必须说清缺什么。</p>
         */
        SKIPPED,
        /** 检查自身抛异常（不应让单项失败拖垮整轮）。 */
        FAILED
    }

    /** 九项检查。 */
    public enum CheckId {
        DEAD_LINK("死链", "相对链接指向的文件不存在"),
        DEAD_ANCHOR("锚点失效", "文件存在但章节 anchor 不存在——改标题的典型后果"),
        BACKREF_BIDIRECTIONAL("引用双向性", "笔记声明的来源 guide 里必须存在指回它的速记链接"),
        NOTE_EXAMPLES("示例入库", "笔记正文必须保留问答中的代码/IR/对照表，不得压成一句摘要"),
        FRONT_MATTER("元数据", "front-matter 语法与枚举取值校验"),
        SCOPE_DANGLING("止损线悬空", "scope.must / scope.skip 引用了未定义的知识点"),
        CHECKPOINT_EXECUTABLE("检验可执行", "验收命令引用的脚本存在且被 git 跟踪"),
        PROTAGONIST_CONSISTENCY("主角一致性", "声明 protagonist 的文档，其数值须与声明文件一致"),
        ORPHAN_DOC("孤岛文档", "没有任何入链的文档——内容再好也很难被再次找到");

        private final String label;
        private final String description;

        CheckId(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }
    }

    /**
     * 一条发现。
     *
     * @param path    出问题的文档相对路径（可能为 null，表示仓库级发现）
     * @param line    行号（1-based，未知为 null）——报错必须能定位，否则用户无从下手
     * @param message 说明「哪里不对」
     * @param hint    说明「怎么改」。延续项目「错误必须可操作」的立场
     */
    public record Finding(CheckId check, Severity severity, String path, Integer line,
                          String message, String hint) {

        public static Finding error(CheckId c, String path, Integer line, String msg, String hint) {
            return new Finding(c, Severity.ERROR, path, line, msg, hint);
        }

        public static Finding warn(CheckId c, String path, Integer line, String msg, String hint) {
            return new Finding(c, Severity.WARN, path, line, msg, hint);
        }

        public static Finding info(CheckId c, String path, Integer line, String msg, String hint) {
            return new Finding(c, Severity.INFO, path, line, msg, hint);
        }

        /** 「文件:行」定位串，便于直接粘到编辑器跳转。 */
        public String locator() {
            if (path == null) return "(repo)";
            return line == null ? path : path + ":" + line;
        }
    }

    /** 单项检查结果。 */
    public record CheckResult(CheckId check, Status status, String skipReason,
                              List<Finding> findings, int scanned, long durationMs) {

        public long errors() {
            return findings.stream().filter(f -> f.severity() == Severity.ERROR).count();
        }

        public long warns() {
            return findings.stream().filter(f -> f.severity() == Severity.WARN).count();
        }

        public long infos() {
            return findings.stream().filter(f -> f.severity() == Severity.INFO).count();
        }
    }

    /**
     * 一轮 CI 报告。
     *
     * @param passed 是否放行。<strong>只看 ERROR</strong>：
     *               WARN/INFO 不阻塞，否则 CI 很快会被用户当噪音关掉
     */
    public record Report(Long repoId, String repoName, List<CheckResult> checks,
                         int errors, int warns, int infos, boolean passed, long durationMs) {}

    private CiCheck() {}
}
