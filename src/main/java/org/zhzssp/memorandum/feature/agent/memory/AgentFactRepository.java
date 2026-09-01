package org.zhzssp.memorandum.feature.agent.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Facts 仓储。
 *
 * <p>查询刻意分开「稳定」与「易变」，语义明确，避免 {@code (:kind is null or ...)}
 * 这类在 Hibernate 下对未类型化 null 参数行为不稳定的写法。</p>
 */
public interface AgentFactRepository extends JpaRepository<AgentFact, Long> {

    /** 某用户的全部 ACTIVE 稳定 facts，按最近更新倒序。 */
    @Query("select f from AgentFact f where f.userId = :userId and f.kind = "
            + "org.zhzssp.memorandum.feature.agent.memory.AgentFact.Kind.STABLE "
            + "and f.status = org.zhzssp.memorandum.feature.agent.memory.AgentFact.Status.ACTIVE "
            + "order by f.updatedAt desc")
    List<AgentFact> findStableActive(@Param("userId") Long userId);

    /**
     * 某用户在 {@code before} 之前创建的 ACTIVE 稳定 facts，按最近更新倒序。
     *
     * <p>支撑 {@code stable-apply-granularity=DAY}：稳定 facts 进 system prompt 并参与
     * memoHash，取「今天零点前」这一刀能让该段在一整天内字节不变。</p>
     */
    @Query("select f from AgentFact f where f.userId = :userId and f.kind = "
            + "org.zhzssp.memorandum.feature.agent.memory.AgentFact.Kind.STABLE "
            + "and f.status = org.zhzssp.memorandum.feature.agent.memory.AgentFact.Status.ACTIVE "
            + "and f.createdAt < :before "
            + "order by f.updatedAt desc")
    List<AgentFact> findStableActiveCreatedBefore(@Param("userId") Long userId,
                                                  @Param("before") java.time.LocalDateTime before);

    /** 某会话的全部 ACTIVE 易变 facts，按最近更新倒序。 */
    @Query("select f from AgentFact f where f.userId = :userId and f.sessionId = :sessionId "
            + "and f.kind = org.zhzssp.memorandum.feature.agent.memory.AgentFact.Kind.VOLATILE "
            + "and f.status = org.zhzssp.memorandum.feature.agent.memory.AgentFact.Status.ACTIVE "
            + "order by f.updatedAt desc")
    List<AgentFact> findVolatileActive(@Param("userId") Long userId,
                                       @Param("sessionId") String sessionId);

    /** 某用户某 key 的现有事实（无论会话），用于覆盖判定。 */
    Optional<AgentFact> findByUserIdAndFactKeyAndStatus(Long userId, String factKey,
                                                       AgentFact.Status status);

    /**
     * 某用户某 key 的最近一条事实（<strong>不限状态</strong>）。
     *
     * <p>覆盖判定必须能看到 {@code REJECTED} 的历史——否则「用户标错的 key 永不再抽」
     * 这条保证会失效：查 ACTIVE 查不到，于是又抽成一条新的 ACTIVE。</p>
     */
    Optional<AgentFact> findTopByUserIdAndFactKeyOrderByUpdatedAtDesc(Long userId, String factKey);

    /** 某用户全部事实（UI 管理用），按更新倒序。 */
    List<AgentFact> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
