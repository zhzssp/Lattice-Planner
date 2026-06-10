package org.zhzssp.memorandum.core.service;

import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.NoteRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 笔记服务：把原 NoteController 内的 CRUD 抽离出来，便于 Agent 复用。
 */
@Service
public class NoteService {

    private final NoteRepository noteRepository;

    public NoteService(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    public List<Note> listByUser(User user) {
        return noteRepository.findByUser(user);
    }

    public List<Note> listByUserAndType(User user, NoteType type) {
        return noteRepository.findByUser(user).stream()
                .filter(n -> n.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * 列出非 AGENT_MEMO 的可见笔记（普通用户视角）。
     */
    public List<Note> listVisibleByUser(User user) {
        return noteRepository.findByUser(user).stream()
                .filter(n -> n.getType() != NoteType.AGENT_MEMO)
                .collect(Collectors.toList());
    }

    public Note create(User user, String title, String content, NoteType type) {
        Note n = new Note();
        n.setUser(user);
        n.setTitle(title);
        n.setContent(content);
        n.setType(type == null ? NoteType.SCRATCH : type);
        return noteRepository.save(n);
    }
}
