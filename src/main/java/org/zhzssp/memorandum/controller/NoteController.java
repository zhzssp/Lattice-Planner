package org.zhzssp.memorandum.controller;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.NoteRepository;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/note")
public class NoteController {

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/list")
    public List<Note> listNotes(Principal principal) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        return noteRepository.findByUser(user);
    }

    @PostMapping("/add")
    public ResponseEntity<?> addNote(@RequestBody NewNoteDto dto, Principal principal) {
        if (!StringUtils.hasText(dto.getTitle()) && !StringUtils.hasText(dto.getContent())) {
            return ResponseEntity.badRequest().body("empty");
        }
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        Note note = new Note();
        note.setTitle(dto.getTitle());
        note.setContent(dto.getContent());

        NoteType type = NoteType.SCRATCH;
        if (StringUtils.hasText(dto.getType())) {
            try {
                type = NoteType.valueOf(dto.getType());
            } catch (IllegalArgumentException ignored) {
            }
        }
        note.setType(type);

        note.setUser(user);
        noteRepository.save(note);
        return ResponseEntity.ok(note.getId());
    }

    @Data
    public static class NewNoteDto {
        private String title;
        private String content;
        private String type;
    }
}
