package org.zhzssp.memorandum.agenteval.cassette;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.agenteval.cost.UsageAccumulator;
import org.zhzssp.memorandum.agenteval.transport.RecordingLlmTransport;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 录制失败时<b>不得毁掉既有录制</b>。
 *
 * <h3>这条守的是一次真实的数据丢失</h3>
 * 录制写盘是<b>整盒重写</b>（一个 caseId 一个文件，含全部试次）。
 * 于是上游一次 HTTP 402 就足以让本次试验录到 0 条交互，
 * 照常写盘的话，磁盘上那盒历史录制会被一个只剩两个字段的空壳覆盖。
 *
 * <p>更危险的是它<b>不会以"数据丢失"的形式暴露</b>：
 * 测试早就因为"LLM 调用失败"红了，人的注意力全在余额上，
 * 根本不会想到红色背后还顺手抹掉了几十条真实轨迹。
 * 而录制盒是花钱买来的、且模型行为不可复现——重录也回不到原样。
 *
 * <p>所以这条断言的价值不在于"逻辑复杂"，而在于
 * <b>把一次已经发生过的破坏钉住，让它不能再发生第二次</b>。
 */
@DisplayName("录制盒 · 空录制不覆盖既有盒子")
class EmptyRecordingGuardTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 只桩 chat 的上游；embed 用不到。 */
    private abstract static class ChatOnly implements LlmTransport {
        @Override
        public List<float[]> embed(EmbedRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    /** 永远抛错的上游，模拟余额不足 / 限流 / 断网。 */
    private static final LlmTransport FAILING = new ChatOnly() {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new LlmTransportException("HTTP 402 - Insufficient Balance", null);
        }
    };

    private static LlmTransport.ChatRequest anyRequest() {
        return new LlmTransport.ChatRequest("http://x", "k", "m",
                List.of(Map.of("role", "user", "content", "hi")),
                0.0, 30, LlmTransport.Purpose.CHAT);
    }

    @Test
    @DisplayName("★上游全失败时，flush 不写盘——既有录制必须原样幸存")
    void failedRecordingDoesNotOverwrite() {
        String caseId = "__guard_probe__";
        var transport = new RecordingLlmTransport(FAILING, OM, new UsageAccumulator());

        try {
            // 1) 先造一盒"珍贵的历史录制"
            transport.beginCase(caseId, 0);
            var good = new Cassette();
            good.setCaseId(caseId);
            good.add(0, new Cassette.LlmInteraction(
                    0, "CHAT", "fp", "digest", "珍贵的历史回答", null, 123L));
            CassetteStore.save(good);
            assertTrue(CassetteStore.exists(caseId));

            // 2) 再模拟一次失败的重录：调用抛错，本次试验 0 条交互
            transport.beginCase(caseId, 0);
            try {
                transport.chat(anyRequest());
            } catch (RuntimeException expected) {
                // 正是我们要模拟的上游失败
            }
            transport.flush();

            // 3) 历史必须还在
            Cassette after = CassetteStore.load(caseId);
            assertEquals(1, after.size(0),
                    "空录制覆盖了既有录制——这是不可挽回的资产丢失，录制要花钱且不可复现");
            assertEquals("珍贵的历史回答", after.at(0, 0).responseContent());
        } finally {
            CassetteStore.pathFor(caseId).toFile().delete();
        }
    }

    @Test
    @DisplayName("正常录到内容时照常写盘——守护不能把正常路径也一起挡了")
    void successfulRecordingStillWrites() {
        String caseId = "__guard_probe_ok__";
        LlmTransport ok = new ChatOnly() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.of("正常回答");
            }
        };
        var transport = new RecordingLlmTransport(ok, OM, new UsageAccumulator());

        try {
            transport.beginCase(caseId, 0);
            transport.chat(anyRequest());
            transport.flush();

            assertTrue(CassetteStore.exists(caseId));
            assertEquals("正常回答", CassetteStore.load(caseId).at(0, 0).responseContent());
        } finally {
            CassetteStore.pathFor(caseId).toFile().delete();
        }
    }

    @Test
    @DisplayName("录制时会把真实上游耗时一并存进盒子（回放不联网，这是唯一的采集时机）")
    void latencyIsCaptured() {
        String caseId = "__guard_probe_lat__";
        LlmTransport slow = new ChatOnly() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
                return ChatResponse.of("慢回答");
            }
        };
        var transport = new RecordingLlmTransport(slow, OM, new UsageAccumulator());

        try {
            transport.beginCase(caseId, 0);
            transport.chat(anyRequest());
            transport.flush();

            Long ms = CassetteStore.load(caseId).at(0, 0).upstreamLatencyMs();
            assertFalse(ms == null, "延迟没有落盘，报告里就只能永远显示 n/a");
            assertTrue(ms >= 10, "记录的应是真实往返耗时，实测 " + ms + "ms");
        } finally {
            CassetteStore.pathFor(caseId).toFile().delete();
        }
    }
}
