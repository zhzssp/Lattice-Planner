package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.codex.repository.KbChunkRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.service.CodexMetrics;
import org.zhzssp.memorandum.feature.codex.service.CodexSearchService;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingClient;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingVectorCache;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接入仓库后 Git 检索必须开；properties 默认关是为了评测，不是为了挡住用户。
 */
@DisplayName("Git 检索闸门：接入即搜，不改 pkm.rag.git.enabled 默认值")
class CodexSearchGateTest {

    private CodexSearchService search;
    private RepoRegistryService registry;

    @BeforeEach
    void setUp() {
        registry = mock(RepoRegistryService.class);
        search = new CodexSearchService(
                mock(KbChunkRepository.class),
                mock(KbDocumentRepository.class),
                mock(EmbeddingClient.class),
                mock(EmbeddingVectorCache.class),
                registry,
                mock(CodexMetrics.class));
        ReflectionTestUtils.setField(search, "gitSearchEnabled", false);
        ReflectionTestUtils.setField(search, "alpha", 0.4);
        ReflectionTestUtils.setField(search, "candidates", 50);
    }

    @Test
    @DisplayName("未接入且配置关：不搜，也不去列仓库")
    void propertyOffUnconnectedNotSearchable() {
        when(registry.readable(1L)).thenReturn(false);
        assertFalse(search.enabled());
        assertFalse(search.searchable(1L));
        assertFalse(search.searchable(null));
        assertTrue(search.search(1L, "IREE compile", 6).isEmpty());
        verify(registry, never()).listEnabled(anyLong());
    }

    @Test
    @DisplayName("已接入：配置仍关，但 searchable，会去列仓库")
    void connectedSearchableEvenIfPropertyOff() {
        when(registry.readable(1L)).thenReturn(true);
        when(registry.listEnabled(1L)).thenReturn(List.of());
        assertFalse(search.enabled(), "properties 仍默认关，评测不被连带打开");
        assertTrue(search.searchable(1L));
        assertTrue(search.search(1L, "IREE compile", 6).isEmpty());
        verify(registry).listEnabled(1L);
    }

    @Test
    @DisplayName("配置强制开：即使未接入也 searchable（评测显式打开时）")
    void propertyOnSearchableWithoutConnect() {
        ReflectionTestUtils.setField(search, "gitSearchEnabled", true);
        when(registry.readable(1L)).thenReturn(false);
        when(registry.listEnabled(1L)).thenReturn(List.of());
        assertTrue(search.enabled());
        assertTrue(search.searchable(1L));
        search.search(1L, "IREE compile", 6);
        verify(registry).listEnabled(1L);
    }
}
