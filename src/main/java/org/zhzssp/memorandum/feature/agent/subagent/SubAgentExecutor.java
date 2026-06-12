package org.zhzssp.memorandum.feature.agent.subagent;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 子代理并行 fan-out 执行器：把一组可拆分的子任务<strong>并发</strong>派给同一角色的多个 worker，
 * 各自在独立上下文跑 ReAct，最后汇总每个 worker 的压缩结论。
 *
 * <p>关键工程点：</p>
 * <ul>
 *   <li><strong>ThreadLocal 跨线程传播</strong>：{@link AgentContext} 是 ThreadLocal，不会自动随
 *       {@code CompletableFuture} 进入 worker 线程。因此在父线程显式取出 {@code user}，
 *       在每个 worker 线程内重新 {@code set(user, parentSid)} 并在 finally 中 {@code clear()}。</li>
 *   <li><strong>并发上限</strong>：固定线程池 + 任务截断到 {@code size}，防止把上游 LLM 打到限流。</li>
 *   <li><strong>超时预算</strong>：{@code allOf().orTimeout(...)}；未完成的 worker 在汇总时降级标注。</li>
 *   <li><strong>并发安全</strong>：仅用于<strong>只读</strong>角色（RESEARCH / REFLECTION）；
 *       写操作角色（PLANNER）保持串行单 worker。</li>
 * </ul>
 */
@Component
public class SubAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentExecutor.class);

    private final SubAgentRunner runner;
    private final ExecutorService pool;
    private final int maxParallel;
    private final int timeoutSeconds;

    public SubAgentExecutor(SubAgentRunner runner,
                            @Value("${agent.subagent.parallel.size:4}") int size,
                            @Value("${agent.subagent.parallel.timeout-seconds:120}") int timeoutSeconds) {
        this.runner = runner;
        this.maxParallel = Math.max(1, size);
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
        this.pool = Executors.newFixedThreadPool(this.maxParallel, r -> {
            Thread t = new Thread(r, "subagent-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 并行执行同一角色的多个子任务。
     *
     * @param role      子代理角色（建议只读：RESEARCH / REFLECTION）
     * @param tasks     子任务/子问题列表（会被去空并截断到并发上限）
     * @param parentSid 主会话 id：worker 内部确认弹窗与可视化事件据此回到主用户 WS
     * @return 汇总结构：{ role, count, results:[{question, finalText, steps, toolsUsed, truncated}] }
     */
    public Map<String, Object> fanOut(SubAgentRole role, List<String> tasks, String parentSid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", role.name());

        List<String> capped = tasks == null ? List.of() : tasks.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .limit(maxParallel)
                .toList();
        if (capped.isEmpty()) {
            out.put("count", 0);
            out.put("results", List.of());
            out.put("note", "未提供有效子问题");
            return out;
        }

        // 父线程取出 user（worker 线程会重新注入）
        final User user = AgentContext.requireUser();

        List<CompletableFuture<SubAgentResult>> futs = new ArrayList<>(capped.size());
        for (String task : capped) {
            futs.add(CompletableFuture.supplyAsync(() -> {
                AgentContext.set(user, parentSid);
                try {
                    return runner.run(role, task, parentSid);
                } finally {
                    AgentContext.clear();
                }
            }, pool));
        }

        try {
            CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]))
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("[SubAgent:fanOut:{}] 并行等待异常/超时（{}s）：{}", role, timeoutSeconds, e.getMessage());
        }

        List<Map<String, Object>> results = new ArrayList<>(capped.size());
        for (int i = 0; i < capped.size(); i++) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("question", capped.get(i));
            try {
                SubAgentResult r = futs.get(i).getNow(null);
                if (r != null) {
                    one.put("finalText", r.finalText());
                    one.put("steps", r.steps());
                    one.put("toolsUsed", r.toolsUsed());
                    one.put("truncated", r.truncated());
                } else {
                    one.put("finalText", "（该子问题未在 " + timeoutSeconds + "s 超时内完成）");
                    one.put("truncated", true);
                }
            } catch (Exception ex) {
                one.put("finalText", "子问题执行异常：" + ex.getMessage());
                one.put("truncated", true);
            }
            results.add(one);
        }

        out.put("count", results.size());
        out.put("results", results);
        return out;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
