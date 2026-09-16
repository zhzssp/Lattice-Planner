package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.zhzssp.memorandum.feature.codex.entity.KbStation;

import java.util.List;
import java.util.Optional;

public interface KbStationRepository extends JpaRepository<KbStation, Long> {

    List<KbStation> findByRepoIdOrderByOrdinalAsc(Long repoId);

    Optional<KbStation> findByRepoIdAndStationId(Long repoId, String stationId);

    @Modifying
    @Query("delete from KbStation s where s.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
