package org.zhzssp.memorandum.feature.codex.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbGap;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.gap.GapService;
import org.zhzssp.memorandum.feature.codex.repository.KbCheckpointRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 定线（ROUTER）：回答「我现在该干什么」，并算出读→做→验的阶段表。
 *
 * <h3>★这里刻意不调用 LLM，一次都不调</h3>
 * <p>设计文档把 ROUTER 列为一个子代理角色。我没有那样实现，理由是具体的：</p>
 *
 * <p>用户的 README §0 优先级表里写着「P0 投入 60% / P1 30% / P2 10%」这类<strong>主观判断</strong>。
 * 那是他对自己时间的分配决定，机器没有任何依据去修改它，
 * 让 LLM 生成一份"建议的阶段表"只会产出一份看起来合理、但用户无法核对的东西——
 * 而无法核对的建议，用户最终只会忽略它。</p>
 *
 * <p>软件真正能提供的增量在别处：用户的周次表已经写好了，
 * 但<strong>每天要人工去对照</strong>「我现在处于哪一周、这周该验哪几条」。
 * 这件事是纯计算：checkpoint 的状态、缺口的 askCount、草稿的核对状态全在库里。
 * <strong>纯计算的结论可以逐条核对</strong>——每条建议都附上它依据的那条记录，
 * 用户点进去就能验证软件有没有算错。这比"更聪明的建议"重要得多。</p>
 *
 * <h3>建议的排序依据，以及为什么草稿核对排在最前</h3>
 * <p>优先级不是按"价值高低"排的，而是按<strong>不做的后果会不会扩散</strong>排：</p>
 * <ol>
 *   <li><strong>未核对的蒸馏草稿</strong>——放着不管的后果会扩散：
 *       它会被检索命中、被引用、被出题，里面一处模型编的参数会顺着引用传播出去。
 *       其余每一项拖延只是拖延，只有这一项会污染。</li>
 *   <li><strong>已填预测未跑的检验</strong>——预测已冻结，此刻是它信息量最大的时刻；
 *       拖久了人会忘记自己当时怎么想的，「预测错」这个最有价值的信号就废了。</li>
 *   <li><strong>通过但预测错、尚未沉淀</strong>——心智模型被修正的那一瞬间，
 *       只有当时记得下来。</li>
 * </ol>
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);

    /**
     * 一条行动建议。
     *
     * @param kind     机器可判的类型，供 UI 决定跳转到哪
     * @param weight   排序权重（越大越紧急）
     * @param what     做什么
     * @param why      ★依据。每条都要能被用户点开核对，否则这就是另一种「无法核对的建议」
     * @param ref      关联对象（checkpoint code / 文档路径 / gap id）
     * @param href     前端跳转地址
     */
    public record Action(String kind, int weight, String what, String why,
                         String ref, String href) {}

    /**
     * 阶段表的一行：一个主题的读→做→验。
     *
     * @param labExists lab 目录是否真实存在——不存在时「做」这一列是空的，必须如实显示
     */
    public record Stage(String topic, String guidePath, String guideTitle,
                        String maturity, String labDir, boolean labExists,
                        int checkpointTotal, int passed, int failed, int todo,
                        int l2Passed, boolean agentDrafted) {

        public boolean verifiable() {
            return checkpointTotal > 0;
        }
    }

    /** 一次定线的完整结果。 */
    public record Route(String repoName, List<Action> actions, List<Stage> stages,
                        Map<String, Object> summary, List<String> caveats) {}

    private final RepoRegistryService registry;
    private final KbDocumentRepository docRepo;
    private final KbCheckpointRepository cpRepo;
    private final GapService gapService;
    private final ObjectMapper om;

    public RouteService(RepoRegistryService registry,
                        KbDocumentRepository docRepo,
                        KbCheckpointRepository cpRepo,
                        GapService gapService,
                        ObjectMapper om) {
        this.registry = registry;
        this.docRepo = docRepo;
        this.cpRepo = cpRepo;
        this.gapService = gapService;
        this.om = om;
    }

    /* ==================== 主入口 ==================== */

    public Route compute(Long userId, Long repoId) {
        KnowledgeRepo repo = repoId == null
                ? firstEnabled(userId)
                : registry.find(userId, repoId).orElse(null);
        if (repo == null) {
            return new Route(null, List.of(), List.of(),
                    Map.of("error", "NO_REPO"),
                    List.of("没有已启用的知识仓库，无法定线。"));
        }

        List<KbDocument> docs = docRepo.findByRepoId(repo.getId());
        List<KbCheckpoint> cps = cpRepo.findByRepoIdOrderByCodeAsc(repo.getId());
        Path root = registry.rootOf(repo);

        List<Stage> stages = buildStages(repo, docs, cps, root);
        List<Action> actions = buildActions(userId, repo, docs, cps, stages);
        actions.sort(Comparator.comparingInt(Action::weight).reversed());

        return new Route(repo.getName(), actions, stages,
                summarize(docs, cps, stages), caveats(docs, cps, stages));
    }

    /* ==================== 阶段表 ==================== */

    /**
     * 由仓库结构算出读→做→验三列。
     *
     * <p>guide ↔ lab ↔ checkpoint 的对应关系不猜：lab 取自检验册里
     * 「对应动手项目」那一行（已由 {@code CheckpointParser} 解析成 {@code lab} 字段），
     * 或按命名约定 {@code xxx-learning-guide.md ↔ xxx-lab/} 匹配<strong>且目录必须存在</strong>。
     * 匹配不上就显示空，不填一个猜测值——阶段表里一个错误的 lab 路径会让人
     * 照着它 cd 进不存在的目录，然后怀疑是不是自己环境坏了。</p>
     */
    private List<Stage> buildStages(KnowledgeRepo repo, List<KbDocument> docs,
                                    List<KbCheckpoint> cps, Path root) {
        List<Stage> out = new ArrayList<>();
        for (KbDocument d : docs) {
            if (d.getKind() != KbDocument.DocKind.GUIDE) continue;

            String topic = topicOf(d.getPath());
            List<KbCheckpoint> mine = cps.stream()
                    .filter(c -> c.getDocumentId() != null && c.getDocumentId().equals(d.getId()))
                    .toList();

            String lab = mine.stream()
                    .map(KbCheckpoint::getLab)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElseGet(() -> guessLab(root, topic));
            boolean labExists = lab != null && Files.isDirectory(root.resolve(lab));

            int passed = 0, failed = 0, todo = 0, l2Passed = 0;
            boolean agentDrafted = false;
            for (KbCheckpoint c : mine) {
                switch (c.getStatus()) {
                    case PASSED, DEGRADED -> {
                        passed++;
                        if (c.getLevel() == KbCheckpoint.Level.L2) l2Passed++;
                    }
                    case FAILED -> failed++;
                    case TODO, PREDICTED -> todo++;
                    default -> { }
                }
                if (c.getVerifySource() == KbCheckpoint.VerifySource.AGENT_DRAFT) {
                    agentDrafted = true;
                }
            }
            out.add(new Stage(topic, d.getPath(),
                    d.getTitle() == null ? topic : d.getTitle(),
                    maturityOf(d), lab, labExists,
                    mine.size(), passed, failed, todo, l2Passed, agentDrafted));
        }
        // 未验的排前面：阶段表的用处是"下一步去哪"，已经验完的主题不需要占视线
        out.sort(Comparator
                .comparingInt((Stage s) -> s.verifiable() ? 1 : 0)
                .thenComparing(Stage::topic));
        return out;
    }

    /* ==================== 行动建议 ==================== */

    private List<Action> buildActions(Long userId, KnowledgeRepo repo,
                                      List<KbDocument> docs, List<KbCheckpoint> cps,
                                      List<Stage> stages) {
        List<Action> out = new ArrayList<>();

        // ① 未核对的蒸馏草稿——唯一一项「不做会扩散」的
        for (KbDocument d : docs) {
            if (!"draft".equalsIgnoreCase(maturityOf(d))) continue;
            if (!isAgentDistilled(d)) continue;
            out.add(new Action("REVIEW_DRAFT", 100,
                    "核对蒸馏草稿《" + shortTitle(d) + "》",
                    "它的 front-matter 是 maturity=draft 且 distilled_by=lattice-agent，"
                            + "说明内容尚未被任何人对着原文看过。"
                            + "未核对的草稿会被检索命中、被引用、被出题——"
                            + "里面一处模型编的参数会顺着引用扩散出去。"
                            + "这是列表里唯一「拖着会变坏」而不只是「拖着没做」的事。",
                    d.getPath(), "/codex/doc?path=" + enc(d.getPath())));
        }

        // ② 已填预测未跑——此刻是这条检验信息量最大的时刻
        for (KbCheckpoint c : cps) {
            if (c.getStatus() != KbCheckpoint.Status.PREDICTED) continue;
            out.add(new Action("RUN_PREDICTED", 90,
                    "跑验收 " + c.getCode() + "：" + nvl(c.getTitle()),
                    "预测已于 " + fmt(c.getPredictedAt()) + " 冻结。"
                            + "拖久了你会忘记当时是怎么想的，"
                            + "「预测错」这个最有价值的信号就失效了——"
                            + "它的价值全在于对比当时的想法与实际结果。",
                    c.getCode(), "/codex/checkpoints#" + c.getCode()));
        }

        // ③ 通过但预测错、尚未沉淀
        for (KbCheckpoint c : cps) {
            if (!Boolean.FALSE.equals(c.getPredictionCorrect())) continue;
            if (c.getDivergence() == null || c.getDivergence().isBlank()) continue;
            out.add(new Action("SEDIMENT_DIVERGENCE", 85,
                    "沉淀 " + c.getCode() + " 的认知修正",
                    "这条通过了但预测错——结果对而因果理解错，是所有假掌握里最危险的一类。"
                            + "「我原以为…实际…」只有当时记得下来，"
                            + "而正确结论到处都能查到，这一份别处没有。",
                    c.getCode(), "/codex/curate?divergence=" + enc(c.getCode())));
        }

        // ④ 失败的检验：按盲点回指
        for (KbCheckpoint c : cps) {
            if (c.getStatus() != KbCheckpoint.Status.FAILED) continue;
            out.add(new Action("FIX_FAILED", 70,
                    "补 " + c.getCode() + "：" + nvl(c.getTitle()),
                    c.getBlindSpots() == null || c.getBlindSpots().isBlank()
                            ? "上次验收失败，且这条没写「常见失败 → 盲点」，需要自己判断卡在哪。"
                            : "上次验收失败。原文给出的盲点映射："
                                    + shorten(c.getBlindSpots(), 120),
                    c.getCode(), "/codex/checkpoints#" + c.getCode()));
        }

        // ⑤ 缺口：只取最该先补的几条，避免把列表淹掉
        try {
            List<KbGap> gaps = gapService.actionable(userId);
            int n = 0;
            for (KbGap g : gaps) {
                if (n++ >= 3) break;
                out.add(new Action("CLOSE_GAP", 60 - n,
                        "补缺口：" + shorten(nvl(g.getQuestion()), 60),
                        "来源 " + g.getSource() + "，已被问到 " + g.getAskCount() + " 次。"
                                + "三类来源补法不同：CRAG 补资料、SKIP_RECALL 是当初主动跳过、"
                                + "CP_* 补动手或因果理解。",
                        String.valueOf(g.getId()), "/codex/gaps#gap-" + g.getId()));
            }
        } catch (Exception e) {
            log.debug("[Codex/Route] 读取缺口失败（不影响定线）：{}", e.getMessage());
        }

        // ⑥ 有 guide 却完全没有检验——「读完了但没验」是最容易自我高估的状态
        for (Stage s : stages) {
            if (s.verifiable()) continue;
            out.add(new Action("NEEDS_CHECKPOINT", 50,
                    "给《" + s.guideTitle() + "》建检验",
                    "这篇文档目前 0 条落地检验。"
                            + "只读不验的主题无法回答「你说你学会了，证据是什么」，"
                            + "而这恰恰是自我高估最常发生的地方。"
                            + (s.labExists() ? "（已有配套 lab：" + s.labDir() + "）"
                                    : "（★还没有配套 lab，先建一个最小可跑目录，否则出的题只能是编的）"),
                    s.guidePath(),
                    s.labExists() ? "/codex/distill?exam=" + enc(s.guidePath()) : null));
        }

        // ⑦ 有题未填预测
        long todoCount = cps.stream()
                .filter(c -> c.getStatus() == KbCheckpoint.Status.TODO).count();
        if (todoCount > 0) {
            KbCheckpoint first = cps.stream()
                    .filter(c -> c.getStatus() == KbCheckpoint.Status.TODO)
                    .min(Comparator.comparing(KbCheckpoint::getCode)).orElse(null);
            out.add(new Action("PREDICT", 40,
                    "填预测：" + (first == null ? "" : first.getCode() + " " + nvl(first.getTitle())),
                    "共 " + todoCount + " 条尚未填预测，未填则不能运行。"
                            + "这条门禁的理由是：改完再解释，人会自动为既成结果编一个理由，"
                            + "于是永远发现不了自己原本想错了。",
                    first == null ? null : first.getCode(), "/codex/checkpoints"));
        }

        if (out.isEmpty()) {
            out.add(new Action("IDLE", 1,
                    "没有待办：可以开始一个新主题",
                    "全部检验已验完、无失败项、无待核对草稿、缺口台账为空。"
                            + "（若你觉得这不对，多半是索引或检验还没同步——"
                            + "本页只依据库里已有的记录计算，不做推测。）",
                    null, "/codex"));
        }
        return out;
    }

    /* ==================== 汇总与如实声明 ==================== */

    private Map<String, Object> summarize(List<KbDocument> docs, List<KbCheckpoint> cps,
                                          List<Stage> stages) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("guides", stages.size());
        m.put("guidesVerifiable", stages.stream().filter(Stage::verifiable).count());
        m.put("guidesWithLab", stages.stream().filter(Stage::labExists).count());
        m.put("drafts", docs.stream().filter(d -> "draft".equalsIgnoreCase(maturityOf(d))).count());
        m.put("checkpoints", cps.size());

        // ★人写的题与机器出的题分开统计。混在一起会让「通过率」这个
        // 号称无法造假的指标失去意义——那才是真正不可逆的损失
        long human = cps.stream()
                .filter(c -> c.getVerifySource() != KbCheckpoint.VerifySource.AGENT_DRAFT).count();
        long agent = cps.size() - human;
        long humanPassed = cps.stream()
                .filter(c -> c.getVerifySource() != KbCheckpoint.VerifySource.AGENT_DRAFT)
                .filter(c -> c.getStatus() == KbCheckpoint.Status.PASSED).count();
        long agentPassed = cps.stream()
                .filter(c -> c.getVerifySource() == KbCheckpoint.VerifySource.AGENT_DRAFT)
                .filter(c -> c.getStatus() == KbCheckpoint.Status.PASSED).count();
        m.put("humanAuthored", human);
        m.put("humanPassed", humanPassed);
        m.put("agentAuthored", agent);
        m.put("agentPassed", agentPassed);
        m.put("passRateHumanAuthored", human == 0 ? null
                : Math.round(humanPassed * 1000.0 / human) / 1000.0);
        return m;
    }

    /**
     * 必须说清的口径限制。
     *
     * <p>「本页只依据库里已有的记录」这句话不是免责声明，是真话：
     * 若索引没同步，这里会漏掉刚写的文档；若检验没 sync，会漏掉刚加的题。
     * 不说清楚的话，用户会把「软件说我没事可做」理解成"我确实没事可做"。</p>
     */
    private List<String> caveats(List<KbDocument> docs, List<KbCheckpoint> cps,
                                List<Stage> stages) {
        List<String> out = new ArrayList<>();
        out.add("本页全部结论来自库里已有记录的确定性计算，不调用 LLM，也不做推测——"
                + "每条建议后面的「依据」都可以点开核对。");
        if (cps.isEmpty()) {
            out.add("检验表为空：可能确实还没有检验，也可能只是还没同步（检验面板里点一次同步）。"
                    + "两种情况这里无法区分，所以不做判断。");
        }
        long agent = cps.stream()
                .filter(c -> c.getVerifySource() == KbCheckpoint.VerifySource.AGENT_DRAFT).count();
        if (agent > 0) {
            out.add("其中 " + agent + " 条是 AI 起草的题（AGENT_DRAFT），判据未经人验证。"
                    + "通过率按人写/机器出分开统计，不合并——"
                    + "混在一起会让「通过率」这个本来无法造假的指标失去意义。");
        }
        long noLab = stages.stream().filter(s -> !s.labExists()).count();
        if (noLab > 0) {
            out.add(noLab + " 篇文档没有配套的动手项目目录。"
                    + "这些主题无法出可执行的题——不是软件不肯出，"
                    + "是没有 lab 时任何验收命令都只能是编的。");
        }
        return out;
    }

    /* ==================== 内部 ==================== */

    private KnowledgeRepo firstEnabled(Long userId) {
        List<KnowledgeRepo> all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 按命名约定猜 lab，但<strong>目录不存在就返回 null</strong>。 */
    private String guessLab(Path root, String topic) {
        if (topic == null || topic.isBlank()) return null;
        String t = topic.toLowerCase(Locale.ROOT);
        for (String candidate : List.of(t + "-lab", t + "-compile", t + "-dialect", t)) {
            if (Files.isDirectory(root.resolve(candidate))) return candidate;
        }
        return null;
    }

    private String topicOf(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1)
                .replaceAll("\\.md$", "")
                .replaceAll("-learning-guide$", "")
                .replaceAll("-guide$", "");
        return base.isBlank() ? path : base;
    }

    private String maturityOf(KbDocument d) {
        JsonNode n = fm(d);
        if (n == null) return null;
        String v = n.path("maturity").asText(null);
        return v == null || v.isBlank() ? null : v;
    }

    private boolean isAgentDistilled(KbDocument d) {
        JsonNode n = fm(d);
        if (n == null) return false;
        return "lattice-agent".equalsIgnoreCase(n.path("distilled_by").asText(""))
                || "lattice-agent".equalsIgnoreCase(n.path("authored_by").asText(""));
    }

    private JsonNode fm(KbDocument d) {
        if (d.getFrontMatterJson() == null || d.getFrontMatterJson().isBlank()) return null;
        try {
            return om.readTree(d.getFrontMatterJson());
        } catch (Exception e) {
            return null;
        }
    }

    private String shortTitle(KbDocument d) {
        String t = d.getTitle() == null || d.getTitle().isBlank() ? d.getPath() : d.getTitle();
        return shorten(t, 40);
    }

    private static String enc(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String fmt(java.time.LocalDateTime t) {
        return t == null ? "未知时间" : t.toLocalDate().toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String shorten(String s, int max) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
