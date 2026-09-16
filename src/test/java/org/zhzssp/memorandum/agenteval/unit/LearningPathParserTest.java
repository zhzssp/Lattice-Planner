package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.entity.KbPoint;
import org.zhzssp.memorandum.feature.codex.index.FrontMatterParser;
import org.zhzssp.memorandum.feature.codex.path.LearningPathParser;
import org.zhzssp.memorandum.feature.codex.path.LearningPathRenderer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("学习路径窄口径解析")
class LearningPathParserTest {

    private final LearningPathParser parser =
            new LearningPathParser(new FrontMatterParser(new ObjectMapper()));
    private final LearningPathRenderer renderer = new LearningPathRenderer();

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

    @Test
    @DisplayName("设计文档中的路径文件能解析出站、MUST/SKIP、cursor")
    void parsesCanonicalSample() {
        var p = parser.parseDocument(SAMPLE, true);
        assertTrue(p.ok(), p.error());
        assertEquals(3, p.version());
        assertEquals("s2", p.cursor());
        assertEquals(2, p.stations().size());
        assertEquals("s1", p.stations().get(0).id());
        assertEquals("读 IREE 编译流水线", p.stations().get(0).title());
        assertEquals(2, p.stations().get(0).sources().size());
        assertEquals("iree-lab/", p.stations().get(0).lab());
        assertEquals(2, p.mustCount());
        assertEquals(1, p.skipCount());
        assertEquals(KbPoint.Level.MUST, p.stations().get(0).points().get(0).level());
        assertEquals("s2.p1", p.stations().get(1).points().get(0).id());
    }

    @Test
    @DisplayName("缺少 kind 且 requireKind 时失败")
    void requireKind() {
        var p = parser.parseDocument("## s1 · 只有站\n", true);
        assertFalse(p.ok());
        assertTrue(p.error().contains("kind"));
    }

    @Test
    @DisplayName("解析失败不静默丢行：重复 id 带原因")
    void duplicateStationFails() {
        String raw = """
                ---
                kind: learning-path
                ---
                ## s1 · A
                ## s1 · B
                """;
        var p = parser.parseDocument(raw, true);
        assertFalse(p.ok());
        assertTrue(p.error().contains("重复"));
    }

    @Test
    @DisplayName("级别不是 MUST/SKIP 则失败")
    void badLevelFails() {
        String raw = """
                ---
                kind: learning-path
                ---
                ## s1 · A
                | id | 级别 | 要点 |
                |----|------|------|
                | s1.p1 | MAYBE | 含糊 |
                """;
        var p = parser.parseDocument(raw, true);
        assertFalse(p.ok());
        assertTrue(p.error().contains("MUST/SKIP"));
    }

    @Test
    @DisplayName("PATH_DELTA 无 front-matter 也能解析")
    void parseDelta() {
        var p = parser.parseDelta("""
                ## s9 · 新站
                | id | 级别 | 要点 |
                |----|------|------|
                | s9.p1 | MUST | 能复述主路径 |
                """);
        assertTrue(p.ok(), p.error());
        assertEquals(1, p.stations().size());
        assertEquals("s9", p.stations().get(0).id());
    }

    @Test
    @DisplayName("渲染后再解析，站与要点一致")
    void roundTrip() {
        var parsed = parser.parseDocument(SAMPLE, true);
        assertTrue(parsed.ok(), parsed.error());
        var again = parser.parseDocument(renderer.render(parsed), true);
        assertTrue(again.ok(), again.error());
        assertEquals(parsed.stations().size(), again.stations().size());
        assertEquals(parsed.mustCount(), again.mustCount());
        assertEquals("s2", again.cursor());
    }

    @Test
    @DisplayName("补丁按 station_id 整站替换并追加新站")
    void mergeReplacesStation() {
        var existing = parser.parseDocument(SAMPLE, true);
        var delta = parser.parseDelta("""
                ## s1 · 读 IREE 编译流水线（修订）
                | id | 级别 | 要点 |
                |----|------|------|
                | s1.p1 | MUST | 能画出 compile 的主路径（修订） |
                ## s3 · 新站
                | id | 级别 | 要点 |
                |----|------|------|
                | s3.p1 | SKIP | 以后再学 |
                """);
        assertTrue(delta.ok(), delta.error());
        var merged = renderer.merge(existing, delta);
        assertEquals(3, merged.stations().size());
        assertEquals("读 IREE 编译流水线（修订）", merged.stations().get(0).title());
        assertEquals("s3", merged.stations().get(2).id());
        assertEquals(1, merged.stations().get(0).points().size());
    }

    @Test
    @DisplayName("单站渲染可作为 PATH_DELTA 再解析")
    void renderStationIsDelta() {
        var parsed = parser.parseDocument(SAMPLE, true);
        assertTrue(parsed.ok(), parsed.error());
        String delta = renderer.renderStation(parsed.stations().get(0));
        var again = parser.parseDelta(delta);
        assertTrue(again.ok(), again.error());
        assertEquals(1, again.stations().size());
        assertEquals("s1", again.stations().get(0).id());
        assertEquals(2, again.stations().get(0).points().size());
    }

    @Test
    @DisplayName("cursor 指向不存在的站则失败")
    void missingCursorFails() {
        String raw = """
                ---
                kind: learning-path
                cursor: s9
                ---
                ## s1 · A
                """;
        var p = parser.parseDocument(raw, true);
        assertFalse(p.ok());
        assertTrue(p.error().contains("cursor"));
    }
}
