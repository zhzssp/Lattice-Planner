package org.zhzssp.memorandum.feature.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.LocalBridgeProxy;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地文档工具：所有 IO 经 LocalBridgeProxy 反向调用 Electron preload 完成，
 * 后端 JVM 不直接做磁盘 IO。仅在 Electron 客户端内可用；网页直接打开会报 BRIDGE_NOT_AVAILABLE。
 */
@Component
public class LocalDocTools {

    private final LocalBridgeProxy bridge;

    public LocalDocTools(LocalBridgeProxy bridge) {
        this.bridge = bridge;
    }

    @AgentTool(name = "local.list_dir", tags = {"local", "read"}, requiresConfirm = true,
            description = "列出本地目录下的所有文件与子目录（路径必须在 Electron 白名单内）。")
    public List<Map<String, Object>> listDir(
            @ToolParam(value = "path", desc = "绝对路径，例如 D:/learning/spring-ai", required = true) String path
    ) throws Exception {
        JsonNode r = bridge.call("list_dir", Map.of("path", path));
        if (r.has("error")) {
            throw new IllegalStateException("local.list_dir 失败：" + r.path("error").asText());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        JsonNode arr = r.isArray() ? r : r.path("entries");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", n.path("name").asText());
                entry.put("isDir", n.path("isDir").asBoolean(false));
                list.add(entry);
            }
        }
        return list;
    }

    @AgentTool(name = "local.read_file", tags = {"local", "read"}, requiresConfirm = true,
            description = "读取本地文本文件（utf-8），扩展名必须在 Electron 白名单内（md/txt/json/yml 等）。")
    public String readFile(
            @ToolParam(value = "path", desc = "绝对路径", required = true) String path
    ) throws Exception {
        JsonNode r = bridge.call("read_file", Map.of("path", path));
        if (r.has("error")) {
            throw new IllegalStateException("local.read_file 失败：" + r.path("error").asText());
        }
        return r.path("content").asText("");
    }

    @AgentTool(name = "local.read_pdf", tags = {"local", "read"}, requiresConfirm = true,
            description = "读取本地 PDF 的纯文本内容。需要 Electron 客户端安装 pdf-parse。")
    public String readPdf(
            @ToolParam(value = "path", desc = "绝对路径", required = true) String path
    ) throws Exception {
        JsonNode r = bridge.call("read_pdf", Map.of("path", path));
        if (r.has("error")) {
            throw new IllegalStateException("local.read_pdf 失败：" + r.path("error").asText());
        }
        return r.path("content").asText("");
    }
}
