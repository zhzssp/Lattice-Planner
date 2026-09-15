package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Agent 面板 IDE 分栏：模板/CSS 契约 + 无头浏览器几何回归。
 */
class AgentPanelLayoutTest {

    private static final List<String> HOST_PAGES = List.of(
            "dashboard.html",
            "noteList.html",
            "noteView.html",
            "noteEdit.html",
            "addMemo.html",
            "mcp-settings.html",
            "preferenceSettings.html",
            "selectFeatures.html"
    );

    @Test
    @DisplayName("fragment 是 workbench：FAB 与 sash 在 mount 外，无浮层补丁")
    void fragmentIsWorkbenchNotOverlay() throws IOException {
        String html = Files.readString(projectFile("src/main/resources/templates/fragments/agent-panel.html"));
        assertTrue(html.contains("lp-agent-workbench"));
        assertTrue(html.contains("id=\"lp-agent-sash\""));
        assertTrue(html.contains("id=\"lp-agent-fab\""));
        assertTrue(html.contains("class=\"lp-agent-mount\""));
        assertTrue(html.indexOf("id=\"lp-agent-fab\"") < html.indexOf("class=\"lp-agent-mount\""));
        assertTrue(html.indexOf("id=\"lp-agent-sash\"") < html.indexOf("class=\"lp-agent-mount\""));
        assertFalse(html.contains("layout-boot"));
        assertFalse(html.contains("lp-agent-split-inline"));
        assertFalse(html.contains("lp-agent-layout-chip"));
        assertFalse(html.contains("lp-agent-resizer"));
        assertFalse(html.contains("position:fixed"));
        assertFalse(html.contains("position: fixed"));
    }

    @Test
    @DisplayName("chat-panel.css：面板 relative，靠 flex 分栏，不用 max-width calc")
    void cssIsFlexNotOverlay() throws IOException {
        String css = Files.readString(projectFile("src/main/resources/static/agent/chat-panel.css"));
        assertTrue(css.contains("#lp-agent-panel {\n    position: relative;"));
        assertFalse(panelRuleIsFixed(css));
        assertFalse(css.contains("max-width: calc(100% - var(--lp-agent-width"));
        assertTrue(css.contains("flex: 0 0 var(--lp-agent-width)"));
        assertTrue(css.contains("#lp-agent-sash"));
        assertFalse(css.contains("lp-agent-layout-chip"));
        String dashboardCss = Files.readString(projectFile("src/main/resources/static/css/dashboard.css"));
        assertFalse(dashboardCss.contains("max-width: calc(100% - var(--lp-agent-width"));
    }

    @Test
    @DisplayName("宿主页都有 lp-page-shell，且已去掉 HTML 改写补丁")
    void hostPagesUseShellAndWorkaroundsAreGone() throws IOException {
        Path templates = projectFile("src/main/resources/templates");
        for (String page : HOST_PAGES) {
            String html = Files.readString(templates.resolve(page));
            assertTrue(html.contains("lp-page-shell"), page + " missing lp-page-shell");
            assertTrue(html.contains("lp-page-main"), page + " missing lp-page-main");
            assertTrue(html.contains("fragments/agent-panel"), page + " missing agent panel");
        }
        assertFalse(Files.exists(projectFile("src/main/java/org/zhzssp/memorandum/config/AgentLayoutFilter.java")));
        assertFalse(Files.exists(projectFile("src/main/java/org/zhzssp/memorandum/config/AgentLayoutAssets.java")));
        assertFalse(Files.exists(projectFile("src/main/resources/static/agent/layout-boot.js")));
    }

    @Test
    @EnabledOnOs({OS.WINDOWS, OS.LINUX, OS.MAC})
    @DisplayName("无头浏览器：打开并排 overlap=0，拖 sash 主区变窄，关闭回全宽")
    void headlessSplitDragAndClose() throws Exception {
        Path chrome = findChrome();
        assumeTrue(chrome != null, "Chrome/Edge not installed; skip geometry probe");

        Path tmp = Files.createTempDirectory("lp-agent-layout");
        Path htmlFile = tmp.resolve("workbench.html");
        Files.writeString(htmlFile, buildFixtureHtml(), StandardCharsets.UTF_8);
        Path profile = tmp.resolve("profile");
        Files.createDirectories(profile);

        ProcessBuilder pb = new ProcessBuilder(
                chrome.toString(),
                "--headless=new",
                "--disable-gpu",
                "--allow-file-access-from-files",
                "--window-size=1440,900",
                "--virtual-time-budget=8000",
                "--user-data-dir=" + profile.toAbsolutePath(),
                "--dump-dom",
                htmlFile.toUri().toString()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String dump = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = proc.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            fail("headless chrome timed out");
        }
        assertEquals(0, proc.exitValue(), () -> "chrome exit=" + proc.exitValue() + "\n" + dump);

        JsonNode geo = parseProbe(dump);
        JsonNode closed = geo.get("closed");
        JsonNode open = geo.get("open");
        JsonNode dragged = geo.get("dragged");
        JsonNode reclosed = geo.get("reclosed");
        assertNotNull(open, dump);

        assertTrue(closed.get("mountW").asInt() < 8, closed.toString());
        assertNotEquals("none", closed.get("fabDisplay").asText());

        assertEquals("relative", open.get("panelPos").asText(), open.toString());
        assertTrue(open.get("overlap").asInt() <= 2, open.toString());
        assertTrue(open.get("sideBySide").asBoolean(), open.toString());
        assertTrue(open.get("panelW").asInt() >= 300, open.toString());
        assertTrue(open.get("mainW").asInt() < closed.get("mainW").asInt() - 100, open.toString());

        assertTrue(dragged.get("panelW").asInt() > open.get("panelW").asInt() + 40, dragged.toString());
        assertTrue(dragged.get("mainW").asInt() < open.get("mainW").asInt() - 40, dragged.toString());
        assertTrue(dragged.get("overlap").asInt() <= 2, dragged.toString());

        assertTrue(reclosed.get("mountW").asInt() < 8, reclosed.toString());
        assertTrue(reclosed.get("mainW").asInt() > dragged.get("mainW").asInt() + 100, reclosed.toString());
        assertNotEquals("none", reclosed.get("fabDisplay").asText());
    }

    private static boolean panelRuleIsFixed(String css) {
        Matcher m = Pattern.compile("#lp-agent-panel\\s*\\{([^}]+)}").matcher(css);
        if (!m.find()) {
            return true;
        }
        return m.group(1).matches("(?s).*position:\\s*fixed.*");
    }

    private static JsonNode parseProbe(String dump) throws IOException {
        Matcher m = Pattern.compile("id=\"probe-out\"[^>]*>(.*?)</pre>", Pattern.DOTALL).matcher(dump);
        assertTrue(m.find(), () -> "probe-out missing in dump:\n" + dump.substring(0, Math.min(dump.length(), 2000)));
        String raw = m.group(1)
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .trim();
        return new ObjectMapper().readTree(raw);
    }

    private static String buildFixtureHtml() throws IOException {
        String css = Files.readString(projectFile("src/main/resources/static/agent/chat-panel.css"));
        String js = Files.readString(projectFile("src/main/resources/static/agent/chat-panel.js"))
                .replace("</script>", "<\\/script>");
        return """
                <!DOCTYPE html>
                <html lang="zh">
                <head>
                <meta charset="UTF-8">
                <style>
                html, body { height: 100%; margin: 0; }
                .container { max-width: 980px; margin: 80px auto; background: #fff; padding: 24px; }
                .lp-agent-mount { transition: none !important; }
                """ + css + """
                </style>
                </head>
                <body>
                <div class="lp-page-shell">
                  <div class="lp-page-main">
                    <div class="container"><h2>Dashboard</h2><p>layout probe</p></div>
                  </div>
                  <div class="lp-agent-workbench">
                    <button id="lp-agent-fab" type="button">AI</button>
                    <div id="lp-agent-sash"></div>
                    <div class="lp-agent-mount">
                      <aside id="lp-agent-panel" aria-hidden="true">
                        <header>
                          <span class="lp-agent-title">Lattice-Agent</span>
                          <select id="lp-agent-mode"><option value="chat">Chat</option></select>
                          <button id="lp-agent-close" type="button">×</button>
                        </header>
                        <ol id="lp-agent-stream"></ol>
                        <footer>
                          <textarea id="lp-agent-input"></textarea>
                          <div class="lp-agent-actions">
                            <span id="lp-agent-status">未连接</span>
                            <button id="lp-agent-send" type="button">发送</button>
                          </div>
                        </footer>
                      </aside>
                    </div>
                  </div>
                </div>
                <pre id="probe-out"></pre>
                <script>
                """ + js + """
                </script>
                <script>
                (function () {
                  function geo() {
                    var main = document.querySelector('.lp-page-main');
                    var panel = document.getElementById('lp-agent-panel');
                    var mount = document.querySelector('.lp-agent-mount');
                    var fab = document.getElementById('lp-agent-fab');
                    var mr = main.getBoundingClientRect();
                    var pr = panel.getBoundingClientRect();
                    var overlap = Math.max(0, Math.min(mr.right, pr.right) - Math.max(mr.left, pr.left));
                    return {
                      mainW: Math.round(mr.width),
                      panelW: Math.round(pr.width),
                      mountW: Math.round(mount.getBoundingClientRect().width),
                      panelPos: getComputedStyle(panel).position,
                      overlap: Math.round(overlap),
                      sideBySide: pr.width > 80 && pr.left >= mr.right - 2,
                      fabDisplay: getComputedStyle(fab).display
                    };
                  }
                  function run() {
                    try { localStorage.removeItem('lp-agent-width-v2'); } catch (e) {}
                    document.documentElement.style.setProperty('--lp-agent-width', '440px');
                    var out = {};
                    out.closed = geo();
                    document.getElementById('lp-agent-fab').click();
                    setTimeout(function () {
                      out.open = geo();
                      var sash = document.getElementById('lp-agent-sash');
                      var startW = out.open.panelW || 440;
                      var right = window.innerWidth;
                      sash.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: right - startW }));
                      window.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, cancelable: true, clientX: right - startW - 100 }));
                      window.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                      setTimeout(function () {
                        out.dragged = geo();
                        document.getElementById('lp-agent-close').click();
                        setTimeout(function () {
                          out.reclosed = geo();
                          document.getElementById('probe-out').textContent = JSON.stringify(out);
                        }, 280);
                      }, 80);
                    }, 280);
                  }
                  if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', function () { setTimeout(run, 30); });
                  } else {
                    setTimeout(run, 30);
                  }
                })();
                </script>
                </body>
                </html>
                """;
    }

    private static Path findChrome() {
        String[] candidates = new String[] {
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static Path projectFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8; i++) {
            if (Files.exists(dir.resolve("src/main/resources/static/agent/chat-panel.css"))) {
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
