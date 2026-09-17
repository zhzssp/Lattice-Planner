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
 * 应用顶栏 规划|知识；知识二级 体系|资料|笔记|工具。
 */
class CodexNavTest {

    private static final List<String> KNOWLEDGE_PAGES = List.of(
            "codex.html",
            "distill.html",
            "codex-notes.html",
            "codex-tools.html",
            "checkpoint.html",
            "curate.html",
            "gap.html"
    );

    private static final List<String> TAB_HREFS = List.of(
            "/codex",
            "/codex/distill",
            "/codex/notes",
            "/codex/tools"
    );

    @Test
    @DisplayName("应用顶栏只有规划、知识、设置")
    void appNavHasTwoDestinations() throws IOException {
        String html = Files.readString(projectFile("src/main/resources/templates/fragments/app-nav.html"));
        assertTrue(html.contains("th:fragment=\"chrome\""));
        assertTrue(html.contains(">规划</a>"));
        assertTrue(html.contains(">知识</a>"));
        assertTrue(html.contains(">设置</a>"));
        assertTrue(html.contains("href=\"/dashboard\""));
        assertTrue(html.contains("href=\"/codex\""));
        assertTrue(html.contains("href=\"/preference/settings\""));
        assertTrue(html.contains("lp-app-nav-slot"), "left slot keeps 规划/知识 optically centered");
        assertFalse(html.contains("我的笔记"));
        assertFalse(html.contains("知识仓库"));
    }

    @Test
    @DisplayName("目的地顶栏居中、字号大于设置，选中态有底边")
    void appNavCentersLargeDestinations() throws IOException {
        String css = Files.readString(projectFile("src/main/resources/static/css/app-nav.css"));
        assertTrue(css.contains("grid-template-columns: 1fr auto 1fr"));
        assertTrue(css.contains("font-size: 1.45rem"));
        assertTrue(css.contains(".lp-app-dest a.is-active::after"));
        int dest = css.indexOf(".lp-app-dest a {");
        int settings = css.indexOf(".lp-app-nav-right a {");
        assertTrue(dest >= 0 && settings > dest);
        assertTrue(css.contains("font-size: 0.88rem"));
    }

    @Test
    @DisplayName("知识二级 Tab 为体系→资料→笔记→工具，检验不占一级")
    void fragmentHasFourKnowledgeTabs() throws IOException {
        String html = Files.readString(projectFile("src/main/resources/templates/fragments/codex-nav.html"));
        assertTrue(html.contains("th:fragment=\"chrome\""));
        assertTrue(html.contains(">体系</a>"));
        assertTrue(html.contains(">资料</a>"));
        assertTrue(html.contains(">笔记</a>"));
        assertTrue(html.contains(">工具</a>"));
        assertFalse(html.contains("返回 Dashboard"));
        assertFalse(html.contains("href=\"/dashboard\""));
        assertFalse(html.contains(">路径</a>"));
        assertFalse(html.contains(">接入</a>"), "接入不再是一级 Tab");
        assertFalse(html.contains(">检验</a>"));
        assertFalse(html.contains(">缺口</a>"));
        assertFalse(html.contains(">策展</a>"));
        assertFalse(html.contains(">定线</a>"));

        int prev = -1;
        for (String href : TAB_HREFS) {
            int at = html.indexOf("href=\"" + href + "\"");
            assertTrue(at >= 0, "missing " + href);
            assertTrue(at > prev, href + " out of order");
            prev = at;
        }
    }

    @Test
    @DisplayName("codexNav=curate 时工具 Tab 带 is-active")
    void toolsTabIsActiveForCuratePages() {
        String html = renderChrome("curate");
        Matcher items = Pattern.compile(
                "<a href=\"([^\"]+)\"[^>]*class=\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        int active = 0;
        boolean toolsActive = false;
        while (items.find()) {
            String href = items.group(1);
            String cls = items.group(2);
            if (!cls.contains("cx-seg-item")) {
                continue;
            }
            boolean isActive = cls.contains("is-active");
            if (isActive) {
                active++;
                toolsActive = "/codex/tools".equals(href);
            }
        }
        assertEquals(1, active, html);
        assertTrue(toolsActive, html);
        assertTrue(html.contains("aria-current=\"page\""));
    }

    @Test
    @DisplayName("知识页引入应用顶栏与二级导航")
    void knowledgePagesShareChrome() throws IOException {
        Path templates = projectFile("src/main/resources/templates");
        for (String page : KNOWLEDGE_PAGES) {
            String html = Files.readString(templates.resolve(page));
            assertTrue(html.contains("fragments/app-nav :: chrome"), page + " missing app-nav");
            assertTrue(html.contains("fragments/codex-nav :: chrome"), page + " missing nav fragment");
            assertTrue(html.contains("app-nav.css"), page + " missing app-nav.css");
            assertTrue(html.contains("codex-nav.css"), page + " missing codex-nav.css");
            assertFalse(html.contains("蒸馏与定线 →"), page);
            assertFalse(html.contains("知识落地检验 →"), page);
            assertFalse(html.contains("← 知识仓库"), page);
            assertFalse(html.contains("← 返回仓库"), page);
        }
        String controller = Files.readString(
                projectFile("src/main/java/org/zhzssp/memorandum/feature/codex/controller/CodexViewController.java"));
        for (String tab : List.of("path", "distill", "notes", "tools", "checkpoints", "curate", "gaps")) {
            assertTrue(controller.contains("\"codexNav\", \"" + tab + "\""), "controller missing " + tab);
        }
    }

    @Test
    @DisplayName("Dashboard 用应用顶栏，不再并列我的笔记/知识仓库按钮")
    void dashboardUsesAppDestinations() throws IOException {
        String html = Files.readString(projectFile("src/main/resources/templates/dashboard.html"));
        assertTrue(html.contains("fragments/app-nav :: chrome"));
        assertFalse(html.contains("我的笔记"));
        assertFalse(html.contains("知识仓库"));
        assertFalse(html.contains("fragments/codex-nav"));
        assertFalse(html.contains("/codex/distill"));
        assertFalse(html.contains("/codex/checkpoints"));
        assertFalse(html.contains("/codex/curate"));
        assertFalse(html.contains("/codex/gaps"));
        assertTrue(html.contains("/codex/notes"));
        assertTrue(html.contains("随手记"));
    }

    @Test
    @DisplayName("体系页是主角：不强迫改 properties，接入收进工具页")
    void pathPageDoesNotDemandProperties() throws IOException {
        String html = Files.readString(projectFile("src/main/resources/templates/codex.html"));
        assertTrue(html.contains("当前路径"));
        assertTrue(html.contains("学 · 投影任务"));
        assertTrue(html.contains("/codex/tools"));
        assertTrue(html.contains("/api/codex/path/point"));
        assertFalse(html.contains("id=\"cx-register\""));
        assertFalse(html.contains("id=\"cx-write\""));
        assertFalse(html.contains("codex.enabled=true"));
        String tools = Files.readString(projectFile("src/main/resources/templates/codex-tools.html"));
        assertTrue(tools.contains("允许写入工作副本"));
        assertTrue(tools.contains("/codex/checkpoints"));
        assertTrue(tools.contains("/codex/gaps"));
        assertTrue(tools.contains("/codex/curate"));
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
