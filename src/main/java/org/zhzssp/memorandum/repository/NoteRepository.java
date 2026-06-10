package org.zhzssp.memorandum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.User;

import java.util.List;
import java.util.Optional;

@Repository
public interface NoteRepository extends JpaRepository<Note, Long> {

    List<Note> findByUser(User user);

    /**
     * 按精确标题取当前用户的第一篇匹配笔记，用于 [[Title]] 双向链接解析。
     */
    Optional<Note> findFirstByUserAndTitle(User user, String title);

    /**
     * MySQL FULLTEXT 关键字检索（ngram parser，中英文均支持）。
     * 索引由 V4__pkm_rag.sql 创建；若未执行该迁移，调用会抛 SQL 异常，
     * RagSearchService 会捕获并自动降级。
     */
    @Query(value = """
            SELECT * FROM note
            WHERE user_id = :userId
              AND MATCH(title, content) AGAINST (:kw IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(title, content) AGAINST (:kw IN NATURAL LANGUAGE MODE) DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<Note> fulltextSearch(@Param("userId") Long userId,
                              @Param("kw") String kw,
                              @Param("topK") int topK);
}

