package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.entity.KbEntity;
import org.zhzssp.memorandum.feature.codex.entity.KbGap;
import org.zhzssp.memorandum.feature.codex.entity.KbScopeDecision;
import org.zhzssp.memorandum.feature.codex.gap.GapService;
import org.zhzssp.memorandum.feature.codex.gap.ScopeRecallService;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.repository.KbEntityRepository;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识缺口工具集。
 *
 * <h3>为什么 gap.list 是只读且在 study 模式也可见</h3>
 * <p>「我还有哪些没补的盲区」是研读时最常问的问题之一，
 * 而看一眼台账不该需要切到策展模式。写操作（转计划 / 关闭 / 忽略）才收在 curate。</p>
 *
 * <h3>为什么没有 gap.create 之类的自由登记工具</h3>
 * <p>缺口的价值在于它来自<strong>行为证据</strong>而非主观感觉。
 * 若给 Agent 一个随手登记的工具，它会在对话里「贴心地」帮用户记下一堆
 * 「你可能需要了解 X」——那些不是缺口，是猜测，会迅速把台账淹掉，
 * 让真正有证据的三类信号沉底。</p>
 *
 * <p>{@code gap.record} 保留给用户经 HTTP 手工登记（{@code source=MANUAL}），
 * 那是用户自己的判断，与模型猜测性质不同。</p>
 */
@Component
public class GapTools {

    /** 一次返回给 LLM 的条数上限：台账本身不该打爆上下文。 */
    private static final int MAX_ROWS = 30;

    private final GapService gapService;
    private final ScopeRecallService scopeRecall;
    private final RepoRegistryService registry;
    private final KbEntityRepository entityRepo;

    public GapTools(GapService gapService,
                    ScopeRecallService scopeRecall,
                    RepoRegistryService registry,
                    KbEntityRepository entityRepo) {
        this.gapService = gapService;
        this.scopeRecall = scopeRecall;
        this.registry = registry;
        this.entityRepo = entityRepo;
    }

    @AgentTool(name = "gap.list", tags = {"codex", "read"},
            description = "列出用户的知识缺口台账（按被问到次数倒序）。"
                    + "用户问「我还有哪些没搞懂的/我的知识盲区/该学什么」时调用。"
                    + "缺口来自三类行为证据：CRAG（问了但库里没有）、"
                    + "SKIP_RECALL（当初标记先跳过、现在反复被问到）、"
                    + "CP_FAIL / CP_MISPREDICT（落地检验没过或预测错）。"
                    + "回答时应说明每条的来源，因为三类的补法不同："
                    + "CRAG 补资料、CP_FAIL 补动手、CP_MISPREDICT 补因果理解。")
    public Map<String, Object> list(
            @ToolParam(value = "status", desc = "OPEN / PLANNED / CLOSED / DISMISSED；"
                    + "省略则只列待处理（OPEN + PLANNED）") String status,
            @ToolParam(value = "source", desc = "按来源过滤：CRAG / SKIP_RECALL / CP_FAIL "
                    + "/ CP_MISPREDICT / MANUAL") String source
    ) {
        User u = AgentContext.requireUser();
        if (!gapService.enabled()) {
            return Map.of("error", "GAP_DISABLED",
                    "message", "知识缺口闭环未启用（codex.gap.enabled=false）。");
        }

        List<KbGap> gaps = (status == null || status.isBlank())
                ? gapService.actionable(u.getId())
                : gapService.all(u.getId(), null).stream()
                        .filter(g -> g.getStatus() == KbGap.Status.of(status))
                        .toList();
        if (source != null && !source.isBlank()) {
            KbGap.Source s = KbGap.Source.of(source);
            gaps = gaps.stream().filter(g -> g.getSource() == s).toList();
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", gapService.summary(u.getId()));
        m.put("count", gaps.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KbGap g : gaps.stream().limit(MAX_ROWS).toList()) {
            rows.add(row(g));
        }
        m.put("gaps", rows);
        if (gaps.size() > MAX_ROWS) {
            m.put("_truncated", "共 " + gaps.size() + " 条，只返回前 " + MAX_ROWS
                    + " 条（已按被问次数排序，靠前的更该先补）。");
        }
        if (gaps.isEmpty()) {
            m.put("_hint", "台账为空。这可能是好事（没有盲区），"
                    + "也可能只是还没积累到信号——缺口来自提问与验收行为，需要用一段时间才有数据。");
        }
        return m;
    }

    @AgentTool(name = "gap.detail", tags = {"codex", "read"},
            description = "查看某条知识缺口的详情（含来源、被问次数、关联知识点与止损线出处）。")
    public Map<String, Object> detail(
            @ToolParam(value = "gapId", desc = "缺口 id", required = true) Long gapId
    ) {
        User u = AgentContext.requireUser();
        KbGap g = gapService.find(u.getId(), gapId);
        if (g == null) return Map.of("error", "GAP_NOT_FOUND", "message", "缺口不存在");
        Map<String, Object> m = row(g);
        m.put("note", g.getNote());
        m.put("firstAt", g.getFirstAt() == null ? null : g.getFirstAt().toString());
        m.put("goalId", g.getGoalId());
        m.put("githubIssueNumber", g.getGithubIssueNumber());
        m.put("closedByDocumentId", g.getClosedByDocumentId());
        return m;
    }

    /**
     * 转学习计划。
     *
     * <h3>关于「CURATE 模式 deny 了 task / goal，这个工具却会建目标」</h3>
     * <p>看似矛盾，实则不是。那条 deny 防的是<strong>顺手改</strong>——
     * 「让它整理笔记，结果动了我的任务」。而本工具的语义正相反：
     * 它唯一的作用就是建目标，用户调用它时明确知道会发生什么，
     * 且带 {@code requiresConfirm} 需逐次确认。</p>
     *
     * <p>刻意<strong>不给它加 {@code goal} tag</strong> 来「绕过」deny——
     * tag 表达的是工具属于哪个能力域，不是用来调可见性的旋钮。
     * 本工具属于 codex 域（缺口闭环），它调用目标体系是实现细节。</p>
     */
    @AgentTool(name = "gap.to_learning_plan", tags = {"codex", "write"}, requiresConfirm = true,
            description = "把一条知识缺口转成学习目标 + 任务树（复用既有目标体系，会落库）。"
                    + "用户说「安排一下补这个」「转成学习计划」时调用。"
                    + "生成的计划会强制包含一条可执行的验收——不接受「读完就算学会」。")
    public Map<String, Object> toLearningPlan(
            @ToolParam(value = "gapId", desc = "缺口 id", required = true) Long gapId,
            @ToolParam(value = "constraints", desc = "额外约束，如「每周只有 4 小时」") List<String> constraints
    ) {
        User u = AgentContext.requireUser();
        if (!gapService.enabled()) {
            return Map.of("error", "GAP_DISABLED", "message", "知识缺口闭环未启用。");
        }
        return gapService.toLearningPlan(u.getId(), gapId, constraints);
    }

    @AgentTool(name = "gap.close", tags = {"codex", "write"}, requiresConfirm = true,
            description = "关闭一条知识缺口，必须给出补上它的那篇文档路径作为证据。"
                    + "若该缺口其实不该补，用 gap.dismiss 而不是本工具——"
                    + "「补上了」与「不用补」是两件事，混在一起会让补全率失真。")
    public Map<String, Object> close(
            @ToolParam(value = "gapId", desc = "缺口 id", required = true) Long gapId,
            @ToolParam(value = "documentPath", desc = "补上该缺口的文档相对路径，"
                    + "如 docs/notes/mlir-transform-dialect.md", required = true) String documentPath
    ) {
        User u = AgentContext.requireUser();
        return gapService.close(u.getId(), gapId, documentPath);
    }

    @AgentTool(name = "gap.dismiss", tags = {"codex", "write"}, requiresConfirm = true,
            description = "忽略一条知识缺口（判定它不属于自己要掌握的范围，或是误报）。"
                    + "被忽略的缺口不会被自动重新打开。")
    public Map<String, Object> dismiss(
            @ToolParam(value = "gapId", desc = "缺口 id", required = true) Long gapId,
            @ToolParam(value = "reason", desc = "忽略原因") String reason
    ) {
        User u = AgentContext.requireUser();
        return gapService.dismiss(u.getId(), gapId, reason);
    }

    @AgentTool(name = "scope.skipped", tags = {"codex", "read"},
            description = "列出知识仓库里被标记为「先跳过（遇到再学）」的知识点，"
                    + "含各自被问到的次数与阈值。"
                    + "用户问「我跳过了哪些东西/有什么该回来补的」时调用。"
                    + "hitCount 接近或达到 threshold 的条目说明它已经在挡路了。")
    public Map<String, Object> skipped(
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        KnowledgeRepo repo = resolveRepo(u.getId(), repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到知识仓库。");
        }
        List<Map<String, Object>> list = scopeRecall.skippedList(u.getId(), repo.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", repo.getName());
        m.put("threshold", scopeRecall.threshold());
        m.put("count", list.size());
        m.put("skipped", list.stream().limit(MAX_ROWS).toList());
        if (list.isEmpty()) {
            m.put("_hint", "未解析到任何「先跳过」条目。"
                    + "若仓库确有跳过清单，检查其小节标题是否含「先跳过 / 可推迟」等措辞；"
                    + "也可能是还没同步过（repo.sync 后会自动解析）。");
        }
        return m;
    }

    @AgentTool(name = "scope.set", tags = {"codex", "write"}, requiresConfirm = true,
            description = "改判某个知识点的止损线：MUST（必须掌握）/ SKIP（先跳过）/ DROPPED（明确不学）。"
                    + "用户说「这个我要学了」「这个不用学」时调用。"
                    + "改判是用户的判断，Agent 不应主动发起。")
    public Map<String, Object> setScope(
            @ToolParam(value = "term", desc = "知识点名称", required = true) String term,
            @ToolParam(value = "decision", desc = "MUST / SKIP / DROPPED", required = true) String decision,
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        KnowledgeRepo repo = resolveRepo(u.getId(), repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到知识仓库。");
        }
        KbEntity e = entityRepo.findByRepoIdAndName(repo.getId(), term.strip()).orElse(null);
        if (e == null) {
            return Map.of("error", "ENTITY_NOT_FOUND",
                    "message", "知识点「" + term + "」不在止损线清单里。"
                            + "可先用 scope.skipped 查看已识别的条目。");
        }
        KbScopeDecision.Decision d;
        try {
            d = KbScopeDecision.Decision.valueOf(decision.strip().toUpperCase());
        } catch (Exception ex) {
            return Map.of("error", "BAD_DECISION",
                    "message", "decision 只能是 MUST / SKIP / DROPPED");
        }
        boolean ok = scopeRecall.setDecision(u.getId(), e.getId(), d);
        return ok
                ? Map.of("ok", true, "term", e.getName(), "decision", d.name(),
                        "message", "已改判为 " + d.name())
                : Map.of("error", "UPDATE_FAILED", "message", "改判失败");
    }

    /* ---------------- 内部 ---------------- */

    private Map<String, Object> row(KbGap g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gapId", g.getId());
        m.put("question", g.getQuestion());
        m.put("source", g.getSource().name());
        m.put("machineJudged", g.getSource().machineJudged());
        m.put("askCount", g.getAskCount());
        m.put("status", g.getStatus().name());
        m.put("priority", g.getPriority());
        m.put("lastAt", g.getLastAt() == null ? null : g.getLastAt().toString());
        if (g.getEntityId() != null) {
            entityRepo.findById(g.getEntityId())
                    .ifPresent(e -> m.put("entity", e.getName()));
        }
        return m;
    }

    private KnowledgeRepo resolveRepo(Long userId, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(userId, repoName.strip()).orElse(null);
        }
        List<KnowledgeRepo> all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }
}
