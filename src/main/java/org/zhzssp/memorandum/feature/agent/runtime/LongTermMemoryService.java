package org.zhzssp.memorandum.feature.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.core.service.NoteService;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆：用 NoteType.AGENT_MEMO 类型的 Note 持久化每次会话精炼后的画像信息。
 *
 * - snippetFor：取最近 5 条 AGENT_MEMO，注入系统 Prompt
 * - archive  ：把一次会话历史交给 LLM 凝练 3~6 行，落库为新 AGENT_MEMO Note
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    private final NoteService noteService;
    private final LlmGateway llm;

    public LongTermMemoryService(NoteService noteService, LlmGateway llm) {
        this.noteService = noteService;
        this.llm = llm;
    }

    public String snippetFor(User user) {
        List<Note> memos = noteService.listByUserAndType(user, NoteType.AGENT_MEMO).stream()
                .sorted(Comparator.comparing(Note::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .toList();
        if (memos.isEmpty()) return "";
        return memos.stream()
                .map(n -> "- " + safe(n.getTitle()) + "：" + safe(n.getContent()))
                .collect(Collectors.joining("\n"));
    }

    public void archive(User user, List<ConversationMemory.Msg> history) {
        if (user == null || history == null || history.isEmpty()) return;
        String dialog = history.stream()
                .map(m -> m.role() + ": " + m.content())
                .collect(Collectors.joining("\n"));
        try {
            String memo = llm.generateText(
                    "请把下面这段用户与助手的对话凝练为 3~6 行，描述用户画像 / 偏好 / 待办线索，纯文本输出，不要寒暄：\n\n"
                            + dialog);
            noteService.create(user,
                    "Agent 长期记忆 " + LocalDate.now(),
                    memo,
                    NoteType.AGENT_MEMO);
        } catch (Exception ex) {
            log.warn("[Agent] long-term memo archive failed: {}", ex.getMessage());
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
