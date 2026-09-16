package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.zhzssp.memorandum.feature.codex.entity.KbTaskProjection;

import java.util.List;
import java.util.Optional;

public interface KbTaskProjectionRepository extends JpaRepository<KbTaskProjection, Long> {

    List<KbTaskProjection> findByRepoId(Long repoId);

    List<KbTaskProjection> findByUserIdAndState(Long userId, KbTaskProjection.State state);

    Optional<KbTaskProjection> findByRepoIdAndPointId(Long repoId, String pointId);

    Optional<KbTaskProjection> findByTaskId(Long taskId);

    @Modifying
    @Query("delete from KbTaskProjection p where p.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
