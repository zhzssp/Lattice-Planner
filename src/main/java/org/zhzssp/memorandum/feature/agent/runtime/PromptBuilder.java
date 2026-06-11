package org.zhzssp.memorandum.feature.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统 Prompt + 历史拼接器。按 mode 选择工具子集：
 *  chat    -> 全部
 *  plan    -> 任务/目标/规划/读+写（+ kb 读，便于规划时引用历史笔记）
 *  reflect -> 任务/目标/insight/笔记/读（+ kb 读，复盘时检索过往）
 *  learn   -> kb/note/read（"我以前学过 X 吗"等纯检索问答）
 */
@Component
public class PromptBuilder {

    private final ToolRegistry registry;
    private final ObjectMapper om;

    public PromptBuilder(ToolRegistry registry, ObjectMapper om) {
        this.registry = registry;
        this.om = om;
    }

    public List<Map<String, String>> build(String mode,
                                           List<ConversationMemory.Msg> history,
                                           String longTermMemo) throws JsonProcessingException {
        Set<String> tagFilter = switch (mode == null ? "chat" : mode) {
            case "plan" -> Set.of("task", "goal", "planner", "kb", "read", "write");
            case "reflect" -> Set.of("task", "goal", "insight", "note", "kb", "read");
            case "learn" -> Set.of("kb", "note", "read");
            default -> null;
        };
        String toolsJson = om.writerWithDefaultPrettyPrinter()
                .writeValueAsString(registry.exportSchemas(tagFilter));

        String memoSection = (longTermMemo == null || longTermMemo.isBlank())
                ? "(暂无)" : longTermMemo;

        String sys = """
                你是 Lattice-Planner 内置的规划助手 Lattice-Agent。今天是 %s。
                你与用户协作管理目标 / 任务 / 笔记 / 复盘，并可读取用户本地文档。

                【可用工具】（必须使用工具完成读写操作，不要编造数据）
                %s

                【输出协议】（严格遵守）
                - 如需调用工具：仅输出一个 JSON 对象，形如
                  {"tool":"task.search","arguments":{"keyword":"周报"}}
                  不要解释，不要 Markdown 围栏，不要附带其他文字。
                - 如已能给出最终答复：直接输出自然语言中文，不要再输出 JSON。
                - 一次只能调用一个工具；看到工具结果后再决定下一步。
                - 标 "需用户确认" 的工具会触发弹窗，请只在用户明确意图后再调用。
                - 不可编造任务/目标/笔记的 id，所有 id 必须来自工具返回的真实数据。

                【知识检索原则】（涉及"我"自身经验/笔记时严格执行）
                - 涉及"我"、"我的笔记/项目/经验"、"我之前学过 X"、"上次我们说过 Y" 等表述时，
                  必须先调用 kb.semantic_search 检索个人知识库（笔记 + 已摄取本地文档）。
                - kb.semantic_search 命中条目最高 score < 0.4 时视为弱相关：最终回答需明示
                  "未找到强相关笔记，以下基于通用知识"，再给一般性回答。
                - 命中并真正引用某篇笔记时，使用 [[标题]] 写法（不带路径），用户可点击跳转。
                - 不要把 kb 工具与 note.create 混用：semantic_search/lookup_by_title 是"读"，
                  仅在用户显式要求"记一笔"时才 note.create。

                【用户长期记忆（来自历史 Agent 会话归档）】
                %s
                """.formatted(LocalDate.now(), toolsJson, memoSection);

        List<Map<String, String>> msgs = new ArrayList<>();
        Map<String, String> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", sys);
        msgs.add(sysMsg);

        for (ConversationMemory.Msg m : history) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", m.role());
            entry.put("content", m.content());
            msgs.add(entry);
        }
        return msgs;
    }
}
