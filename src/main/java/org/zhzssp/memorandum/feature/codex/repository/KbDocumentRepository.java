package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    List<KbDocument> findByRepoId(Long repoId);

    Optional<KbDocument> findByRepoIdAndPath(Long repoId, String path);

    Optional<KbDocument> findByIdAndUserId(Long id, Long userId);

    long countByRepoId(Long repoId);

    long countByRepoIdAndTruncatedTrue(Long repoId);

    /** kind 分布统计（仪表盘用）。列序：[kind, cnt]。 */
    @Query("select d.kind as kind, count(d) as cnt from KbDocument d " +
            "where d.repoId = :repoId group by d.kind order by count(d) desc")
    List<KindCount> countByKind(@Param("repoId") Long repoId);

    interface KindCount {
        org.zhzssp.memorandum.feature.codex.entity.KbDocument.DocKind getKind();
        long getCnt();
    }

    /**
     * 标题模糊匹配（用于 {@code doc.read} 按标题定位，以及 [[标题]] 解析）。
     * 刻意不用 FULLTEXT：标题定位要的是确定性前缀/包含匹配，而非相关性排序。
     */
    @Query("select d from KbDocument d where d.userId = :userId " +
            "and lower(d.title) like lower(concat('%', :kw, '%')) order by length(d.title) asc")
    List<KbDocument> searchByTitle(@Param("userId") Long userId, @Param("kw") String kw);

    @Modifying
    @Query("delete from KbDocument d where d.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
