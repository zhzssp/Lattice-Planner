package org.zhzssp.memorandum.agenteval.cassette;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 请求指纹的<b>规范化</b>验证：语义相同的 prompt 必须算出相同指纹，
 * 语义变了必须算出不同指纹。
 *
 * <h3>★ 这里守的是一个真实踩过的坑</h3>
 * 漂移警告（"该用例录制可能已过期"）的<b>全部价值</b>建立在一个前提上：
 * <b>平时恒为 0</b>。只要掺进恒定噪声，它立刻退化成一行没人看的日志——
 * 而真正的 prompt 漂移就淹没在噪声里了。
 *
 * <p>它曾经就是这样坏掉的：生产代码里 {@code LlmGateway.generateText}
 * 用 {@code Map.of("role", …, "content", …)} 构造消息，而 {@code Map.of}
 * 的迭代顺序取决于 {@code ImmutableCollections.SALT}——<b>每次 JVM 启动重新随机</b>。
 * 录制是一个进程、回放是另一个进程，两边把同一条消息序列化成不同的 key 顺序，
 * 指纹必然对不上。结果是<b>每次回放都稳定报 2 条漂移</b>，
 * 而 prompt 一个字都没改过。
 *
 * <p>所以指纹计算改用一个开了 {@code ORDER_MAP_ENTRIES_BY_KEYS} 的序列化器。
 * 这不是打补丁：JSON 对象的 key 顺序<b>不携带语义</b>，
 * 指纹要的恰恰是"语义等价则相等"。
 */
@DisplayName("录制盒 · 指纹规范化")
class FingerprintStabilityTest {

    private static LlmTransport.ChatRequest req(List<Map<String, String>> messages) {
        return new LlmTransport.ChatRequest(
                "https://api.example.com", "key", "deepseek-chat", messages,
                0.3, 30, LlmTransport.Purpose.TEXT);
    }

    /** 与 {@code Map.of} 可能产生的两种迭代顺序等价的显式构造。 */
    private static Map<String, String> ordered(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    @Nested
    @DisplayName("这个缺陷本身")
    class TheDefect {

        @Test
        @DisplayName("★key 顺序不同、内容相同的消息，指纹必须相等")
        void keyOrderDoesNotAffectFingerprint() {
            String a = CassetteStore.fingerprint(req(List.of(
                    ordered("role", "system", "content", "你是助手"),
                    ordered("role", "user", "content", "今年有几个月"))));

            String b = CassetteStore.fingerprint(req(List.of(
                    ordered("content", "你是助手", "role", "system"),
                    ordered("content", "今年有几个月", "role", "user"))));

            assertEquals(a, b,
                    "key 顺序不携带语义。两者不等，回放就会对着没改过的 prompt 报漂移");
        }

        @Test
        @DisplayName("Map.of 构造的消息，与显式有序构造的指纹一致")
        void immutableMapMatchesOrderedMap() {
            // Map.of 的迭代顺序按 JVM 盐随机，本用例在任一盐下都必须通过
            String viaMapOf = CassetteStore.fingerprint(req(List.of(
                    Map.of("role", "system", "content", "S"),
                    Map.of("role", "user", "content", "U"))));

            String viaLinked = CassetteStore.fingerprint(req(List.of(
                    ordered("role", "system", "content", "S"),
                    ordered("role", "user", "content", "U"))));

            assertEquals(viaLinked, viaMapOf,
                    "生产的 generateText 走 Map.of，录制/回放必须算出同一个指纹");
        }
    }

    @Nested
    @DisplayName("规范化不能过头")
    class StillSensitive {

        @Test
        @DisplayName("prompt 内容变了，指纹必须变——否则漂移检测就没用了")
        void contentChangeStillDetected() {
            String before = CassetteStore.fingerprint(req(List.of(
                    ordered("role", "user", "content", "查一下本周任务"))));
            String after = CassetteStore.fingerprint(req(List.of(
                    ordered("role", "user", "content", "查一下本月任务"))));

            assertNotEquals(before, after, "指纹对内容不敏感的话，它就什么都不守了");
        }

        @Test
        @DisplayName("消息顺序（不是 key 顺序）变了，指纹必须变")
        void messageOrderStillMatters() {
            Map<String, String> sys = ordered("role", "system", "content", "S");
            Map<String, String> usr = ordered("role", "user", "content", "U");

            assertNotEquals(
                    CassetteStore.fingerprint(req(List.of(sys, usr))),
                    CassetteStore.fingerprint(req(List.of(usr, sys))),
                    "messages 是有序的对话，顺序携带语义，不能被规范化掉");
        }

        @Test
        @DisplayName("模型或温度变了，指纹必须变")
        void modelAndTemperatureStillMatter() {
            List<Map<String, String>> msgs = List.of(ordered("role", "user", "content", "同一句话"));

            String base = CassetteStore.fingerprint(req(msgs));
            String otherModel = CassetteStore.fingerprint(new LlmTransport.ChatRequest(
                    "https://api.example.com", "key", "deepseek-reasoner", msgs,
                    0.3, 30, LlmTransport.Purpose.TEXT));
            String otherTemp = CassetteStore.fingerprint(new LlmTransport.ChatRequest(
                    "https://api.example.com", "key", "deepseek-chat", msgs,
                    0.9, 30, LlmTransport.Purpose.TEXT));

            assertNotEquals(base, otherModel, "换模型等于换了被测对象");
            assertNotEquals(base, otherTemp, "温度直接决定采样分布");
        }

        @Test
        @DisplayName("只是过了一天，指纹不能变")
        void datesAreNormalisedAway() {
            // system prompt 里带「今天是 …」，若不规范化，每天全员漂移
            assertEquals(
                    CassetteStore.fingerprint(req(List.of(
                            ordered("role", "system", "content", "今天是 2026-09-04，请规划")))),
                    CassetteStore.fingerprint(req(List.of(
                            ordered("role", "system", "content", "今天是 2026-09-05，请规划")))),
                    "日期是易变片段，已在 VOLATILE_PATTERNS 里剔除");
        }
    }
}
