package org.zhzssp.memorandum.feature.codex.gap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbEntity;
import org.zhzssp.memorandum.feature.codex.entity.KbGap;
import org.zhzssp.memorandum.feature.codex.entity.KbScopeDecision;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.index.FrontMatterParser;
import org.zhzssp.memorandum.feature.codex.index.MarkdownStructureParser;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbEntityRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbScopeDecisionRepository;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 止损线召回：让「遇到再学」真的能被召回。
 *
 * <h3>这是本期最有价值的一环，因为它监测的事此前完全无人监测</h3>
 * <p>用户每篇 guide 都写了「先跳过（遇到再学）」清单——这条纪律防止了自学中
 * 最常见的死法：无限深潜。但它有个隐性缺陷：<strong>「遇到了」没有人负责发现</strong>。
 * 于是止损线只能单向生效，跳过的东西就永久跳过了，
 * 哪怕它后来一直挡在路上。</p>
 *
 * <p>软件能做的事很小但很实：记住那份清单，然后在用户提问时留意。
 * 某个 SKIP 概念被问到 N 次 → 「{@code transform-dialect} 你标了先跳过，
 * 本周已被问到 3 次，建议补上」→ 一键转学习任务。</p>
 *
 * <h3>为什么必须做词边界匹配</h3>
 * <p>清单里有 {@code omp} / {@code acc} / {@code pdl} 这类三字母术语。
 * 用 {@code contains} 匹配的话，「compiler」会命中 {@code omp}、
 * 「accuracy」会命中 {@code acc}——一次提问能误触发好几条。
 * 而误报的代价是不对称的：漏报只是少一次提醒，误报会让用户几天内就关掉这个功能。</p>
 *
 * <h3>为什么阈值不是 1</h3>
 * <p>一次提到某个跳过的概念，很可能只是顺带提及（「这个先不管」）。
 * 反复被问才说明它真的挡路了。阈值把「偶遇」与「绕不开」区分开——
 * 这个区分正是「遇到再学」这条纪律里「遇到」二字的实际含义。</p>
 */
@Service
public class ScopeRecallService {

    private static final Logger log = LoggerFactory.getLogger(ScopeRecallService.class);

    /** 一次提问最多触发的召回条数：防止一句话里提到十个跳过项时刷出十条缺口。 */
    private static final int MAX_HITS_PER_QUESTION = 3;

    /**
     * 编译后的匹配术语。
     *
     * <p>公开是为了让匹配规则可被单独测试——词边界这条规则是整个召回机制里
     * 最容易出错、后果最直接的一环（{@code omp} 命中 {@code compiler} 之类），
     * 它必须有独立的测试守着，而不是只能通过整个服务间接验证。</p>
     */
    public record Term(Long entityId, String name, Pattern pattern, boolean cjk) {

        /** @param lowerQuestion 已转小写的提问文本 */
        public boolean matches(String lowerQuestion) {
            if (cjk) return lowerQuestion.contains(name.toLowerCase(Locale.ROOT));
            return pattern.matcher(lowerQuestion).find();
        }
    }

    private final KbEntityRepository entityRepo;
    private final KbScopeDecisionRepository decisionRepo;
    private final KbDocumentRepository docRepo;
    private final RepoRegistryService registry;
    private final ScopeListParser parser;
    private final MarkdownStructureParser structure;
    private final FrontMatterParser fm;
    private final GapService gapService;

    /** repoId → 编译后的 SKIP 术语表。同步时失效。 */
    private final Map<Long, List<Term>> termCache = new ConcurrentHashMap<>();

    @Value("${codex.gap.enabled:false}")
    private boolean gapEnabled;

    @Value("${codex.gap.skip-recall-threshold:3}")
    private int recallThreshold;

    public ScopeRecallService(KbEntityRepository entityRepo,
                              KbScopeDecisionRepository decisionRepo,
                              KbDocumentRepository docRepo,
                              RepoRegistryService registry,
                              ScopeListParser parser,
                              MarkdownStructureParser structure,
                              FrontMatterParser fm,
                              GapService gapService) {
        this.entityRepo = entityRepo;
        this.decisionRepo = decisionRepo;
        this.docRepo = docRepo;
        this.registry = registry;
        this.parser = parser;
        this.structure = structure;
        this.fm = fm;
        this.gapService = gapService;
    }

    public boolean enabled() {
        return gapEnabled;
    }

    public int threshold() {
        return recallThreshold;
    }

    /* ==================== 同步「先跳过」清单 ==================== */

    public record SyncResult(int documentsScanned, int termsFound, int entitiesCreated,
                             int decisionsCreated, int decisionsKept) {}

    /**
     * 从仓库全部文档解析「先跳过」清单，落成 entity + scope 决策。
     *
     * <p><strong>已存在的决策只更新 reason 与出处，绝不重置 {@code hitCount}</strong>——
     * 与 P1 的检验同步同一立场：重新解析一次文档不该把用户的行为数据清零。
     * 「这个概念我已经遇到 2 次了」是攒了很久的信息，丢了就再也攒不回来。</p>
     */
    @Transactional
    public SyncResult syncFromRepo(KnowledgeRepo repo) {
        Path root = registry.rootOf(repo);
        List<KbDocument> docs = docRepo.findByRepoId(repo.getId());

        // 全仓库去重：同一个术语可能在多篇 guide 的跳过清单里都出现
        Map<String, ScopeListParser.SkipItem> merged = new LinkedHashMap<>();
        Map<String, Long> definedIn = new LinkedHashMap<>();
        int scanned = 0;

        for (KbDocument d : docs) {
            if (d.getKind() == KbDocument.DocKind.SOURCE) continue;   // PDF 不解析
            String content;
            try {
                Path f = root.resolve(d.getPath());
                if (!Files.isRegularFile(f)) continue;
                content = Files.readString(f, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("[Codex Gap] 读取失败 {}：{}", d.getPath(), e.getMessage());
                continue;
            }
            scanned++;
            int bodyStart = fm.parse(content).bodyStart();
            var sections = structure.parse(content, bodyStart).sections();
            for (ScopeListParser.SkipItem item : parser.parse(content, bodyStart, sections)) {
                String key = item.term().toLowerCase(Locale.ROOT);
                if (merged.putIfAbsent(key, item) == null) {
                    definedIn.put(key, d.getId());
                }
            }
        }

        int entitiesCreated = 0;
        int decisionsCreated = 0;
        int decisionsKept = 0;

        for (Map.Entry<String, ScopeListParser.SkipItem> e : merged.entrySet()) {
            ScopeListParser.SkipItem item = e.getValue();
            KbEntity entity = entityRepo.findByRepoIdAndName(repo.getId(), item.term())
                    .orElse(null);
            if (entity == null) {
                entity = new KbEntity();
                entity.setRepoId(repo.getId());
                entity.setUserId(repo.getUserId());
                entity.setName(item.term());
                entity.setDefinedInDocumentId(definedIn.get(e.getKey()));
                entity = entityRepo.save(entity);
                entitiesCreated++;
            }

            Optional<KbScopeDecision> existing = decisionRepo.findByEntityId(entity.getId());
            if (existing.isPresent()) {
                KbScopeDecision d = existing.get();
                // 只更新「定义」，hitCount 与用户可能已手工改过的 decision 保持
                d.setReason(item.reason());
                d.setDecidedInDocumentId(definedIn.get(e.getKey()));
                decisionRepo.save(d);
                decisionsKept++;
            } else {
                KbScopeDecision d = new KbScopeDecision();
                d.setEntityId(entity.getId());
                d.setDecision(KbScopeDecision.Decision.SKIP);
                d.setReason(item.reason());
                d.setDecidedInDocumentId(definedIn.get(e.getKey()));
                d.setHitCount(0);
                decisionRepo.save(d);
                decisionsCreated++;
            }
        }

        termCache.remove(repo.getId());
        log.info("[Codex Gap] 仓库「{}」止损线同步：扫描 {} 篇，术语 {} 个，"
                        + "新建 entity {} / 决策 {}，保留既有决策 {}",
                repo.getName(), scanned, merged.size(),
                entitiesCreated, decisionsCreated, decisionsKept);
        return new SyncResult(scanned, merged.size(), entitiesCreated,
                decisionsCreated, decisionsKept);
    }

    /* ==================== 召回检测 ==================== */

    /** 一次命中。 */
    public record Recall(String term, Long entityId, int hitCount, boolean reachedThreshold,
                         String reason) {}

    /**
     * 检测提问是否命中了「先跳过」的概念，命中则累加计数。
     *
     * <p>达到阈值时生成 {@code SKIP_RECALL} 缺口。<strong>不是每次命中都建缺口</strong>——
     * 见类注释里关于「偶遇 vs 绕不开」的区分。</p>
     */
    @Transactional
    public List<Recall> observeQuestion(Long userId, Long repoId, String question) {
        if (!gapEnabled || question == null || question.isBlank()) return List.of();

        List<Long> repoIds = new ArrayList<>();
        if (repoId != null) {
            repoIds.add(repoId);
        } else {
            registry.listEnabled(userId).forEach(r -> repoIds.add(r.getId()));
        }
        if (repoIds.isEmpty()) return List.of();

        String lower = question.toLowerCase(Locale.ROOT);
        List<Recall> out = new ArrayList<>();

        for (Long rid : repoIds) {
            for (Term t : terms(rid)) {
                if (out.size() >= MAX_HITS_PER_QUESTION) return out;
                if (!t.matches(lower)) continue;

                KbScopeDecision d = decisionRepo.findByEntityId(t.entityId()).orElse(null);
                if (d == null || d.getDecision() != KbScopeDecision.Decision.SKIP) continue;

                int hits = (d.getHitCount() == null ? 0 : d.getHitCount()) + 1;
                d.setHitCount(hits);
                decisionRepo.save(d);

                boolean reached = hits >= Math.max(1, recallThreshold);
                if (reached) {
                    gapService.upsert(userId, rid, KbGap.Source.SKIP_RECALL,
                            "「" + t.name() + "」当初标记为先跳过，现已被问到 " + hits + " 次",
                            t.entityId(),
                            "止损线出处：" + shorten(d.getReason(), 300));
                }
                out.add(new Recall(t.name(), t.entityId(), hits, reached, d.getReason()));
            }
        }
        return out;
    }

    /** 某仓库的 SKIP 清单（供工具与看板展示）。 */
    public List<Map<String, Object>> skippedList(Long userId, Long repoId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbEntity e : entityRepo.findByRepoId(repoId)) {
            if (!e.getUserId().equals(userId)) continue;
            KbScopeDecision d = decisionRepo.findByEntityId(e.getId()).orElse(null);
            if (d == null || d.getDecision() != KbScopeDecision.Decision.SKIP) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entityId", e.getId());
            m.put("term", e.getName());
            m.put("hitCount", d.getHitCount());
            m.put("threshold", recallThreshold);
            m.put("reason", d.getReason());
            out.add(m);
        }
        out.sort((a, b) -> Integer.compare(
                (Integer) b.getOrDefault("hitCount", 0), (Integer) a.getOrDefault("hitCount", 0)));
        return out;
    }

    /** 手工改判止损线（用户可以把 SKIP 改成 MUST，或反悔）。 */
    @Transactional
    public boolean setDecision(Long userId, Long entityId, KbScopeDecision.Decision decision) {
        KbEntity e = entityRepo.findById(entityId).orElse(null);
        if (e == null || !e.getUserId().equals(userId)) return false;
        KbScopeDecision d = decisionRepo.findByEntityId(entityId).orElseGet(() -> {
            KbScopeDecision nd = new KbScopeDecision();
            nd.setEntityId(entityId);
            nd.setHitCount(0);
            return nd;
        });
        d.setDecision(decision);
        decisionRepo.save(d);
        termCache.remove(e.getRepoId());
        return true;
    }

    /* ---------------- 内部 ---------------- */

    private List<Term> terms(Long repoId) {
        return termCache.computeIfAbsent(repoId, rid -> {
            List<Term> list = new ArrayList<>();
            for (KbEntity e : entityRepo.findByRepoId(rid)) {
                KbScopeDecision d = decisionRepo.findByEntityId(e.getId()).orElse(null);
                if (d == null || d.getDecision() != KbScopeDecision.Decision.SKIP) continue;
                list.add(compile(e.getId(), e.getName()));
            }
            log.debug("[Codex Gap] 仓库 {} 编译 SKIP 术语 {} 个", rid, list.size());
            return list;
        });
    }

    /**
     * 编译一个术语。
     *
     * <p>含 CJK 的术语走 {@code contains}（中文没有词边界概念）；
     * 纯 latin 术语必须加词边界，否则 {@code omp} 会命中 {@code compiler}。</p>
     */
    public static Term compile(Long entityId, String name) {
        boolean cjk = name.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff);
        if (cjk) {
            return new Term(entityId, name, null, true);
        }
        // 词边界用 lookaround 而非 \b：术语可能含 . - + #（如 c++ / llvm-mca），
        // 而 \b 在这些字符处的行为与直觉不符
        String p = "(?<![A-Za-z0-9_])" + Pattern.quote(name.toLowerCase(Locale.ROOT))
                + "(?![A-Za-z0-9_])";
        return new Term(entityId, name, Pattern.compile(p), false);
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
