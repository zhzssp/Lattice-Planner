package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbScopeDecision;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbScopeDecisionRepository extends JpaRepository<KbScopeDecision, Long> {

    Optional<KbScopeDecision> findByEntityId(Long entityId);

    List<KbScopeDecision> findByDecision(KbScopeDecision.Decision decision);

    @Modifying
    @Query("delete from KbScopeDecision s where s.entityId in " +
            "(select e.id from KbEntity e where e.repoId = :repoId)")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
