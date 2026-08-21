package org.zhzssp.memorandum.feature.codex.gap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.dto.ApplyGoalPlanResponse;
import org.zhzssp.memorandum.feature.agent.dto.GoalPlanRequest;
import org.zhzssp.memorandum.feature.agent.dto.GoalPlanResponse;
import org.zhzssp.memorandum.feature.agent.service.AgentPlanApplyService;
import org.zhzssp.memorandum.feature.agent.service.PlannerAgentService;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbEntity;
import org.zhzssp.memorandum.feature.codex.entity.KbGap;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.git.GitHubPrClient;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbEntityRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbGapRepository;
import org.zhzssp.memorandum.feature.codex.service.CodexMetrics;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识缺口台账与闭环编排。
 *
 * <h3>完整闭环</h3>
 * <pre>
 * 问不出来 / 跳过的被反复问到 / 检验没过
 *   → 记缺口（本服务）
 *   → 转学习计划（复用 PlannerAgentService + AgentPlanApplyService，不另造一套）
 *   → 学完产出 Guide / Note（P2 的沉淀）
 *   → 关闭缺口（须给出关闭它的那篇文档，作为证据）
 * </pre>
 *
 * <p>这个闭环<strong>首尾都能被现有指标量化</strong>：
 * 入口是 CRAG 的 {@code degradedRate}，出口是缺口关闭数。
 * degraded 率单调下降 = 知识体系真的在长——
 * 这是整套方案里少有的、无法靠「多写几篇文档」刷上去的指标。</p>
 *
 * <h3>为什么默认关闭</h3>
 * <p>{@code codex.gap.enabled=false}。这不只是谨慎：缺口记录挂在
 * {@code kb.semantic_search} / {@code doc.search} 的调用路径上，
 * 而<strong>评测套件也会走这两个工具</strong>。
 * 若默认开启，跑一次 {@code agentEval} 就会往台账里灌进几十条来自测试用例的假缺口，
 * 而这张表是本期唯一不可重建的表——污染了没法靠重建索引洗掉。</p>
 */
@Service
public class GapService {

    private static final Logger log = LoggerFactory.getLogger(GapService.class);

    private final KbGapRepository gapRepo;
    private final KbEntityRepository entityRepo;
    private final KbDocumentRepository docRepo;
    private final QuestionNormalizer normalizer;
    private final RepoRegistryService registry;
    private final PlannerAgentService planner;
    private final AgentPlanApplyService planApply;
    private final GitHubPrClient github;
    private final UserRepository userRepository;
    private final CodexMetrics metrics;

    @Value("${codex.gap.enabled:false}")
    private boolean gapEnabled;

    @Value("${codex.gap.max-open:200}")
    private int maxOpen;

    public GapService(KbGapRepository gapRepo,
                      KbEntityRepository entityRepo,
                      KbDocumentRepository docRepo,
                      QuestionNormalizer normalizer,
                      RepoRegistryService registry,
                      PlannerAgentService planner,
                      AgentPlanApplyService planApply,
                      GitHubPrClient github,
                      UserRepository userRepository,
                      CodexMetrics metrics) {
        this.gapRepo = gapRepo;
        this.entityRepo = entityRepo;
        this.docRepo = docRepo;
        this.normalizer = normalizer;
        this.registry = registry;
        this.planner = planner;
        this.planApply = planApply;
        this.github = github;
        this.userRepository = userRepository;
        this.metrics = metrics;
    }

    public boolean enabled() {
        return gapEnabled;
    }

    /* ==================== 记录 ==================== */

    /**
     * 登记或累加一条缺口。
     *
     * <p>已 {@code DISMISSED} 的缺口<strong>不会被重新打开</strong>，只累加计数。
     * 用户判定过「这不是我的缺口」之后又被自动重开，是最容易让人放弃一个功能的行为——
     * 他会觉得自己的判断不被尊重。计数仍然累加，因为若它真的被问了 20 次，
     * 用户自己会想回头看一眼。</p>
     *
     * @return 落库后的缺口；未启用或问题无法归一化时返回 null
     */
    @Transactional
    public KbGap upsert(Long userId, Long repoId, KbGap.Source source,
                        String question, Long entityId, String note) {
        if (!gapEnabled || userId == null) return null;
        String norm = normalizer.normalize(question);
        if (norm == null || norm.isBlank()) {
            // 无法归一化 → 无法去重 → 会在台账里堆出一串永远聚不起来的噪声
            log.debug("[Codex Gap] 提问无法归一化，跳过登记：{}", question);
            return null;
        }

        KbGap gap = gapRepo.findByUserIdAndSourceAndNormQuestion(userId, source, norm)
                .orElse(null);
        LocalDateTime now = LocalDateTime.now();

        if (gap != null) {
            gap.setAskCount((gap.getAskCount() == null ? 1 : gap.getAskCount()) + 1);
            gap.setLastAt(now);
            gap.setUpdatedAt(now);
            if (entityId != null && gap.getEntityId() == null) gap.setEntityId(entityId);
            if (note != null && !note.isBlank()) gap.setNote(trim(note, 1024));
            gapRepo.save(gap);
            metrics.recordGapTouched(source.name(), false);
            return gap;
        }

        long open = gapRepo.countByUserIdAndStatus(userId, KbGap.Status.OPEN);
        if (open >= Math.max(20, maxOpen)) {
            // 台账无上限增长会让看板失去可用性（几百条待处理等于没有优先级）。
            // 停止新增而非丢弃最老的：最老的往往是最该补的。
            log.info("[Codex Gap] 待处理缺口已达上限 {}，暂停新增（先处理或忽略一些）", open);
            return null;
        }

        gap = new KbGap();
        gap.setUserId(userId);
        gap.setRepoId(repoId);
        gap.setQuestion(trim(question, 1024));
        gap.setNormQuestion(norm);
        gap.setSource(source);
        gap.setEntityId(entityId);
        gap.setAskCount(1);
        gap.setFirstAt(now);
        gap.setLastAt(now);
        gap.setStatus(KbGap.Status.OPEN);
        // 有机器判据的信号默认更高优先级——它不是「可能没掌握」而是「确实没做出来」
        gap.setPriority(source.machineJudged() ? "P0" : "P1");
        gap.setNote(trim(note, 1024));
        gap.setCreatedAt(now);
        gap.setUpdatedAt(now);
        gapRepo.save(gap);
        metrics.recordGapTouched(source.name(), true);
        log.info("[Codex Gap] 新增缺口[{}]：{}", source, normalizer.summarize(question, 80));
        return gap;
    }

    /* ==================== 查询 ==================== */

    public List<KbGap> actionable(Long userId) {
        return gapRepo.findActionable(userId);
    }

    public List<KbGap> all(Long userId, Long repoId) {
        return repoId == null
                ? gapRepo.findAllForUser(userId)
                : gapRepo.findAllForUserAndRepo(userId, repoId);
    }

    public KbGap find(Long userId, Long gapId) {
        return gapRepo.findByIdAndUserId(gapId, userId).orElse(null);
    }

    /**
     * 台账摘要。
     *
     * <p>{@code closedRate} 是这套设计里最该盯的数字：它回答「缺口是在被补上，
     * 还是只是在堆积」。只涨不关的台账等于一份让人焦虑的待办清单，没有价值。</p>
     */
    public Map<String, Object> summary(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        long open = gapRepo.countByUserIdAndStatus(userId, KbGap.Status.OPEN);
        long planned = gapRepo.countByUserIdAndStatus(userId, KbGap.Status.PLANNED);
        long closed = gapRepo.countByUserIdAndStatus(userId, KbGap.Status.CLOSED);
        long dismissed = gapRepo.countByUserIdAndStatus(userId, KbGap.Status.DISMISSED);
        m.put("open", open);
        m.put("planned", planned);
        m.put("closed", closed);
        m.put("dismissed", dismissed);
        // 分母刻意排除 dismissed：那是「不是我的缺口」的判断，不该算进补全率的分母
        long denominator = open + planned + closed;
        m.put("closedRate", denominator == 0 ? 0.0
                : Math.round((double) closed / denominator * 1000) / 1000.0);

        Map<String, Long> bySource = new LinkedHashMap<>();
        for (KbGap.Source s : KbGap.Source.values()) {
            bySource.put(s.name(), gapRepo.countByUserIdAndSource(userId, s));
        }
        m.put("bySource", bySource);
        return m;
    }

    /* ==================== 状态流转 ==================== */

    /**
     * 转成学习计划：复用既有的目标 / 任务体系。
     *
     * <p>刻意<strong>不新建一套「学习计划」实体</strong>。用户的目标体系已经存在，
     * 学习本身就是一个目标——另造一套会让「我在推进的事」分裂成两个列表，
     * 而两个待办列表的结局一定是其中一个被遗忘。</p>
     */
    @Transactional
    public Map<String, Object> toLearningPlan(Long userId, Long gapId, List<String> constraints) {
        KbGap gap = find(userId, gapId);
        if (gap == null) return err("GAP_NOT_FOUND", "缺口不存在");
        if (gap.getStatus() == KbGap.Status.CLOSED) {
            return err("ALREADY_CLOSED", "该缺口已关闭，无需再转学习计划");
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return err("USER_NOT_FOUND", "用户不存在");

        String statement = buildGoalStatement(gap);
        List<String> cons = new ArrayList<>();
        if (constraints != null) cons.addAll(constraints);
        cons.add("这是一次补知识缺口的学习，产出必须落成知识仓库里的文档或笔记");
        cons.add("必须包含一条可执行的验收（能改能跑），不接受「读完就算学会」");

        GoalPlanResponse plan;
        try {
            plan = planner.draftPlan(new GoalPlanRequest(statement, cons));
        } catch (Exception e) {
            return err("PLAN_FAILED", "生成学习计划失败：" + e.getMessage());
        }
        if (plan.tasks() == null || plan.tasks().isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("code", "NEEDS_CLARIFY");
            m.put("message", "计划器需要更多信息才能拆解");
            m.put("clarifyQuestions", plan.clarifyQuestions());
            m.put("goalStatement", statement);
            return m;
        }

        ApplyGoalPlanResponse applied;
        try {
            applied = planApply.apply(user, plan);
        } catch (Exception e) {
            return err("APPLY_FAILED", "落库失败：" + e.getMessage());
        }

        gap.setStatus(KbGap.Status.PLANNED);
        gap.setGoalId(applied.goalId());
        gap.setUpdatedAt(LocalDateTime.now());
        gapRepo.save(gap);
        metrics.recordGapPlanned();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("code", "PLANNED");
        m.put("gapId", gap.getId());
        m.put("goalId", applied.goalId());
        m.put("createdTaskCount", applied.createdTaskCount());
        m.put("goalStatement", statement);
        m.put("message", "已生成学习目标与 " + applied.createdTaskCount() + " 个任务");
        return m;
    }

    /**
     * 关闭缺口——<strong>必须给出关闭它的那篇文档</strong>。
     *
     * <p>为什么强制要证据：没有证据的「已关闭」等于一次自我安慰。
     * 而且这份证据有实际用途——半年后想知道「这个知识点我是怎么补上的」，
     * 缺口记录会直接指向那篇文档。</p>
     */
    @Transactional
    public Map<String, Object> close(Long userId, Long gapId, String documentPath) {
        KbGap gap = find(userId, gapId);
        if (gap == null) return err("GAP_NOT_FOUND", "缺口不存在");

        Long docId = null;
        if (documentPath != null && !documentPath.isBlank()) {
            docId = resolveDocId(userId, documentPath);
            if (docId == null) {
                return err("DOC_NOT_FOUND",
                        "索引中没有这篇文档：" + documentPath
                                + "。若刚创建请先 repo.sync；关闭缺口必须给出证据文档。");
            }
        } else {
            return err("EVIDENCE_REQUIRED",
                    "关闭缺口必须指出是哪篇文档补上了它。"
                            + "没有证据的「已关闭」只是自我安慰，而且以后想回溯「这个知识点我是怎么补上的」时无从查找。"
                            + "若这个缺口其实不该补，请用 dismiss 而不是 close。");
        }

        gap.setStatus(KbGap.Status.CLOSED);
        gap.setClosedByDocumentId(docId);
        gap.setClosedAt(LocalDateTime.now());
        gap.setUpdatedAt(LocalDateTime.now());
        gapRepo.save(gap);
        metrics.recordGapClosed();

        String issueNote = closeIssueIfAny(userId, gap);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("code", "CLOSED");
        m.put("gapId", gap.getId());
        m.put("closedByDocumentId", docId);
        m.put("message", "缺口已关闭，证据文档：" + documentPath
                + (issueNote == null ? "" : "；" + issueNote));
        return m;
    }

    /** 忽略缺口：这是一次判断，与「补上了」严格区分。 */
    @Transactional
    public Map<String, Object> dismiss(Long userId, Long gapId, String reason) {
        KbGap gap = find(userId, gapId);
        if (gap == null) return err("GAP_NOT_FOUND", "缺口不存在");
        gap.setStatus(KbGap.Status.DISMISSED);
        gap.setNote(trim(reason == null ? gap.getNote() : reason, 1024));
        gap.setUpdatedAt(LocalDateTime.now());
        gapRepo.save(gap);
        metrics.recordGapDismissed();
        return Map.of("ok", true, "code", "DISMISSED", "gapId", gap.getId(),
                "message", "已忽略。它不会被自动重新打开，但仍会累计被问次数——"
                        + "若它被问了很多次，你自己会想回头看一眼。");
    }

    /** 外化为 GitHub Issue（可选能力）。 */
    @Transactional
    public Map<String, Object> toIssue(Long userId, Long gapId) {
        KbGap gap = find(userId, gapId);
        if (gap == null) return err("GAP_NOT_FOUND", "缺口不存在");
        if (gap.getGithubIssueNumber() != null) {
            return err("ALREADY_EXISTS", "已创建过 Issue #" + gap.getGithubIssueNumber());
        }
        if (gap.getRepoId() == null) {
            return err("NO_REPO", "该缺口未关联知识仓库，无法创建 Issue");
        }
        KnowledgeRepo repo = registry.find(userId, gap.getRepoId()).orElse(null);
        if (repo == null) return err("REPO_NOT_FOUND", "仓库不存在");
        if (repo.getProvider() != KnowledgeRepo.RepoProvider.GITHUB) {
            return err("PROVIDER_LOCAL",
                    "该仓库为本地仓库，不创建 Issue。缺口台账在本地看板里同样完整可用——"
                            + "Issue 只是把它外化到远端以便讨论。");
        }

        GitHubPrClient.IssueResult r = github.createIssue(
                repo.getRemoteUrl(), repo.getTokenRef(),
                "[知识缺口] " + normalizer.summarize(gap.getQuestion(), 80),
                issueBody(gap),
                List.of("knowledge-gap", gap.getSource().name().toLowerCase()));
        if (!r.ok()) {
            return err(r.code(), r.message());
        }
        gap.setGithubIssueNumber(r.number());
        gap.setUpdatedAt(LocalDateTime.now());
        gapRepo.save(gap);
        return Map.of("ok", true, "code", "ISSUE_CREATED",
                "issueNumber", r.number(), "url", r.url() == null ? "" : r.url());
    }

    /* ---------------- 内部 ---------------- */

    /**
     * 由缺口生成目标陈述。
     *
     * <p>按来源分别措辞，因为三类缺口的「补法」完全不同：
     * 检索没命中要补的是资料，检验失败要补的是动手，预测错要补的是因果理解。
     * 用同一句模板会让规划器给出千篇一律的任务树。</p>
     */
    String buildGoalStatement(KbGap gap) {
        String q = normalizer.summarize(gap.getQuestion(), 200);
        String entity = gap.getEntityId() == null ? null
                : entityRepo.findById(gap.getEntityId()).map(KbEntity::getName).orElse(null);
        return switch (gap.getSource()) {
            case SKIP_RECALL -> "补上当初判定「先跳过」但现在绕不开的知识点"
                    + (entity == null ? "" : "：" + entity)
                    + "。学完要能说清它解决什么问题、以及为什么当初可以先跳过而现在不能。";
            case CP_FAIL -> "攻克一条做不出来的落地检验：" + q
                    + "。目标是能独立跑通并解释每一步为什么这么做。";
            case CP_MISPREDICT -> "修正一个被验证为错误的理解：" + q
                    + "。重点不是把结果做对（已经对了），而是弄清我原来的因果推断错在哪里。";
            case CRAG, MANUAL -> "补齐知识缺口：" + q
                    + "。产出要能回答这个问题，并沉淀进知识仓库。";
        };
    }

    private String issueBody(KbGap gap) {
        StringBuilder sb = new StringBuilder();
        sb.append("> 由 Lattice Codex 自动登记的知识缺口。\n\n");
        sb.append("**问题**：").append(gap.getQuestion()).append("\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 来源 | `").append(gap.getSource().name()).append("` |\n");
        sb.append("| 被问到次数 | ").append(gap.getAskCount()).append(" |\n");
        sb.append("| 首次 | ").append(gap.getFirstAt()).append(" |\n");
        sb.append("| 最近 | ").append(gap.getLastAt()).append(" |\n");
        if (gap.getNote() != null && !gap.getNote().isBlank()) {
            sb.append("\n**上下文**：").append(gap.getNote()).append('\n');
        }
        sb.append("\n关闭方式：补上对应文档后，在 PR 描述里写 `Closes #<本 Issue 号>`。\n");
        return sb.toString();
    }

    private String closeIssueIfAny(Long userId, KbGap gap) {
        if (gap.getGithubIssueNumber() == null || gap.getRepoId() == null) return null;
        KnowledgeRepo repo = registry.find(userId, gap.getRepoId()).orElse(null);
        if (repo == null || repo.getProvider() != KnowledgeRepo.RepoProvider.GITHUB) return null;
        GitHubPrClient.IssueResult r = github.closeIssue(
                repo.getRemoteUrl(), repo.getTokenRef(), gap.getGithubIssueNumber());
        // 关不掉不影响本地状态：本地才是台账的权威处，Issue 只是外化视图
        return r.ok() ? "已关闭 Issue #" + gap.getGithubIssueNumber()
                : "Issue #" + gap.getGithubIssueNumber() + " 关闭失败（" + r.message() + "）";
    }

    private Long resolveDocId(Long userId, String pathOrTitle) {
        String p = pathOrTitle.strip().replace('\\', '/');
        for (KnowledgeRepo r : registry.listEnabled(userId)) {
            KbDocument d = docRepo.findByRepoIdAndPath(r.getId(), p).orElse(null);
            if (d != null) return d.getId();
        }
        List<KbDocument> byTitle = docRepo.searchByTitle(userId, p);
        return byTitle.isEmpty() ? null : byTitle.get(0).getId();
    }

    private static Map<String, Object> err(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("code", code);
        m.put("error", code);
        m.put("message", message);
        return m;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
