package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpointRun;

import java.util.List;

@Repository
public interface KbCheckpointRunRepository extends JpaRepository<KbCheckpointRun, Long> {

    List<KbCheckpointRun> findByCheckpointIdOrderByStartedAtDesc(Long checkpointId);

    @Query("select r from KbCheckpointRun r where r.checkpointId = :cpId " +
            "order by r.startedAt desc limit 1")
    KbCheckpointRun findLatest(@Param("cpId") Long checkpointId);

    long countByUserId(Long userId);

    /** 被安全闸门拦下的次数——证明白名单机制是否必要。 */
    @Query("select count(r) from KbCheckpointRun r where r.userId = :userId " +
            "and r.rejectReason is not null")
    long countRejected(@Param("userId") Long userId);
}
