package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codex 工作台分段导航：五页共用 fragment，选中态由 {@code codexNav} 决定。
 */
class CodexNavTest {

    private static final List<String> CODEX_PAGES = List.of(
            "codex.html",
            "distill.html",
            "checkpoint.html",
            "curate.html",
            "gap.html"
    );

    private static final List<String> TAB_HREFS = List.of(
            "/codex",
            "/codex/distill",
            "/codex/checkpoints",
            "/codex/curate",
            "/codex/gaps"
    );

    @Test
    @DisplayName("fragment 含五个分区 href，顺序为接入→定线→检验→策展→缺口")
    void fragmentHasFiveTabsInDailyOrder() throws IOException {
        String html = Files.readString(projectFile("src/main/resources/templates/fragments/codex-nav.html"));
        assertTrue(html.contains("th:fragment=\"chrome\""));
        assertTrue(html.contains("返回 Dashboard"));
        assertTrue(html.contains("href=\"/dashboard\""));

        int prev = -1;
        for (String href : TAB_HREFS) {
            int at = html.indexOf("href=\"" + href + "\"");
            assertTrue(at >= 0, "missing " + href);
            assertTrue(at > prev, href + " out of order");
            prev = at;
        }
    }

    @Test
    @DisplayName("传入 codexNav=curate 时仅策展 Tab 带 is-active")
    void curateTabIsActiveWhenCodexNavIsCurate() {
        String html = renderChrome("curate");
        assertTrue(html.contains("class=\"cx-seg-item is-active\"")
                || html.contains("class=\"cx-seg-item  is-active\""));

        Matcher items = Pattern.compile(
                "<a href=\"([^\"]+)\"[^>]*class=\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        int active = 0;
        boolean curateActive = false;
        while (items.find()) {
            String href = items.group(1);
            String cls = items.group(2);
            if (!cls.contains("cx-seg-item")) {
                continue;
            }
            boolean isActive = cls.contains("is-active");
            if (isActive) {
                active++;
                curateActive = "/codex/curate".equals(href);
            }
        }
        assertEquals(1, active, html);
        assertTrue(curateActive, html);
        assertTrue(html.contains("aria-current=\"page\""));
    }

    @Test
    @DisplayName("五份 Codex 页引入 fragment 与 css，不再各自堆页间箭头链接")
    void fivePagesShareChrome() throws IOException {
        Path templates = projectFile("src/main/resources/templates");
        for (String page : CODEX_PAGES) {
            String html = Files.readString(templates.resolve(page));
            assertTrue(html.contains("fragments/codex-nav :: chrome"), page + " missing nav fragment");
            assertTrue(html.contains("codex-nav.css"), page + " missing codex-nav.css");
            assertFalse(html.contains("蒸馏与定线 →"), page);
            assertFalse(html.contains("知识落地检验 →"), page);
            assertFalse(html.contains("← 知识仓库"), page);
            assertFalse(html.contains("← 返回仓库"), page);
        }
        String controller = Files.readString(
                projectFile("src/main/java/org/zhzssp/memorandum/feature/codex/controller/CodexViewController.java"));
        for (String tab : List.of("repos", "distill", "checkpoints", "curate", "gaps")) {
            assertTrue(controller.contains("\"codexNav\", \"" + tab + "\""), "controller missing " + tab);
        }
    }

    @Test
    @DisplayName("Dashboard 只保留跳出入口，不内嵌 Codex 分区")
    void dashboardKeepsSingleCodexExit() throws IOException {
        String html = Files.readString(projectFile("src/main/resources/templates/dashboard.html"));
        assertTrue(html.contains("href=\"/codex\""));
        assertFalse(html.contains("/codex/distill"));
        assertFalse(html.contains("/codex/checkpoints"));
        assertFalse(html.contains("/codex/curate"));
        assertFalse(html.contains("/codex/gaps"));
        assertFalse(html.contains("fragments/codex-nav"));
        int exits = count(html, "href=\"/codex\"");
        assertEquals(1, exits);
    }

    private static String renderChrome(String tab) {
        FileTemplateResolver resolver = new FileTemplateResolver();
        resolver.setPrefix(projectFile("src/main/resources/templates").toAbsolutePath() + "/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setEnableSpringELCompiler(true);
        Context ctx = new Context();
        ctx.setVariable("codexNav", tab);
        return engine.process("fragments/codex-nav", ctx);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = 0; (i = haystack.indexOf(needle, i)) >= 0; i += needle.length()) {
            n++;
        }
        return n;
    }

    private static Path projectFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8; i++) {
            if (Files.exists(dir.resolve("src/main/resources/templates/fragments/codex-nav.html"))) {
                return dir.resolve(relative);
            }
            dir = dir.getParent();
            if (dir == null) {
                break;
            }
        }
        throw new IllegalStateException("project root not found for " + relative);
    }
}
