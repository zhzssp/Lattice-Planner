package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbCheckpointRepository extends JpaRepository<KbCheckpoint, Long> {

    List<KbCheckpoint> findByRepoIdOrderByCodeAsc(Long repoId);

    List<KbCheckpoint> findByUserIdOrderByCodeAsc(Long userId);

    Optional<KbCheckpoint> findByRepoIdAndCode(Long repoId, String code);

    Optional<KbCheckpoint> findByIdAndUserId(Long id, Long userId);

    /** 按 code 在用户全部仓库中查找（工具入口通常只拿到 code）。 */
    @Query("select c from KbCheckpoint c where c.userId = :userId and upper(c.code) = upper(:code)")
    Optional<KbCheckpoint> findByUserIdAndCode(@Param("userId") Long userId,
                                               @Param("code") String code);

    List<KbCheckpoint> findByRepoIdAndStatus(Long repoId, KbCheckpoint.Status status);

    long countByRepoId(Long repoId);

    long countByRepoIdAndStatus(Long repoId, KbCheckpoint.Status status);

    long countByRepoIdAndLevelAndStatus(Long repoId, KbCheckpoint.Level level,
                                        KbCheckpoint.Status status);

    long countByRepoIdAndPredictionCorrect(Long repoId, Boolean predictionCorrect);

    @Query("select count(c) from KbCheckpoint c where c.repoId = :repoId " +
            "and c.predictionCorrect is not null")
    long countPredictionJudged(@Param("repoId") Long repoId);

    /**
     * 「下一条该做什么」：优先未完成且已具备前置的最低级别条目。
     *
     * <p>排序刻意先按 level 再按 code：入门线要求「L0 全做 + 一半 L1 + 至少两条 L2」，
     * 跳着做 L3 没有意义。</p>
     */
    @Query("select c from KbCheckpoint c where c.repoId = :repoId " +
            "and c.status in ('TODO', 'PREDICTED', 'FAILED') " +
            "order by c.level asc, c.code asc")
    List<KbCheckpoint> findNextCandidates(@Param("repoId") Long repoId);

    /** 级别 × 状态分布（掌握度视图）。 */
    @Query("select c.level as level, c.status as status, count(c) as cnt " +
            "from KbCheckpoint c where c.repoId = :repoId group by c.level, c.status")
    List<LevelStatusCount> countByLevelAndStatus(@Param("repoId") Long repoId);

    interface LevelStatusCount {
        KbCheckpoint.Level getLevel();
        KbCheckpoint.Status getStatus();
        long getCnt();
    }

    @Modifying
    @Query("delete from KbCheckpoint c where c.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") Long repoId);
}
