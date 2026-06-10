package org.zhzssp.memorandum.controller;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zhzssp.memorandum.core.service.NoteService;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/note")
public class NoteController {

    @Autowired
    private NoteService noteService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/list")
    public List<Note> listNotes(Principal principal) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        // UI 列表不展示 Agent 长期记忆条目
        return noteService.listVisibleByUser(user);
    }

    @PostMapping("/add")
    public ResponseEntity<?> addNote(@RequestBody NewNoteDto dto, Principal principal) {
        if (!StringUtils.hasText(dto.getTitle()) && !StringUtils.hasText(dto.getContent())) {
            return ResponseEntity.badRequest().body("empty");
        }
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();

        NoteType type = NoteType.SCRATCH;
        if (StringUtils.hasText(dto.getType())) {
            try {
                NoteType parsed = NoteType.valueOf(dto.getType());
                // 拒绝普通页面创建 AGENT_MEMO 类型笔记
                if (parsed != NoteType.AGENT_MEMO) {
                    type = parsed;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        Note saved = noteService.create(user, dto.getTitle(), dto.getContent(), type);
        return ResponseEntity.ok(saved.getId());
    }

    @Data
    public static class NewNoteDto {
        private String title;
        private String content;
        private String type;
    }
}
