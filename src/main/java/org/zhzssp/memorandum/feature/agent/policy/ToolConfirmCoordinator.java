package org.zhzssp.memorandum.feature.agent.policy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 高危工具执行前的确认协调器：发出 confirmReq -> 等用户在 UI 点允许/拒绝 -> 回 future。
 * 60 秒未应答自动按"拒绝"处理，避免 Agent 推理线程永久阻塞。
 */
@Component
public class ToolConfirmCoordinator {

    private final AgentChatWebSocketHandler ws;
    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    public ToolConfirmCoordinator(@Lazy AgentChatWebSocketHandler ws) {
        this.ws = ws;
    }

    public CompletableFuture<Boolean> askUser(String sid, String reqId, String tool, JsonNode args) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        pending.put(reqId, f);
        String summary = "Agent 想调用工具 \"" + tool + "\"，参数：" + (args == null ? "{}" : args.toString());
        ws.sendConfirmReq(sid, reqId, summary);
        return f.orTimeout(60, TimeUnit.SECONDS).exceptionally(ex -> {
            pending.remove(reqId);
            return false;
        });
    }

    public void onReply(String reqId, boolean approved) {
        CompletableFuture<Boolean> f = pending.remove(reqId);
        if (f != null) f.complete(approved);
    }
}
