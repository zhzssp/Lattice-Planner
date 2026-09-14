package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.config.AgentLayoutAssets;

import static org.junit.jupiter.api.Assertions.*;

class AgentLayoutAssetsTest {

    @Test
    void injectsSplitAssetsIntoOldOverlayHtml() {
        String old = """
                <html lang="en"><head><title>Dashboard</title></head>
                <body>
                <div class="container">Dashboard</div>
                <aside id="lp-agent-panel" class="open"></aside>
                </body></html>
                """;

        String out = AgentLayoutAssets.inject(old);

        assertTrue(out.contains("data-lp-layout=\"" + AgentLayoutAssets.VERSION + "\""));
        assertTrue(out.contains("id=\"lp-agent-split-boot\""));
        assertTrue(out.contains("position: relative !important"));
        assertTrue(out.contains("id=\"lp-agent-layout-boot\""));
        assertTrue(out.contains("LP_AGENT_LAYOUT"));
        assertTrue(out.indexOf("lp-agent-split-boot") < out.toLowerCase().indexOf("</head>"));
    }

    @Test
    void doesNotDuplicateWhenAlreadyBooted() {
        String html = """
                <html data-lp-layout="split-v11"><head>
                <style id="lp-agent-split-boot"></style>
                </head><body>
                <script id="lp-agent-layout-boot"></script>
                <aside id="lp-agent-panel"></aside>
                </body></html>
                """;
        assertEquals(html, AgentLayoutAssets.inject(html));
    }

    @Test
    void skipsPagesWithoutAgentPanel() {
        String html = "<html><head></head><body>login</body></html>";
        assertEquals(html, AgentLayoutAssets.inject(html));
    }
}
