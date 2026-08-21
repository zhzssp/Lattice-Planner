package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbLink;

import java.util.List;

@Repository
public interface KbLinkRepository extends JpaRepository<KbLink, Long> {

    List<KbLink> findByRepoId(Long repoId);

    List<KbLink> findBySrcDocumentId(Long srcDocumentId);

    /** 反链查询：谁指向了我。 */
    List<KbLink> findByTargetDocumentId(Long targetDocumentId);

    List<KbLink> findByRepoIdAndBrokenTrue(Long repoId);

    long countByRepoIdAndBrokenTrue(Long repoId);

    /** 孤岛检测：本仓库中没有任何入链的文档 id。 */
    @Query("select d.id from KbDocument d where d.repoId = :repoId " +
            "and d.id not in (select l.targetDocumentId from KbLink l " +
            "where l.repoId = :repoId and l.targetDocumentId is not null)")
    List<Long> findOrphanDocumentIds(@Param("repoId") Long repoId);

    @Modifying
    @Query("delete from KbLink l where l.srcDocumentId = :documentId")
    void deleteBySrcDocumentId(@Param("documentId") Long documentId);

    @Modifying
    @Query("delete from KbLink l where l.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
