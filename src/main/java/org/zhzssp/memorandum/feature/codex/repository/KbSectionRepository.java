package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbSection;

import java.util.List;

@Repository
public interface KbSectionRepository extends JpaRepository<KbSection, Long> {

    List<KbSection> findByDocumentIdOrderByOrdAsc(Long documentId);

    boolean existsByDocumentIdAndAnchor(Long documentId, String anchor);

    @Modifying
    @Query("delete from KbSection s where s.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);

    @Modifying
    @Query("delete from KbSection s where s.documentId in " +
            "(select d.id from KbDocument d where d.repoId = :repoId)")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
