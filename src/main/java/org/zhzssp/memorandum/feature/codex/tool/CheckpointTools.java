package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointRunner;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointService;
import org.zhzssp.memorandum.feature.codex.verify.CommandGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识落地检验工具集（P1 验证闭环）。
 *
 * <h3>刻意不存在的工具：{@code checkpoint.predict}</h3>
 * <p>这是本类最重要的设计。预测<strong>只能由用户经 HTTP 表单提交</strong>，
 * Agent 没有任何写入预测的通道。</p>
 *
 * <p>理由：如果给 Agent 一个提交预测的工具，它一定会"贴心地"帮用户预测
 * ——毕竟从模型视角看这是在帮忙。但整个「先预测再动手」机制的价值就在于
 * <em>暴露用户自己的心智模型</em>，由 AI 代填等于把这个机制彻底掏空。</p>
 *
 * <p>同理，{@code checkpoint.grade} 只允许提交 {@code AI} 裁判的判定，
 * 且判定结果会标注来源，用户可覆盖。</p>
 *
 * <h3>可见性</h3>
 * <p>{@code checkpoint.run} 带 {@code exec} tag，仅在 {@code AgentMode.VERIFY} 可见
 * （由方案 K 的执行层强制保证，不是仅从 prompt 隐藏）。
 * 且它在 {@code ToolApprovalPolicy} 中被硬编码禁止加入 auto-approve 白名单。</p>
 */
@Component
public class CheckpointTools {

    private final CheckpointService service;
    private final RepoRegistryService registry;
    private final CommandGuard guard;

    public CheckpointTools(CheckpointService service,
                           RepoRegistryService registry,
                           CommandGuard guard) {
        this.service = service;
        this.registry = registry;
        this.guard = guard;
    }

    @AgentTool(name = "checkpoint.list", tags = {"checkpoint", "read"},
            description = "列出知识落地检验条目（L0 复现 / L1 改一处 / L2 加组件 / L3 打通）及其状态。" +
                    "用户问「我该验证什么/我的检验进度/哪些还没做」时调用。" +
                    "status 含义：TODO=未填预测，PREDICTED=已填预测可运行，" +
                    "PASSED=通过，DEGRADED=通过但输出被截断判定强度低，FAILED=失败。")
    public List<Map<String, Object>> list(
            @ToolParam(value = "level", desc = "只看某一级：L0/L1/L2/L3；省略则全部") String level,
            @ToolParam(value = "status", desc = "只看某状态：TODO/PREDICTED/PASSED/FAILED；省略则全部") String status
    ) {
        User u = AgentContext.requireUser();
        if (!service.enabled()) {
            return List.of(Map.of("error", "VERIFY_DISABLED",
                    "message", "验证闭环未启用（codex.verify.enabled=false）。"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbCheckpoint cp : service.list(u.getId(), null)) {
            if (level != null && !level.isBlank()
                    && !cp.getLevel().name().equalsIgnoreCase(level.strip())) {
                continue;
            }
            if (status != null && !status.isBlank()
                    && !cp.getStatus().name().equalsIgnoreCase(status.strip())) {
                continue;
            }
            out.add(brief(cp));
        }
        if (out.isEmpty()) {
            return List.of(Map.of("_meta", "checkpoint", "count", 0,
                    "message", "没有匹配的检验条目。若知识仓库里有 docs/checkpoints/ 检验册，"
                            + "可先同步（repo.sync 后会自动解析）。"));
        }
        return out;
    }

    @AgentTool(name = "checkpoint.next", tags = {"checkpoint", "read"},
            description = "给出「下一条该做的检验」（按 L0→L3 顺序取首个未完成条目），" +
                    "并说明它检验什么、需要什么资源、预计耗时、以及是否已填预测。" +
                    "用户问「我接下来该做什么/下一步验证什么」时调用。")
    public Map<String, Object> next() {
        User u = AgentContext.requireUser();
        if (!service.enabled()) {
            return Map.of("error", "VERIFY_DISABLED", "message", "验证闭环未启用。");
        }
        KbCheckpoint cp = service.next(u.getId(), null).orElse(null);
        if (cp == null) {
            return Map.of("_meta", "checkpoint",
                    "message", "没有待完成的检验条目——要么全部通过了，要么还没同步检验册。");
        }
        Map<String, Object> m = detailOf(cp);
        if (!cp.predictionSatisfied()) {
            // 关键：明确告知模型「不能代替用户预测」
            m.put("_actionRequired", "PREDICTION");
            m.put("_hint", "该条尚未填写预测，运行验收会被拒绝。"
                    + "请把「先预测再动手」的提问转达给用户，让用户在 Checkpoint 面板自行填写。"
                    + "⚠ 你不得代替用户预测——这个机制的意义正是暴露用户自己的心智模型，"
                    + "由你代填等于让它失效。");
        } else {
            m.put("_actionRequired", "RUN");
            m.put("_hint", "预测已冻结，可以调用 checkpoint.run 执行验收（需用户确认）。");
        }
        return m;
    }

    @AgentTool(name = "checkpoint.detail", tags = {"checkpoint", "read"},
            description = "查看某条检验的完整信息：检验什么、任务、预测提问、验收命令、通过标准、" +
                    "常见失败→盲点映射、历史运行记录。")
    public Map<String, Object> detail(
            @ToolParam(value = "code", desc = "检验编号，如 L2-MLIR-04", required = true) String code
    ) {
        User u = AgentContext.requireUser();
        if (!service.enabled()) {
            return Map.of("error", "VERIFY_DISABLED", "message", "验证闭环未启用。");
        }
        KbCheckpoint cp = service.byCode(u.getId(), code).orElse(null);
        if (cp == null) {
            return Map.of("error", "NOT_FOUND", "code", code,
                    "message", "未找到该编号，可先 checkpoint.list 查看全部条目。");
        }
        Map<String, Object> m = detailOf(cp);
        List<Map<String, Object>> runs = new ArrayList<>();
        service.runsOf(cp.getId()).stream().limit(5).forEach(r -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("startedAt", r.getStartedAt() == null ? null : r.getStartedAt().toString());
            x.put("passed", r.getPassed());
            x.put("exitCode", r.getExitCode());
            x.put("durationMs", r.getDurationMs());
            if (Boolean.TRUE.equals(r.getTimedOut())) x.put("timedOut", true);
            if (r.getRejectReason() != null) x.put("rejectReason", r.getRejectReason());
            runs.add(x);
        });
        if (!runs.isEmpty()) m.put("recentRuns", runs);
        return m;
    }

    @AgentTool(name = "checkpoint.run", tags = {"checkpoint", "exec"}, requiresConfirm = true,
            description = "执行某条检验的验收命令并自动判定结果。" +
                    "⚠ 这会在用户机器上真实执行命令，必须经用户确认。" +
                    "前置硬约束：该条必须已由【用户本人】填写预测，否则返回 PREDICTION_REQUIRED。" +
                    "命令须通过白名单与路径沙箱校验（只允许仓库内被 git 跟踪的脚本）。" +
                    "失败时返回 blindSpotHint，据此告诉用户该回看哪个知识点。")
    public Map<String, Object> run(
            @ToolParam(value = "code", desc = "检验编号，如 L0-MLIR-01", required = true) String code
    ) {
        User u = AgentContext.requireUser();
        if (!service.enabled()) {
            return Map.of("error", "VERIFY_DISABLED",
                    "message", "验证闭环未启用（codex.verify.enabled=false）。");
        }
        KbCheckpoint cp = service.byCode(u.getId(), code).orElse(null);
        if (cp == null) {
            return Map.of("error", "NOT_FOUND", "code", code);
        }

        // requiresConfirm=true 意味着到这里已经过用户确认弹窗
        CheckpointService.RunResult r = service.run(u.getId(), cp.getId(), true);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", cp.getCode());
        if (r.rejectReason() != null) {
            m.put("error", "COMMAND_REJECTED");
            m.put("reason", r.rejectReason());
            m.put("hint", "命令未通过安全闸门，未执行，无任何副作用。"
                    + "请向用户说明原因；若确需执行，用户可手动在终端运行。");
            return m;
        }
        if (!r.executed()) {
            m.put("error", firstToken(r.error()));
            m.put("message", r.error());
            return m;
        }

        m.put("passed", r.passed());
        m.put("exitCode", r.exitCode());
        m.put("durationMs", r.durationMs());
        m.put("cmd", r.cmd());
        if (r.timedOut()) m.put("timedOut", true);
        if (r.truncated()) {
            m.put("outputTruncated", true);
            m.put("_warning", "输出被截断，涉及内容匹配的断言不可靠，判定强度下降。");
        }
        if (r.details() != null && !r.details().isEmpty()) {
            List<Map<String, Object>> ds = new ArrayList<>();
            for (CheckpointRunner.ExpectResult d : r.details()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("kind", d.kind());
                x.put("expected", d.expected());
                x.put("actual", d.actual());
                x.put("passed", d.passed());
                ds.add(x);
            }
            m.put("assertions", ds);
        }
        m.put("stdoutTail", tail(r.stdoutExcerpt(), 1200));
        if (r.stderrExcerpt() != null && !r.stderrExcerpt().isBlank()) {
            m.put("stderrTail", tail(r.stderrExcerpt(), 800));
        }
        if (r.blindSpotHint() != null) {
            m.put("blindSpotHint", r.blindSpotHint());
        }

        if (Boolean.TRUE.equals(r.passed())) {
            // 通过之后最该做的事不是庆祝，而是核对预测
            m.put("_nextStep", "PREDICTION_CHECK");
            m.put("_hint", "验收通过。接下来请把用户当初的预测（prediction 字段）与实际结果对比，"
                    + "判断二者是否一致，然后调用 checkpoint.grade 记录。"
                    + "若不一致——即「结果对但预测错」——这是最有价值的信号，"
                    + "说明因果理解有偏差，应建议用户沉淀一篇「我原以为…实际…」的笔记。");
            m.put("userPrediction", cp.getPrediction());
        } else {
            m.put("_nextStep", "DIAGNOSE");
            m.put("_hint", "验收失败。请结合 assertions、stderrTail 与 blindSpotHint，"
                    + "指出最可能是哪个知识点没掌握，并给出回看建议。不要泛泛地说「再试一次」。");
        }
        return m;
    }

    @AgentTool(name = "checkpoint.grade", tags = {"checkpoint", "read"},
            description = "记录「用户的预测是否与实际结果一致」。仅在 checkpoint.run 通过后调用。" +
                    "correct=false 表示【结果对但预测错】——这是最有价值的信号，" +
                    "须同时给出 divergence（我原以为X，实际Y）并建议用户沉淀笔记。" +
                    "本判定会被标注为 AI 裁判，用户可在面板覆盖。")
    public Map<String, Object> grade(
            @ToolParam(value = "code", desc = "检验编号", required = true) String code,
            @ToolParam(value = "correct", desc = "预测是否与实际一致", required = true) Boolean correct,
            @ToolParam(value = "divergence", desc = "不一致时说明：用户原以为什么，实际是什么") String divergence
    ) {
        User u = AgentContext.requireUser();
        if (!service.enabled()) {
            return Map.of("error", "VERIFY_DISABLED", "message", "验证闭环未启用。");
        }
        KbCheckpoint cp = service.byCode(u.getId(), code).orElse(null);
        if (cp == null) {
            return Map.of("error", "NOT_FOUND", "code", code);
        }
        if (Boolean.FALSE.equals(correct) && (divergence == null || divergence.isBlank())) {
            // 判"预测错"却说不出差异在哪，这个判定就没有复盘价值
            return Map.of("error", "DIVERGENCE_REQUIRED",
                    "message", "判定预测错误时必须给出 divergence，说明用户原以为什么、实际是什么。"
                            + "否则这条记录无法用于复盘。");
        }
        CheckpointService.JudgeResult jr = service.recordPredictionJudgement(
                u.getId(), cp.getId(), correct, divergence,
                KbCheckpoint.PredictionJudge.AI);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", cp.getCode());
        m.put("recorded", jr.recorded());
        m.put("message", jr.message());
        if (!jr.recorded()) m.put("error", "JUDGE_REJECTED");
        if (Boolean.FALSE.equals(correct) && jr.recorded()) {
            m.put("_suggestion", "建议向用户提议：把这次的「我原以为…实际…」沉淀为一篇笔记。"
                    + "这类记录是最难得的学习素材——它记下的是心智模型被修正的瞬间。");
        }
        return m;
    }

    @AgentTool(name = "checkpoint.stats", tags = {"checkpoint", "read"},
            description = "统计检验进度：通过率、L2 通过数（主判据）、预测准确率。" +
                    "用户问「我学得怎么样/有什么证据说明我掌握了」时调用。" +
                    "注意：L2 是主判据，入门线是每个工具至少两条 L2 通过——" +
                    "只报总通过数会高估掌握程度。")
    public Map<String, Object> stats(
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        if (!service.enabled()) {
            return Map.of("error", "VERIFY_DISABLED", "message", "验证闭环未启用。");
        }
        KnowledgeRepo repo = resolveRepo(u, repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到仓库，可先 repo.list。");
        }
        Map<String, Object> m = new LinkedHashMap<>(service.stats(repo.getId()));
        m.put("repo", repo.getName());
        m.put("_note", "predictionAccuracy 的分母只含已判定的条目；"
                + "AI 判定自然语言一致性会有误判，用户可在面板覆盖。"
                + "引用这个数字时应说明判定来源。");
        return m;
    }

    @AgentTool(name = "checkpoint.guard_info", tags = {"checkpoint", "read"},
            description = "查看受限执行的安全策略：可执行文件白名单、沙箱规则。" +
                    "用户问「它能执行什么命令/安不安全」时调用。")
    public Map<String, Object> guardInfo() {
        AgentContext.requireUser();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowedExecutables", guard.allowedExecutablesList());
        m.put("rules", List.of(
                "仅在 verify 思维模式下可用（其余模式执行层直接拦截）",
                "禁止 shell 元字符：管道 | 重定向 > < 串联 && ; 反引号 变量替换 $()",
                "可执行文件必须是白名单内的程序名，不得带路径",
                "工作目录与脚本必须在仓库内（解析符号链接后比对）",
                "脚本必须已被 git 跟踪——只跑纳入版本控制、可审计的脚本",
                "每次执行需用户确认，且不允许加入 auto-approve 白名单",
                "超时强制终止，输出上限 8KB 且截断必标记，环境变量按白名单透传"
        ));
        m.put("isolation", "无容器隔离。真实风险模型等价于「用户自己在终端敲这条命令」——"
                + "在其本机、经其逐次确认、跑其自己仓库里被 git 跟踪的脚本。如实告知用户这一点。");
        return m;
    }

    /* ---------------- 内部 ---------------- */

    private KnowledgeRepo resolveRepo(User u, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(u.getId(), repoName.strip()).orElse(null);
        }
        List<KnowledgeRepo> all = registry.listEnabled(u.getId());
        return all.isEmpty() ? null : all.get(0);
    }

    private Map<String, Object> brief(KbCheckpoint cp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", cp.getCode());
        m.put("level", cp.getLevel().name());
        m.put("title", cp.getTitle());
        m.put("status", cp.getStatus().name());
        if (cp.getResourceTag() != null) m.put("resource", cp.getResourceTag());
        if (cp.getPredictedAt() != null) m.put("predicted", true);
        if (cp.getPredictionCorrect() != null) {
            m.put("predictionCorrect", cp.getPredictionCorrect());
        }
        return m;
    }

    /** 详情视图构造。与 {@code detail(String)} 工具方法区分命名，避免重载引起误读。 */
    private Map<String, Object> detailOf(KbCheckpoint cp) {
        Map<String, Object> m = new LinkedHashMap<>(brief(cp));
        if (cp.getChecksWhat() != null) m.put("checksWhat", cp.getChecksWhat());
        if (cp.getPrerequisite() != null) m.put("prerequisite", cp.getPrerequisite());
        if (cp.getEstHours() != null) m.put("estHours", cp.getEstHours());
        if (cp.getLab() != null) m.put("lab", cp.getLab());
        if (cp.getPredictionQuestions() != null) {
            m.put("predictionQuestions", cp.getPredictionQuestions());
        }
        if (cp.getPassCriteria() != null) m.put("passCriteria", cp.getPassCriteria());
        if (cp.getPrediction() != null) m.put("userPrediction", cp.getPrediction());
        if (cp.getVerifyJson() != null) m.put("verify", cp.getVerifyJson());
        m.put("verifySource", cp.getVerifySource().name());
        if (cp.getVerifySource() == KbCheckpoint.VerifySource.PARSED) {
            // 如实说明判据强度：不假装精确
            m.put("_verifyNote", "该条判据由原文 Markdown 解析推断，通常只能判「退出码为 0」，"
                    + "强度弱于声明式断言。机器判定通过不等于内容完全正确，"
                    + "仍应对照 passCriteria 人工核对。");
        }
        return m;
    }

    private String firstToken(String s) {
        if (s == null) return "ERROR";
        int i = s.indexOf('：');
        return i > 0 ? s.substring(0, i) : s;
    }

    private String tail(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.length() <= max) return t;
        return "...(前段省略)...\n" + t.substring(t.length() - max);
    }
}
