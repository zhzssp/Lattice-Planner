package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.zhzssp.memorandum.feature.codex.entity.KbPoint;

import java.util.List;
import java.util.Optional;

public interface KbPointRepository extends JpaRepository<KbPoint, Long> {

    List<KbPoint> findByRepoIdOrderByPointIdAsc(Long repoId);

    List<KbPoint> findByRepoIdAndStationId(Long repoId, String stationId);

    Optional<KbPoint> findByRepoIdAndPointId(Long repoId, String pointId);

    @Modifying
    @Query("delete from KbPoint p where p.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
