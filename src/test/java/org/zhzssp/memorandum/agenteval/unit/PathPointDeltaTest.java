package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.index.FrontMatterParser;
import org.zhzssp.memorandum.feature.codex.path.LearningPathParser;
import org.zhzssp.memorandum.feature.codex.path.LearningPathRenderer;
import org.zhzssp.memorandum.feature.codex.path.PathApplyService;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("体系页要点行内改 → 单站 PATH_DELTA")
class PathPointDeltaTest {

    private static final String SAMPLE = """
            ---
            kind: learning-path
            version: 3
            cursor: s2
            ---

            ## s1 · 读 IREE 编译流水线
            - sources: `paper/iree.pdf`, `docs/learning-guides/iree-learning-guide.md`
            - lab: `iree-lab/`
            - next: s2

            | id | 级别 | 要点 |
            |----|------|------|
            | s1.p1 | MUST | 能画出 compile 的主路径 |
            | s1.p2 | SKIP | Codegen 后端细节，遇到再学 |

            ## s2 · 动手改一个 pass
            - sources: iree-lab/README.md
            - lab: iree-lab/

            | id | 级别 | 要点 |
            |----|------|------|
            | s2.p1 | MUST | 能改一处并预先说出结果 |
            """;

    private final LearningPathParser parser =
            new LearningPathParser(new FrontMatterParser(new ObjectMapper()));
    private final PathApplyService svc = new PathApplyService(
            parser, new LearningPathRenderer(), null, null, null, null, null);

    @Test
    @DisplayName("替换已有要点，级别可改为 SKIP，其它点保留")
    void replaceExistingPoint() {
        String delta = svc.pointDelta(SAMPLE, "s1", "s1.p1", "SKIP", "能口述 compile 主路径");
        var parsed = parser.parseDelta(delta);
        assertTrue(parsed.ok(), parsed.error());
        assertEquals(1, parsed.stations().size());
        var s1 = parsed.stations().get(0);
        assertEquals("s1", s1.id());
        assertEquals(2, s1.points().size());
        assertEquals("s1.p1", s1.points().get(0).id());
        assertEquals("SKIP", s1.points().get(0).level().name());
        assertEquals("能口述 compile 主路径", s1.points().get(0).statement());
        assertEquals("s1.p2", s1.points().get(1).id());
        assertFalse(delta.contains("能画出 compile 的主路径"));
        assertFalse(delta.contains("## s2 "));
    }

    @Test
    @DisplayName("没有 pointId 则追加 sN.p(max+1)")
    void appendGeneratesNextId() {
        String delta = svc.pointDelta(SAMPLE, "s1", "", "MUST", "能指出 dialect 边界");
        var parsed = parser.parseDelta(delta);
        assertTrue(parsed.ok(), parsed.error());
        var points = parsed.stations().get(0).points();
        assertEquals(3, points.size());
        assertEquals("s1.p3", points.get(2).id());
        assertEquals("MUST", points.get(2).level().name());
        assertEquals("能指出 dialect 边界", points.get(2).statement());
    }

    @Test
    @DisplayName("未知车站 / 空原文失败")
    void rejectsBadInput() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> svc.pointDelta(SAMPLE, "s9", "s9.p1", "MUST", "x"));
        assertTrue(missing.getMessage().contains("s9"));
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> svc.pointDelta(SAMPLE, "s1", "s1.p1", "MUST", "  "));
        assertTrue(empty.getMessage().contains("不能为空"));
    }
}
