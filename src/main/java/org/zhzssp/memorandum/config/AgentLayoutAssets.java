package org.zhzssp.memorandum.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Agent 左右分栏的服务端注入物。
 * localhost 上旧 Thymeleaf 缓存 / 旧 classpath 模板仍可能吐出 {@code position:fixed}
 * 浮层 HTML；过滤器把这份 CSS/JS 写进响应，不依赖浏览器是否拿到了新的 fragment。
 */
public final class AgentLayoutAssets {

    public static final String VERSION = "split-v11";
    public static final String HEADER = "X-LP-Agent-Layout";

    static final String CSS = """
            #lp-agent-layout-chip {
                position: fixed !important;
                left: 8px !important;
                bottom: 8px !important;
                z-index: 2147483646 !important;
                background: #111827 !important;
                color: #86efac !important;
                font: 11px/1.2 ui-monospace, Consolas, monospace !important;
                padding: 4px 7px !important;
                border-radius: 4px !important;
                pointer-events: none !important;
                opacity: .85 !important;
            }
            .lp-page-shell {
                display: flex !important;
                flex-direction: row !important;
                align-items: stretch !important;
                width: 100% !important;
                min-height: 100vh !important;
                box-sizing: border-box !important;
            }
            .lp-page-main {
                flex: 1 1 auto !important;
                min-width: 0 !important;
                overflow: auto !important;
            }
            .lp-agent-mount, #lp-agent-host {
                flex: 0 0 0 !important;
                width: 0 !important;
                min-width: 0 !important;
                overflow: hidden !important;
                align-self: stretch !important;
            }
            #lp-agent-panel, #lp-agent-panel.open {
                position: relative !important;
                top: auto !important;
                right: auto !important;
                left: auto !important;
                bottom: auto !important;
                inset: auto !important;
                transform: none !important;
                width: 100% !important;
                height: 100% !important;
                max-width: none !important;
                z-index: 2 !important;
            }
            html.lp-agent-open,
            html.lp-agent-open body,
            html:has(#lp-agent-panel.open),
            html:has(#lp-agent-panel.open) body {
                height: 100vh !important;
                overflow: hidden !important;
            }
            html.lp-agent-open .lp-page-shell,
            html:has(#lp-agent-panel.open) .lp-page-shell,
            .lp-page-shell:has(#lp-agent-panel.open) {
                height: 100vh !important;
                overflow: hidden !important;
            }
            html.lp-agent-open .lp-agent-mount,
            html.lp-agent-open #lp-agent-host,
            html:has(#lp-agent-panel.open) .lp-agent-mount,
            html:has(#lp-agent-panel.open) #lp-agent-host,
            .lp-page-shell:has(#lp-agent-panel.open) .lp-agent-mount {
                flex: 0 0 var(--lp-agent-width, 440px) !important;
                width: var(--lp-agent-width, 440px) !important;
                max-width: var(--lp-agent-width, 440px) !important;
            }
            html.lp-agent-open .lp-page-main,
            html:has(#lp-agent-panel.open) .lp-page-main {
                max-width: calc(100% - var(--lp-agent-width, 440px)) !important;
            }
            html.lp-agent-open .container,
            html.lp-agent-open .note-page,
            html.lp-agent-open .memo-form-container,
            html.lp-agent-open .mcp-container,
            html:has(#lp-agent-panel.open) .container,
            html:has(#lp-agent-panel.open) .note-page,
            html:has(#lp-agent-panel.open) .memo-form-container,
            html:has(#lp-agent-panel.open) .mcp-container {
                max-width: none !important;
                width: auto !important;
                box-sizing: border-box !important;
            }
            html.lp-agent-open #lp-agent-fab,
            html:has(#lp-agent-panel.open) #lp-agent-fab {
                display: none !important;
            }
            """;

    private AgentLayoutAssets() {
    }

    public static boolean isAgentPage(String html) {
        return html != null && (html.contains("lp-agent-panel") || html.contains("lp-agent-fab"));
    }

    public static String inject(String html) {
        if (!isAgentPage(html)) {
            return html;
        }
        String out = html;
        if (!out.contains("data-lp-layout=")) {
            out = out.replaceFirst("(?i)<html(\\s|>)", "<html data-lp-layout=\"" + VERSION + "\"$1");
        }
        if (!containsId(out, "lp-agent-split-boot")) {
            out = insertBefore(out, "</head>",
                    "<style id=\"lp-agent-split-boot\">" + CSS + "</style>\n");
        }
        if (!containsId(out, "lp-agent-layout-boot")) {
            String js = readBootJs();
            out = insertBefore(out, "</body>",
                    "<script id=\"lp-agent-layout-boot\">" + sanitizeScript(js) + "</script>\n");
        }
        return out;
    }

    static String readBootJs() {
        try (InputStream in = AgentLayoutAssets.class.getResourceAsStream("/static/agent/layout-boot.js")) {
            if (in == null) {
                return "console.error('[LP-Agent][" + VERSION + "] layout-boot.js missing from classpath');";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "console.error('[LP-Agent][" + VERSION + "] failed to read layout-boot.js');";
        }
    }

    private static boolean containsId(String html, String id) {
        return html.contains("id=\"" + id + "\"") || html.contains("id='" + id + "'");
    }

    private static String sanitizeScript(String js) {
        return js.replace("</script>", "<\\/script>").replace("</SCRIPT>", "<\\/SCRIPT>");
    }

    private static String insertBefore(String html, String marker, String insert) {
        int idx = indexOfIgnoreCase(html, marker);
        if (idx >= 0) {
            return html.substring(0, idx) + insert + html.substring(idx);
        }
        return html + insert;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase());
    }
}
