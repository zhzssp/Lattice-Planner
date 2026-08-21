package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeRepoRepository extends JpaRepository<KnowledgeRepo, Long> {

    List<KnowledgeRepo> findByUserIdOrderByIdAsc(Long userId);

    List<KnowledgeRepo> findByUserIdAndEnabledTrueOrderByIdAsc(Long userId);

    Optional<KnowledgeRepo> findByUserIdAndName(Long userId, String name);

    Optional<KnowledgeRepo> findByIdAndUserId(Long id, Long userId);
}
