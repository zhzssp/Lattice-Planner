package org.zhzssp.memorandum.feature.agent.tool.impl;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.subagent.SubAgentExecutor;
import org.zhzssp.memorandum.feature.agent.subagent.SubAgentResult;
import org.zhzssp.memorandum.feature.agent.subagent.SubAgentRole;
import org.zhzssp.memorandum.feature.agent.subagent.SubAgentRunner;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「代理即工具」桥接层：把每个子代理包装成一个 {@code @AgentTool}（tag=subagent）。
 *
 * <p>主 {@code AgentOrchestrator} 因此<strong>零改动</strong>即可委派子代理——
 * 对它而言只是"调了一个慢一点的工具"。所有子代理工具均 {@code depth==0} 才可用：
 * {@link #guardTopLevel()} 断言层级，配合 {@code SubAgentRunner} 内部不暴露 subagent.* 工具，
 * 双重防止子代理递归套娃。</p>
 */
@Component
public class SubAgentTools {

    private final SubAgentRunner runner;
    private final SubAgentExecutor executor;

    public SubAgentTools(SubAgentRunner runner, SubAgentExecutor executor) {
        this.runner = runner;
        this.executor = executor;
    }

    @AgentTool(name = "subagent.plan", tags = {"subagent"},
            description = "委派【规划专家】子代理：读取本地文档/目标描述并拆解为目标与任务落库。" +
                    "适合一次性的复杂建库，可避免长文档与建库中间数据污染主对话上下文。" +
                    "instruction 应说清要规划什么、素材在哪（如本地文件路径）。")
    public Map<String, Object> plan(
            @ToolParam(value = "instruction", desc = "要规划的目标或需求，含素材来源（如本地文件路径）", required = true)
            String instruction) {
        guardTopLevel();
        return wrap(runner.run(SubAgentRole.PLANNER, instruction, AgentContext.sessionId()));
    }

    @AgentTool(name = "subagent.reflect", tags = {"subagent"},
            description = "委派【复盘专家】子代理：聚合一段周期的分数/任务/目标/笔记，生成结构化复盘报告。" +
                    "instruction 应说清复盘范围与诉求，如'最近7天的执行复盘'。")
    public Map<String, Object> reflect(
            @ToolParam(value = "instruction", desc = "复盘范围与诉求，如'最近7天'", required = true)
            String instruction) {
        guardTopLevel();
        return wrap(runner.run(SubAgentRole.REFLECTION, instruction, AgentContext.sessionId()));
    }

    @AgentTool(name = "subagent.research", tags = {"subagent"},
            description = "委派【检索专家】子代理：对一个问题做多跳知识库+本地文档检索并综合作答。" +
                    "适合'我之前关于 X 的笔记和文档都说了啥'这类需要多轮深检索的问题。")
    public Map<String, Object> research(
            @ToolParam(value = "question", desc = "要深入检索的问题", required = true)
            String question) {
        guardTopLevel();
        return wrap(runner.run(SubAgentRole.RESEARCH, question, AgentContext.sessionId()));
    }

    @AgentTool(name = "subagent.parallel_research", tags = {"subagent"},
            description = "并行委派多个【检索专家】子代理处理多个子问题并汇总结果。" +
                    "适合'体检我所有目标进度'、'分别查 A/B/C 三个主题'等可拆分为独立子问题的任务，" +
                    "比逐个串行调用 subagent.research 更快。questions 建议拆成 2~4 条彼此独立的子问题。")
    public Map<String, Object> parallelResearch(
            @ToolParam(value = "questions", desc = "彼此独立的子问题数组（建议 2~4 条），将并行检索", required = true)
            List<String> questions) {
        guardTopLevel();
        return executor.fanOut(SubAgentRole.RESEARCH, questions, AgentContext.sessionId());
    }

    /* ---------------- 内部 ---------------- */

    private void guardTopLevel() {
        if (AgentContext.depth() > 0) {
            throw new IllegalStateException(
                    "子代理不可再委派子代理（当前 depth=" + AgentContext.depth() + "）");
        }
    }

    private Map<String, Object> wrap(SubAgentResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", r.role());
        m.put("finalText", r.finalText());
        m.put("steps", r.steps());
        m.put("toolsUsed", r.toolsUsed());
        m.put("truncated", r.truncated());
        return m;
    }
}
