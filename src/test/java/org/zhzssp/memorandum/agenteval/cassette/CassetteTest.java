package org.zhzssp.memorandum.agenteval.cassette;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 录制盒的多试次结构与<b>向后兼容</b>验证。
 *
 * <p>兼容性在这里不是锦上添花：仓库里已有 9 个旧格式录制盒，
 * 若新代码读不了它们，等于把现有全部评测资产一次性作废。
 */
@DisplayName("录制盒 · 多试次与旧格式兼容")
class CassetteTest {

    private static final ObjectMapper OM = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static Cassette.LlmInteraction interaction(String content) {
        return new Cassette.LlmInteraction(0, "CHAT", null, "digest", content, null);
    }

    /* ---- 旧格式 ---- */

    @Test
    @DisplayName("旧格式的 interactions 被当作 trial 0 读取")
    void legacyInteractionsReadAsTrialZero() throws Exception {
        String legacy = """
                {
                  "caseId": "legacy_case",
                  "recordedAt": "2026-09-01T11:30:00",
                  "recordedModel": "deepseek-chat",
                  "_comment": "旧格式，无 trials 字段",
                  "interactions": [
                    {"index":0,"purpose":"CHAT","fingerprint":null,
                     "messagesDigest":"d","responseContent":"hello","responseUsageJson":null}
                  ]
                }
                """;

        Cassette c = OM.readValue(legacy, Cassette.class);

        assertEquals(1, c.trialCount());
        assertNotNull(c.trial(0));
        assertEquals("hello", c.at(0, 0).responseContent());
        assertEquals(1, c.size(0));
    }

    /**
     * <b>这条是 pass^k 正确性的地基。</b>
     *
     * <p>旧盒子只有一条轨迹。若 {@code trial(1)} 回退到 trial 0，
     * 多试次回放就会把同一条轨迹重放 k 遍——k 次结果完全相同、方差恒为 0，
     * pass^k 退化成 pass^1，报告上会出现一个漂亮且完全虚假的可靠性数字。
     * 所以这里必须返回 null，让回放层抛出明确错误。
     */
    @Test
    @DisplayName("旧格式的 trial 1 必须返回 null，不能回退到 trial 0")
    void legacyHasNoSecondTrial() throws Exception {
        String legacy = """
                {"caseId":"c","interactions":[
                  {"index":0,"purpose":"CHAT","fingerprint":null,
                   "messagesDigest":"d","responseContent":"only","responseUsageJson":null}]}
                """;

        Cassette c = OM.readValue(legacy, Cassette.class);

        assertNull(c.trial(1), "旧盒子不该凭空多出第二次试验");
        assertNull(c.at(1, 0));
        assertEquals(0, c.size(1));
    }

    /* ---- 新格式 ---- */

    @Test
    @DisplayName("多试次写入与读取互不串扰")
    void multiTrialIsolation() {
        Cassette c = new Cassette("multi", "now", "deepseek-chat");
        c.add(0, interaction("trial-0-step-0"));
        c.add(0, interaction("trial-0-step-1"));
        c.add(1, interaction("trial-1-step-0"));
        c.add(2, interaction("trial-2-step-0"));

        assertEquals(3, c.trialCount());
        assertEquals(2, c.size(0));
        assertEquals(1, c.size(1));
        assertEquals("trial-0-step-1", c.at(0, 1).responseContent());
        assertEquals("trial-1-step-0", c.at(1, 0).responseContent());
        assertEquals("trial-2-step-0", c.at(2, 0).responseContent());
        assertNull(c.at(3, 0), "未录制的试次应为 null");
    }

    @Test
    @DisplayName("从非 0 试次开始写入时自动补齐前置空试次，不越界")
    void sparseTrialIndexIsPadded() {
        Cassette c = new Cassette("sparse", "now", null);
        c.add(2, interaction("x"));

        assertEquals(3, c.trialCount());
        assertEquals(0, c.size(0));
        assertEquals(0, c.size(1));
        assertEquals(1, c.size(2));
    }

    @Test
    @DisplayName("对旧格式对象追加新试次时，原 interactions 被收编为 trial 0")
    void appendingToLegacyMigratesInPlace() throws Exception {
        Cassette c = OM.readValue("""
                {"caseId":"c","interactions":[
                  {"index":0,"purpose":"CHAT","fingerprint":null,
                   "messagesDigest":"d","responseContent":"old","responseUsageJson":null}]}
                """, Cassette.class);

        c.add(1, interaction("new"));

        assertEquals(2, c.trialCount());
        assertEquals("old", c.at(0, 0).responseContent(), "原轨迹不能在迁移中丢失");
        assertEquals("new", c.at(1, 0).responseContent());
    }

    /* ---- 序列化 ---- */

    @Test
    @DisplayName("新录制序列化为 trials，且不再写出空的 interactions 字段")
    void serializesToTrialsOnly() throws Exception {
        Cassette c = new Cassette("ser", "now", "deepseek-chat");
        c.add(0, interaction("a"));
        c.add(1, interaction("b"));

        String json = OM.writeValueAsString(c);

        assertTrue(json.contains("\"trials\""), "应写出 trials");
        assertFalse(json.contains("\"interactions\""),
                "不应写出 interactions，否则文件体积翻倍且两份数据可能不一致");

        Cassette back = OM.readValue(json, Cassette.class);
        assertEquals(2, back.trialCount());
        assertEquals("b", back.at(1, 0).responseContent());
    }
}
