package org.zhzssp.memorandum.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Data
@Entity
@Table(name = "note")
public class Note {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 标签：逗号分隔，如 "spring,ai,rag"。
     * V4 PKM 升级新增字段，旧记录为 NULL 不影响兼容。
     */
    @Column(name = "tags", length = 255)
    private String tags;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = true)
    private NoteType type;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * 晋升到 Git 知识仓库后的相对路径。原文留在 MySQL，不删。
     */
    @Column(name = "promoted_path", length = 255)
    private String promotedPath;

    @ManyToOne
    private User user;
}
