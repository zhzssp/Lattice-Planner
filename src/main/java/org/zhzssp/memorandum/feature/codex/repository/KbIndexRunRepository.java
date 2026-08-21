package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbIndexRun;

import java.util.List;

@Repository
public interface KbIndexRunRepository extends JpaRepository<KbIndexRun, Long> {

    /** 最近一次运行（仪表盘显示增量命中率与耗时）。 */
    @Query("select r from KbIndexRun r where r.repoId = :repoId order by r.startedAt desc limit 1")
    KbIndexRun findLatest(@Param("repoId") Long repoId);

    List<KbIndexRun> findByRepoIdOrderByStartedAtDesc(Long repoId);
}
