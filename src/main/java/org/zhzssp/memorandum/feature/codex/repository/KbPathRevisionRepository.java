package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.zhzssp.memorandum.feature.codex.entity.KbPathRevision;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KbPathRevisionRepository extends JpaRepository<KbPathRevision, Long> {

    Optional<KbPathRevision> findTopByRepoIdOrderByIdDesc(Long repoId);

    List<KbPathRevision> findByRepoIdAndCreatedAtAfterOrderByCreatedAtAsc(
            Long repoId, LocalDateTime after);

    @Modifying
    @Query("delete from KbPathRevision r where r.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
