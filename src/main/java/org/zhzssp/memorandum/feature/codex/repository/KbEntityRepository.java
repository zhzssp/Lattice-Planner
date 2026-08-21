package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbEntityRepository extends JpaRepository<KbEntity, Long> {

    List<KbEntity> findByRepoId(Long repoId);

    Optional<KbEntity> findByRepoIdAndName(Long repoId, String name);

    @Modifying
    @Query("delete from KbEntity e where e.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
