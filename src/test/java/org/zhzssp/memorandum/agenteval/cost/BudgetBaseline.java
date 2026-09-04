package org.zhzssp.memorandum.agenteval.cost;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 预算基线的读写：{@code src/test/resources/agent-eval/budget-baseline.json}。
 *
 * <h3>为什么它随代码一起提交</h3>
 * 见 {@link BudgetGate} 的类注释——核心是让<b>放宽预算成为一次可评审的动作</b>。
 *
 * <h3>写入时刻意做的两件事</h3>
 * <ol>
 *   <li><b>用例名排序</b>（{@link TreeMap}）。若按运行顺序落盘，
 *       JUnit 换个执行顺序就会产生一份<b>内容相同但 diff 满屏</b>的文件，
 *       "这次到底改了哪个用例的预算"就淹了。基线文件的全部价值在于它的 diff 可读。</li>
 *   <li><b>不写时间戳以外的运行环境信息</b>。写了 JVM 版本、机器名之类，
 *       每个人重新生成都会产生噪声 diff，同样冲淡真正的变化。</li>
 * </ol>
 *
 * <h3>为什么读走 classpath、写走文件系统</h3>
 * 与 {@code CassetteStore} 同一套路：读要能在打包后的测试 classpath 里拿到，
 * 写要落在源码树里（{@code src/test/resources}）好让人直接 {@code git diff}。
 * 若写到 {@code build/} 下，生成的基线永远进不了仓库，门禁也就永远没有基线可比。
 */
public final class BudgetBaseline {

    /** 写基线模式：{@code -Dagent.eval.budget=write}。 */
    public static final String MODE_PROPERTY = "agent.eval.budget";
    public static final String MODE_WRITE = "write";

    private static final String RESOURCE = "/agent-eval/budget-baseline.json";
    private static final Path SOURCE_PATH =
            Paths.get("src/test/resources/agent-eval/budget-baseline.json");

    /**
     * 纳入门禁的指标。
     *
     * <p>刻意<b>只有这两个</b>。token / 成本 / 延迟一律只报不禁，因为：
     * <ul>
     *   <li>token 与成本在回放下来自录制盒，不反映当前代码（见 {@link UsageAccumulator}）；</li>
     *   <li>延迟在回放下根本不存在（不联网）。</li>
     * </ul>
     * 把测不准的量放进门禁，只会制造一道<b>看着很严、其实随时误报或漏报</b>的闸门，
     * 用不了多久就会被加上 {@code @Disabled}。
     */
    public static final List<String> GATED_METRICS = List.of("llmCalls", "requestChars");

    private static final ObjectMapper OM = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private BudgetBaseline() {
    }

    public static boolean isWriteMode() {
        return MODE_WRITE.equalsIgnoreCase(System.getProperty(MODE_PROPERTY, ""));
    }

    /** 基线不存在时返回空 map——此时全部用例判为 UNTRACKED，不判红。 */
    public static Map<String, Map<String, Long>> load() {
        try (InputStream in = BudgetBaseline.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return Map.of();
            JsonNode root = OM.readTree(in);
            JsonNode cases = root.path("cases");
            Map<String, Map<String, Long>> out = new LinkedHashMap<>();
            cases.fieldNames().forEachRemaining(caseId -> {
                JsonNode m = cases.path(caseId);
                Map<String, Long> metrics = new LinkedHashMap<>();
                for (String metric : GATED_METRICS) {
                    if (m.has(metric)) metrics.put(metric, m.path(metric).asLong());
                }
                out.put(caseId, metrics);
            });
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("读取预算基线失败：" + RESOURCE, e);
        }
    }

    /**
     * 把实测值写回源码树，供 {@code git diff} 审阅后提交。
     *
     * <h4>★ 合并而不是覆盖</h4>
     * 本次<b>没跑到</b>的用例，其基线原样保留。
     *
     * <p>因为写基线的入口对所有评测任务都开着，而套件是分开的
     * （{@code agentEval} 跑回归集、{@code agentEvalCapability} 跑能力集）。
     * 若照直覆盖，一句 {@code agentEvalCapability "-Dagent.eval.budget=write"}
     * 就会把基线换成只剩能力集那几条——<b>13 条回归门禁全部静默失效</b>，
     * 之后它们一律判为 UNTRACKED，也就是不判。
     *
     * <p>这与刚修掉的"空录制覆盖既有录制盒"是<b>同一类事故</b>：
     * 整体重写 + 局部数据 = 静默的资产丢失。既然认得出这个形状，就不该再犯一次。
     *
     * <p>代价是删掉的用例会滞留在基线里。这个代价可以接受——
     * 报告会把它们列为 {@code MISSING}，而滞留一条无害的多余数据，
     * 远好过静默删掉一道门禁。<b>两类错误的代价不对称，就不该用对称的策略。</b>
     */
    public static void write(Map<String, Map<String, Long>> actual, String mode) {
        Map<String, Map<String, Long>> existing = load();
        Set<String> kept = keptFrom(existing, actual);
        if (!kept.isEmpty()) {
            System.out.println("[AgentEval] 本次未跑到、基线原样保留的用例：" + kept
                    + "\n           （若确已删除，请手工从基线里移除）");
        }
        writeAll(merge(existing, actual), mode);
    }

    /**
     * 合并语义的<b>纯函数</b>形态：本次实测覆盖同名条目，其余原样保留。
     *
     * <p>单独提出来是为了能脱离文件系统单测——
     * {@link #load()} 读 classpath 而 {@link #write} 写源码树，两者<b>不构成往返</b>，
     * 想靠"写一遍再读一遍"验证合并逻辑，只会写出一条永远为真的空断言。
     * （这条弯路是真走过的：第一版测试就是那么写的，两个断言里有一个是假的。）
     */
    static Map<String, Map<String, Long>> merge(Map<String, Map<String, Long>> existing,
                                                Map<String, Map<String, Long>> actual) {
        Map<String, Map<String, Long>> merged = new LinkedHashMap<>(existing);
        merged.putAll(actual);
        return merged;
    }

    /** 基线里有、本次没跑到的用例。 */
    static Set<String> keptFrom(Map<String, Map<String, Long>> existing,
                                Map<String, Map<String, Long>> actual) {
        Set<String> kept = new java.util.LinkedHashSet<>(existing.keySet());
        kept.removeAll(actual.keySet());
        return kept;
    }

    private static void writeAll(Map<String, Map<String, Long>> actual, String mode) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_comment", List.of(
                "Agent 评测的成本预算基线。★ 这份文件是给 code review 看的。",
                "",
                "它守的是「prompt 悄悄变胖」——那是本套件里唯一不会让任何断言变红的退化：",
                "往 system prompt 或工具描述里多写两句，调用次数不变、轨迹不变、断言全绿，",
                "只是每一次调用都贵了一点。requestChars 是活体测量，专治这个。",
                "",
                "回放模式下的 token 数来自录制盒（是录制当天的值），所以门禁<不>建立在 token 上。",
                "",
                "更新方式：gradlew agentEval \"-Dagent.eval.budget=write\"，然后把 diff 一起提交。",
                "diff 里若出现某个用例的 requestChars 显著上涨，那就是这次改动的成本代价，",
                "应当在 PR 里被解释，而不是顺手带过。"
        ));
        root.put("generatedAt", LocalDate.now().toString());
        root.put("generatedInMode", mode);
        root.put("gatedMetrics", GATED_METRICS);
        // 排序：让 diff 只反映真实变化，不反映执行顺序
        root.put("cases", new TreeMap<>(actual));

        try {
            Files.createDirectories(SOURCE_PATH.getParent());
            OM.writeValue(SOURCE_PATH.toFile(), root);
            System.out.println("[AgentEval] 预算基线已写入 " + SOURCE_PATH.toAbsolutePath()
                    + "\n           请 git diff 确认涨幅合理后再提交。");
        } catch (Exception e) {
            System.err.println("[AgentEval] 写预算基线失败：" + e.getMessage());
        }
    }
}
