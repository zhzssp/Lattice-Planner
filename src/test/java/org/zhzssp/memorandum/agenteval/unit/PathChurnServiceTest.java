package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.path.PathChurnService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("路径 churn：点数增减与站顺序，不是 0–100 分")
class PathChurnServiceTest {

    @Test
    @DisplayName("相对上一版：新增/删除要点与站顺序改动可分计")
    void diffsAddedRemovedReorder() {
        PathChurnService.Diff first = PathChurnService.diff(
                List.of(), Set.of(), List.of("s1", "s2"), Set.of("s1.p1", "s2.p1"));
        assertEquals(2, first.added());
        assertEquals(0, first.removed());
        assertFalse(first.reordered());

        PathChurnService.Diff reorder = PathChurnService.diff(
                List.of("s1", "s2"), Set.of("s1.p1", "s2.p1"),
                List.of("s2", "s1"), Set.of("s1.p1", "s2.p1"));
        assertEquals(0, reorder.added());
        assertEquals(0, reorder.removed());
        assertTrue(reorder.reordered());

        PathChurnService.Diff swap = PathChurnService.diff(
                List.of("s1"), Set.of("a", "b"),
                List.of("s1"), Set.of("b", "c"));
        assertEquals(1, swap.added());
        assertEquals(1, swap.removed());
        assertFalse(swap.reordered());
    }
}
