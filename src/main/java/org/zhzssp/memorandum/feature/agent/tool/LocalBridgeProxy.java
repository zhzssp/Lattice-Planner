package org.zhzssp.memorandum.feature.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 本地工具反向代理：服务端工具调用时 -> 通过 WebSocket 让 Electron 渲染层调用 IPC -> 返回结果。
 *
 * 后端 JVM 不直接做任何本地 IO，所有本地操作必须经过 Electron preload 的白名单网关。
 */
@Component
public class LocalBridgeProxy {

    private final AgentChatWebSocketHandler ws;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    public LocalBridgeProxy(@Lazy AgentChatWebSocketHandler ws) {
        this.ws = ws;
    }

    public JsonNode call(String tool, Map<String, Object> args) throws Exception {
        String sid = AgentContext.sessionId();
        if (sid == null) {
            throw new IllegalStateException("当前线程无 Agent sessionId（不在 ReAct 推理线程中？）");
        }
        String reqId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(reqId, f);
        try {
            ws.sendLocalCall(sid, reqId, tool, args);
            return f.orTimeout(30, TimeUnit.SECONDS).get();
        } finally {
            pending.remove(reqId);
        }
    }

    public void onLocalResult(String reqId, JsonNode result) {
        CompletableFuture<JsonNode> f = pending.remove(reqId);
        if (f != null) f.complete(result);
    }
}
