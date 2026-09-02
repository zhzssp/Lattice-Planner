package org.zhzssp.memorandum.agenteval.rag.faithfulness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;

import java.util.List;
import java.util.Map;

/**
 * 用 LLM 判定"答复里的说法能否由 context 推出"。
 *
 * <h3>为什么这个指标不需要参考答案</h3>
 * 忠实度问的是<b>答复与 context 的关系</b>，不是答复与标准答案的关系。
 * 这一点决定了它的运行时机：<b>可以挂在真实对话上持续跑</b>，
 * 而 recall 那类需要参考答案的指标只能在金标集上跑。
 * 这是 RAGAS 生态里最实用的一条设计——把"要不要参考答案"
 * 直接映射成"能不能在线上用"。
 *
 * <h3>与 P3 裁判共享的三条纪律</h3>
 * 默认关闭、裁判模型区别于被判模型、必须先对人工标注校准。
 * 详见 {@code LlmJudge} 的类注释，此处不重复。
 *
 * <h3>为什么绕开 @Primary 的传输层</h3>
 * 评测运行时 {@code LlmTransport} 被替换成了回放/录制实现。裁判若走那条路，
 * 回放模式下会因为盒子里没有裁判请求而报错，录制模式下则会把裁判调用
 * <b>污染进录制盒</b>。
 */
public final class LlmFaithfulnessJudge {

    public static final String ENABLE_PROPERTY = "agent.eval.judge";
    public static final String MODEL_PROPERTY = "agent.eval.judge.model";
    public static final String KEY_ENV = "AGENT_EVAL_JUDGE_KEY";

    /**
     * rubric。
     *
     * <p>刻意反复强调"<b>不问对不对，只问有没有据</b>"：
     * 裁判模型的本能是评价答案质量，不加约束的话
     * 它会把"说法正确但 context 里没有"判成 SUPPORTED，
     * 而那恰恰是 RAG 里最该被抓出来的一类——<b>用户以为在读自己的笔记，
     * 实际读到的是模型的记忆</b>。
     */
    private static final String RUBRIC = """
            你是一个严格的评审。下面给你一个问题、一段检索到的资料（context）、以及一段答复。

            请只判断一件事：【答复里的说法，能不能由 context 推出来】。

            特别注意：
            - 【不要】判断答复对不对。一句话完全正确、但 context 里没有依据，
              仍然要扣分——因为使用者会以为那是资料里的内容。
            - context 为空时，任何具体内容都属于无据。

            评分口径：
            2 = 答复里的说法全部能在 context 中找到依据
            1 = 主干有据，但夹带了 context 里没有的具体细节（数字、版本号、参数、专有名词）
            0 = 关键说法在 context 里毫无依据，或与 context 相矛盾
            U = 无法判断

            只返回 JSON，不要任何其它内容：
            {"reason": "先写判断依据，指出具体是哪句话没有据，一到两句", "score": "2 或 1 或 0 或 U"}
            """;

    private final LlmTransport http;
    private final ObjectMapper om;
    private final String model;
    private final String apiKey;
    private final String baseUrl;

    public LlmFaithfulnessJudge(LlmTransport http, ObjectMapper om, String baseUrl) {
        this.http = http;
        this.om = om;
        this.baseUrl = baseUrl;
        this.model = System.getProperty(MODEL_PROPERTY, "deepseek-chat");
        this.apiKey = System.getenv(KEY_ENV);
    }

    /** 是否已显式开启裁判。默认关闭——裁判不该在每次 CI 里跑。 */
    public static boolean enabled() {
        return "on".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "off"));
    }

    /** 开启了但没给 key 时应当明确报错：静默跳过会让人以为跑过了。 */
    public void requireUsable() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "已开启 LLM 裁判但未提供 API Key。请设置环境变量 " + KEY_ENV
                            + "。PowerShell：$env:" + KEY_ENV + " = \"sk-xxx\"");
        }
    }

    public String name() {
        return "LLM 忠实度裁判(" + model + ")";
    }

    public Verdict score(FaithfulnessSample sample) {
        String context = sample.context() == null || sample.context().isEmpty()
                ? "（检索没有返回任何内容）"
                : String.join("\n---\n", sample.context());
        String user = "【问题】\n" + sample.question()
                + "\n\n【context】\n" + context
                + "\n\n【答复】\n" + sample.answer();

        try {
            LlmTransport.ChatResponse resp = http.chat(new LlmTransport.ChatRequest(
                    baseUrl, apiKey, model,
                    List.of(Map.of("role", "system", "content", RUBRIC),
                            Map.of("role", "user", "content", user)),
                    // 判分要的是可复现而非多样性，故用低温。
                    // 注意这与录制被判对象时"不要设 0"的要求方向相反：
                    // 那边压方差会让 pass^k 失去意义，这边压方差正是目的
                    0.0, 60, LlmTransport.Purpose.TEXT));
            return parse(resp.content());
        } catch (Exception e) {
            // 调用失败判 U 而不是判 0：把基础设施故障算成"模型在编造"
            // 会让指标凭空变差，且故障一恢复分数就跳，根本无法归因
            return new Verdict(Faithfulness.UNCERTAIN, "裁判调用失败：" + e.getMessage());
        }
    }

    /** 容忍模型在 JSON 外包一层 ```json 围栏——这是实践中最常见的格式偏差。 */
    Verdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Verdict(Faithfulness.UNCERTAIN, "裁判返回空内容");
        }
        String body = raw.trim();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new Verdict(Faithfulness.UNCERTAIN, "裁判返回非 JSON：" + truncate(body));
        }
        try {
            JsonNode n = om.readTree(body.substring(start, end + 1));
            return new Verdict(Faithfulness.parse(n.path("score").asText(null)),
                    n.path("reason").asText(""));
        } catch (Exception e) {
            return new Verdict(Faithfulness.UNCERTAIN, "裁判 JSON 解析失败：" + truncate(body));
        }
    }

    private static String truncate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    public record Verdict(Faithfulness score, String reason) {}
}
