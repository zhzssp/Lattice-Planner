package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.memory.AgentFact;
import org.zhzssp.memorandum.feature.agent.memory.AgentFactRepository;
import org.zhzssp.memorandum.feature.agent.memory.FactService;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * L1 单元测试：Facts 层（上下文工程 P1 第二步）。
 *
 * <h3>这组测试守的核心诉求</h3>
 * <p>facts 会被注入<strong>每一轮</strong>，所以「抽错」的代价比一次错误回答更大。
 * 因此最该守住的是：</p>
 * <ul>
 *   <li><strong>覆盖而非追加</strong>——同 key 新值把旧值标 SUPERSEDED；</li>
 *   <li><strong>{@code REJECTED} 永不再抽</strong>——用户判定过的错误不被自动重开；</li>
 *   <li><strong>低置信不入库</strong>——宁缺勿错；</li>
 *   <li><strong>易变 facts 走 history 而非 system</strong>——不打穿前缀缓存。</li>
 * </ul>
 */
class FactServiceTest {

    private AgentFactRepository repo;
    private LlmGateway llm;
    private ObjectMapper om;
    private FactService service;

    /** 记录 save 调用，便于断言「覆盖而非追加」。 */
    private final List<AgentFact> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(AgentFactRepository.class);
        llm = mock(LlmGateway.class);
        om = new ObjectMapper();
        service = new FactService(repo, llm, om);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxStable", 20);
        ReflectionTestUtils.setField(service, "maxVolatile", 15);
        ReflectionTestUtils.setField(service, "minConfidenceRaw", "MEDIUM");
        ReflectionTestUtils.setField(service, "stableGranularity", "DAY");

        saved.clear();
        // 默认无既有事实
        when(repo.findByUserIdAndFactKeyAndStatus(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repo.findTopByUserIdAndFactKeyOrderByUpdatedAtDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        // save 时记录
        doAnswer(inv -> {
            AgentFact f = inv.getArgument(0);
            saved.add(f);
            return f;
        }).when(repo).save(any(AgentFact.class));
    }

    private String llmJson(String... lines) {
        return String.join("\n", lines);
    }

    @Nested
    @DisplayName("抽取与置信度")
    class Extraction {

        @Test
        @DisplayName("明确陈述抽取为 HIGH，正常入库")
        void extractsHighConfidence() {
            when(llm.generateText(anyString())).thenReturn(llmJson(
                    "{\"key\":\"deadline.project-x\",\"value\":\"deadline 是下周五\","
                            + "\"kind\":\"VOLATILE\",\"confidence\":\"HIGH\"}"));
            service.extractAsync(1L, "s1", "我们这个项目的 deadline 定在下周五，务必完成", 0);
            assertEquals(1, saved.size());
            AgentFact f = saved.get(0);
            assertEquals("deadline.project-x", f.getFactKey());
            assertEquals(AgentFact.Confidence.HIGH, f.getConfidence());
            assertEquals(AgentFact.Kind.VOLATILE, f.getKind());
            assertEquals("s1", f.getSessionId());
        }

        @Test
        @DisplayName("★低置信候选（低于下限）不入库")
        void dropsLowConfidence() {
            // 下限 MEDIUM。LOW 必须被挡掉——facts 注入每一轮，抽错的污染面比一次错误回答大得多。
            // 缺失 confidence 字段等同 LOW：模型漏写不该蒙混成 MEDIUM 入库（宁缺勿错）。
            when(llm.generateText(anyString())).thenReturn(llmJson(
                    "{\"key\":\"guess.a\",\"value\":\"看起来像是下周\",\"kind\":\"VOLATILE\",\"confidence\":\"LOW\"}",
                    "{\"key\":\"missing.b\",\"value\":\"没写置信度\",\"kind\":\"VOLATILE\"}",
                    "{\"key\":\"ok.c\",\"value\":\"原文明确说了\",\"kind\":\"VOLATILE\",\"confidence\":\"HIGH\"}"));
            service.extractAsync(1L, "s1", "这是一条足够长的用户输入，用于触发事实抽取流程", 0);
            assertEquals(1, saved.size(), "LOW 与缺失 confidence 的候选都应被下限挡掉");
            assertEquals("ok.c", saved.get(0).getFactKey());
        }

        @Test
        @DisplayName("空 key 或空 value 的候选被丢弃")
        void dropsEmptyKeyOrValue() {
            when(llm.generateText(anyString())).thenReturn(llmJson(
                    "{\"key\":\"\",\"value\":\"无 key\",\"kind\":\"VOLATILE\",\"confidence\":\"HIGH\"}",
                    "{\"key\":\"x\",\"value\":\"\",\"kind\":\"VOLATILE\",\"confidence\":\"HIGH\"}",
                    "{\"key\":\"ok\",\"value\":\"有 key 有值\",\"kind\":\"VOLATILE\",\"confidence\":\"HIGH\"}"));
            service.extractAsync(1L, "s1", "这是一条足够长的用户输入，用于触发事实抽取流程", 0);
            assertEquals(1, saved.size(), "空 key 或空 value 的候选应被丢弃");
            assertEquals("ok", saved.get(0).getFactKey());
        }

        @Test
        @DisplayName("太短的输入不触发抽取（省 LLM 调用）")
        void skipsShortInput() {
            service.extractAsync(1L, "s1", "太短", 0);
            verify(llm, never()).generateText(anyString());
        }

        @Test
        @DisplayName("抽取含 source_quote 与 source_turn（可核对）")
        void storesSourceQuote() {
            String userInput = "我们这个项目的 deadline 定在下周五";
            when(llm.generateText(anyString())).thenReturn(llmJson(
                    "{\"key\":\"deadline.x\",\"value\":\"下周五\",\"kind\":\"VOLATILE\",\"confidence\":\"HIGH\"}"));
            service.extractAsync(1L, "s1", userInput, 3);
            assertEquals(1, saved.size());
            assertEquals(userInput, saved.get(0).getSourceQuote(),
                    "必须存原文片段，否则用户无法核对「凭什么说我有这条约束」");
            assertEquals(3, saved.get(0).getSourceTurn());
        }
    }

    @Nested
    @DisplayName("覆盖语义")
    class Supersede {

        @Test
        @DisplayName("同 key 新值 → 旧值标 SUPERSEDED，不删除")
        void supersedesExisting() {
            AgentFact old = new AgentFact();
            old.setId(1L);
            old.setUserId(1L);
            old.setFactKey("deadline.x");
            old.setFactValue("下周五");
            old.setStatus(AgentFact.Status.ACTIVE);
            when(repo.findByUserIdAndFactKeyAndStatus(1L, "deadline.x", AgentFact.Status.ACTIVE))
                    .thenReturn(Optional.of(old));

            when(llm.generateText(anyString())).thenReturn(llmJson(
                    "{\"key\":\"deadline.x\",\"value\":\"改到下周三\",\"kind\":\"VOLATILE\",\"confidence\":\"HIGH\"}"));
            service.extractAsync(1L, "s1", "deadline 改到下周三了，这是足够长的输入", 0);

            // 旧值被标 SUPERSEDED（save 被调用，状态已改）
            assertEquals(AgentFact.Status.SUPERSEDED, old.getStatus(),
                    "同 key 覆盖必须把旧值标 SUPERSEDED，而不是删除历史");
            // 新值落库为 ACTIVE
            assertTrue(saved.stream().anyMatch(f ->
                    f.getStatus() == AgentFact.Status.ACTIVE
                            && "deadline.x".equals(f.getFactKey())
                            && "改到下周三".equals(f.getFactValue())));
        }
    }

    @Nested
    @DisplayName("★REJECTED 永不再抽")
    class Rejected {

        @Test
        @DisplayName("★用户标错的 key 不会被再次抽取入库")
        void rejectedKeyNotReextracted() {
            AgentFact rejected = new AgentFact();
            rejected.setId(2L);
            rejected.setUserId(1L);
            rejected.setFactKey("deadline.x");
            rejected.setStatus(AgentFact.Status.REJECTED);
            // ★最近一条是 REJECTED（不限状态查询），必须挡住重新抽取
            when(repo.findTopByUserIdAndFactKeyOrderByUpdatedAtDesc(1L, "deadline.x"))
                    .thenReturn(Optional.of(rejected));
            when(repo.findByUserIdAndFactKeyAndStatus(anyLong(), anyString(), any()))
                    .thenReturn(Optional.empty());

            when(llm.generateText(anyString())).thenReturn(llmJson(
                    "{\"key\":\"deadline.x\",\"value\":\"又抽出来了\",\"kind\":\"VOLATILE\",\"confidence\":\"HIGH\"}"));
            service.extractAsync(1L, "s1", "这是一条足够长的输入，用于触发事实抽取", 0);

            assertTrue(saved.isEmpty(),
                    "REJECTED 的 key 绝不能被重新抽成 ACTIVE——用户判定过的错误不该被自动重开");
        }

        @Test
        @DisplayName("reject() 把事实标为 REJECTED")
        void rejectMarks() {
            AgentFact f = new AgentFact();
            f.setId(7L);
            f.setUserId(1L);
            f.setStatus(AgentFact.Status.ACTIVE);
            when(repo.findById(7L)).thenReturn(Optional.of(f));

            service.reject(1L, 7L);
            assertEquals(AgentFact.Status.REJECTED, f.getStatus());
        }
    }

    @Nested
    @DisplayName("注入片段")
    class Snippet {

        @Test
        @DisplayName("稳定 facts 注入 system 用（stableSnippet 返回纯列表，无占位）")
        void stableSnippetFormat() {
            AgentFact f = new AgentFact();
            f.setFactValue("习惯早上做深度工作");
            when(repo.findStableActiveCreatedBefore(eq(1L), any())).thenReturn(List.of(f));
            String s = service.stableSnippet(1L);
            assertTrue(s.contains("习惯早上做深度工作"));
            assertFalse(s.contains("[已知事实]"), "稳定 facts 走 system，由 handler 统一加段标题");
        }

        @Test
        @DisplayName("无稳定 facts 时返回空串（不污染 memoHash）")
        void stableSnippetEmpty() {
            when(repo.findStableActiveCreatedBefore(eq(1L), any())).thenReturn(List.of());
            assertEquals("", service.stableSnippet(1L));
        }

        @Test
        @DisplayName("★DAY 粒度只取今天零点前创建的稳定 facts（今天新抽的不进 system）")
        void dayGranularityCutsAtMidnight() {
            AgentFact f = new AgentFact();
            f.setFactValue("习惯早上做深度工作");
            when(repo.findStableActiveCreatedBefore(eq(1L), any())).thenReturn(List.of(f));

            service.stableSnippet(1L);

            // 卡点必须正好是今天零点：早一秒会漏掉昨天的，晚一秒会放今天新抽的进 system，
            // 而 system 段一变就打穿上游前缀缓存——这正是 DAY 粒度要买的东西。
            ArgumentCaptor<java.time.LocalDateTime> cutoff =
                    ArgumentCaptor.forClass(java.time.LocalDateTime.class);
            verify(repo).findStableActiveCreatedBefore(eq(1L), cutoff.capture());
            assertEquals(java.time.LocalDate.now().atStartOfDay(), cutoff.getValue());
            verify(repo, never()).findStableActive(anyLong());
        }

        @Test
        @DisplayName("非 DAY 粒度立即生效（走不带时间卡点的查询）")
        void nonDayGranularityAppliesImmediately() {
            ReflectionTestUtils.setField(service, "stableGranularity", "IMMEDIATE");
            AgentFact f = new AgentFact();
            f.setFactValue("习惯早上做深度工作");
            when(repo.findStableActive(1L)).thenReturn(List.of(f));

            assertTrue(service.stableSnippet(1L).contains("习惯早上做深度工作"));
            verify(repo, never()).findStableActiveCreatedBefore(anyLong(), any());
        }

        @Test
        @DisplayName("易变 facts 带 [已知事实] 前缀，走 history")
        void volatileSnippetFormat() {
            AgentFact f = new AgentFact();
            f.setFactValue("deadline 是下周五");
            when(repo.findVolatileActive(1L, "s1")).thenReturn(List.of(f));
            String s = service.volatileSnippet(1L, "s1");
            assertTrue(s.startsWith("[已知事实]"), "易变 facts 走 history 首条，需带显式标记");
            assertTrue(s.contains("deadline 是下周五"));
        }

        @Test
        @DisplayName("无易变 facts 时返回 null（调用方不加那段 user 消息）")
        void volatileSnippetNull() {
            when(repo.findVolatileActive(1L, "s1")).thenReturn(List.of());
            assertNull(service.volatileSnippet(1L, "s1"));
        }
    }
}
