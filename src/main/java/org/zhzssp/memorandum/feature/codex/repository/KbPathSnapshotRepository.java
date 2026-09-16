package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.zhzssp.memorandum.feature.codex.entity.KbPathSnapshot;

import java.util.Optional;

public interface KbPathSnapshotRepository extends JpaRepository<KbPathSnapshot, Long> {

    Optional<KbPathSnapshot> findByRepoId(Long repoId);

    @Modifying
    @Query("delete from KbPathSnapshot s where s.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
