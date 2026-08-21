package org.zhzssp.memorandum.feature.codex.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.feature.codex.entity.KbGap;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbGapRepository extends JpaRepository<KbGap, Long> {

    Optional<KbGap> findByIdAndUserId(Long id, Long userId);

    Optional<KbGap> findByUserIdAndSourceAndNormQuestion(Long userId,
                                                         KbGap.Source source,
                                                         String normQuestion);

    /**
     * 看板主查询：待处理缺口按「被问到次数」倒序。
     *
     * <p>排序键刻意是 {@code askCount} 而非时间：缺口台账的价值在于
     * <strong>告诉你该先补哪个</strong>，而「问得最多的」正是最挡路的。
     * 按时间排会让一次性的偶发提问顶在最前面。</p>
     */
    @Query("select g from KbGap g where g.userId = :userId " +
            "and g.status in ('OPEN', 'PLANNED') " +
            "order by g.askCount desc, g.lastAt desc")
    List<KbGap> findActionable(@Param("userId") Long userId);

    /**
     * 用户全部缺口。
     *
     * <p>刻意<strong>不写成 {@code (:repoId is null or g.repoId = :repoId)}</strong>：
     * 那种「一个查询兼两种语义」的写法在 Hibernate 下对未类型化的 null 参数
     * 行为不稳定（不同版本表现不同），出问题时报的是难以归因的类型推断错误。
     * 两个方法各自语义明确，调用方选一个。</p>
     */
    @Query("select g from KbGap g where g.userId = :userId " +
            "order by g.askCount desc, g.lastAt desc")
    List<KbGap> findAllForUser(@Param("userId") Long userId);

    @Query("select g from KbGap g where g.userId = :userId and g.repoId = :repoId " +
            "order by g.askCount desc, g.lastAt desc")
    List<KbGap> findAllForUserAndRepo(@Param("userId") Long userId,
                                      @Param("repoId") Long repoId);

    List<KbGap> findByUserIdAndStatusOrderByAskCountDesc(Long userId, KbGap.Status status);

    List<KbGap> findByEntityId(Long entityId);

    long countByUserIdAndStatus(Long userId, KbGap.Status status);

    long countByUserIdAndSource(Long userId, KbGap.Source source);

    /** 按来源 × 状态分布（看板顶部摘要）。列序：[source, status, cnt]。 */
    @Query("select g.source as source, g.status as status, count(g) as cnt " +
            "from KbGap g where g.userId = :userId group by g.source, g.status")
    List<SourceStatusCount> countBySourceAndStatus(@Param("userId") Long userId);

    interface SourceStatusCount {
        KbGap.Source getSource();
        KbGap.Status getStatus();
        long getCnt();
    }
}
