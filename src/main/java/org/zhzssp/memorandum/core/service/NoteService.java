package org.zhzssp.memorandum.core.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.pkm.event.NotePersistedEvent;
import org.zhzssp.memorandum.repository.NoteRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 笔记服务：把原 NoteController 内的 CRUD 抽离出来，便于 Agent 复用。
 *
 * V4 PKM 升级：
 *   - 新增 update / findByIdForUser；
 *   - create / update 末尾发布 NotePersistedEvent，触发异步索引重建（NOTE→NOTE 链接 / 向量）。
 */
@Service
public class NoteService {

    private final NoteRepository noteRepository;
    private final ApplicationEventPublisher publisher;

    public NoteService(NoteRepository noteRepository, ApplicationEventPublisher publisher) {
        this.noteRepository = noteRepository;
        this.publisher = publisher;
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

    /**
     * 仅当笔记属于该用户时返回，避免越权读。
     */
    public Optional<Note> findByIdForUser(Long id, User user) {
        if (id == null || user == null) return Optional.empty();
        return noteRepository.findById(id)
                .filter(n -> n.getUser() != null
                        && n.getUser().getId() != null
                        && n.getUser().getId().equals(user.getId()));
    }

    public Note create(User user, String title, String content, NoteType type) {
        Note n = new Note();
        n.setUser(user);
        n.setTitle(title);
        n.setContent(content);
        n.setType(type == null ? NoteType.SCRATCH : type);
        Note saved = noteRepository.save(n);
        publisher.publishEvent(new NotePersistedEvent(this, saved));
        return saved;
    }

    /**
     * 增量更新，传 null 表示不修改对应字段。tags 传空字符串会清空标签。
     */
    public Note update(Note n, String title, String content, NoteType type, String tags) {
        if (n == null) return null;
        if (title != null) n.setTitle(title);
        if (content != null) n.setContent(content);
        if (type != null) n.setType(type);
        if (tags != null) n.setTags(tags);
        Note saved = noteRepository.save(n);
        publisher.publishEvent(new NotePersistedEvent(this, saved));
        return saved;
    }
}
