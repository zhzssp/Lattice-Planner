package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbChunk;

import java.util.List;

@Repository
public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {

    List<KbChunk> findByUserIdAndRepoId(Long userId, Long repoId);

    List<KbChunk> findByDocumentIdOrderByChunkIdxAsc(Long documentId);

    long countByRepoId(Long repoId);

    @Modifying
    @Query("delete from KbChunk c where c.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);

    @Modifying
    @Query("delete from KbChunk c where c.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);

    /**
     * 关键字通路：FULLTEXT ngram 检索。
     *
     * <p>用原生 SQL 而非 JPQL：MySQL 的 {@code MATCH ... AGAINST} 无 JPQL 等价物。
     * 与 {@code NoteRepository.fulltextSearch} 同构，未跑 V8 SQL 时会抛
     * SQLException，由调用方 catch 后降级为仅向量通路——沿用既有的双通路独立降级设计。</p>
     */
    @Query(value = """
            SELECT * FROM kb_chunk
            WHERE user_id = :userId AND repo_id = :repoId
              AND MATCH(content) AGAINST (:kw IN NATURAL LANGUAGE MODE)
            LIMIT :lim
            """, nativeQuery = true)
    List<KbChunk> fulltextSearch(@Param("userId") Long userId,
                                 @Param("repoId") Long repoId,
                                 @Param("kw") String keyword,
                                 @Param("lim") int limit);

    /** 跨仓库关键字检索（用户全部启用仓库）。 */
    @Query(value = """
            SELECT * FROM kb_chunk
            WHERE user_id = :userId
              AND MATCH(content) AGAINST (:kw IN NATURAL LANGUAGE MODE)
            LIMIT :lim
            """, nativeQuery = true)
    List<KbChunk> fulltextSearchAllRepos(@Param("userId") Long userId,
                                         @Param("kw") String keyword,
                                         @Param("lim") int limit);
}
