package org.zhzssp.memorandum.agenteval.db;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 端状态探针：只读地检查一次评测跑完后，H2 里<b>真实被改成了什么样</b>。
 *
 * <h3>为什么需要它</h3>
 * 原有断言只能校验「工具被调用过」。但工具被调用 ≠ 事情被做对：
 * 参数解析错了、写库失败了、写进了别人的数据，轨迹断言全都看不出来。
 * 真实发生过的例子是评测用户没落库，写工具<b>全部执行失败</b>，
 * 而九个用例依然全绿。
 *
 * <p>因此这里借鉴 τ-bench 的判分立场：<b>不看模型说了什么，看世界被改成了什么样</b>。
 * 任何能产生等价末态的路径都算通过，不要求 Agent 走某条特定轨迹。
 *
 * <h3>两条实现纪律</h3>
 * <ul>
 *   <li><b>只用最朴素的 SQL。</b>评测库是 H2 的 {@code MODE=MySQL}，它并不是真 MySQL，
 *       {@code DATE()} / {@code DATE_FORMAT()} 这类方言函数行为不一致甚至直接报错。</li>
 *   <li><b>日期比对放在 Java 侧。</b>{@code task.create} 存的是
 *       {@code LocalDate.parse(x).atStartOfDay()}，带时分秒；
 *       在 SQL 里和日期字面量等值比对会恒为假。见 {@link TaskRow#deadlineDate()}。</li>
 * </ul>
 *
 * <p>注意实体表名与直觉不符：{@code Task} 映射到 <b>{@code memo}</b> 表，
 * 截止字段是 {@code deadline} 而非 {@code due_date}；{@code Goal} 的标题字段是
 * {@code name} 而非 {@code title}。
 */
public final class EvalDbProbe {

    private final JdbcTemplate jdbc;
    private final Long userId;

    /** 本用例做过几次端状态断言。供报告标注 {@code endStateChecked}。 */
    private final AtomicInteger checks = new AtomicInteger();

    public EvalDbProbe(JdbcTemplate jdbc, Long userId) {
        this.jdbc = jdbc;
        this.userId = userId;
    }

    /**
     * 一行任务（{@code memo} 表）。
     *
     * @param deadline 带时分秒；按日比对请用 {@link #deadlineDate()}
     */
    public record TaskRow(Long id, String title, String description,
                          LocalDateTime deadline, String status) {

        /** 截止<b>日期</b>（忽略时分秒）。未设置截止时为 null。 */
        public LocalDate deadlineDate() {
            return deadline == null ? null : deadline.toLocalDate();
        }

        /** 标题是否包含给定片段。断言里最常用，单独提出来免得每处都判空。 */
        public boolean titleContains(String fragment) {
            return title != null && title.contains(fragment);
        }
    }

    /** 一行目标（{@code goal} 表）。 */
    public record GoalRow(Long id, String name, String goalType, LocalDateTime archivedAt) {}

    /* ---- 查询 ---- */

    public List<TaskRow> tasks() {
        return jdbc.query(
                "select id, title, description, deadline, status from memo where user_id = ? order by id",
                (rs, i) -> new TaskRow(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getObject("deadline", LocalDateTime.class),
                        rs.getString("status")),
                userId);
    }

    public List<GoalRow> goals() {
        return jdbc.query(
                "select id, name, goal_type, archived_at from goal where user_id = ? order by id",
                (rs, i) -> new GoalRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("goal_type"),
                        rs.getObject("archived_at", LocalDateTime.class)),
                userId);
    }

    public int taskCount() {
        return tasks().size();
    }

    public int goalCount() {
        return goals().size();
    }

    /** 是否存在满足条件的任务。 */
    public boolean anyTask(Predicate<TaskRow> p) {
        return tasks().stream().anyMatch(p);
    }

    /** 满足条件的任务数。 */
    public long countTasks(Predicate<TaskRow> p) {
        return tasks().stream().filter(p).count();
    }

    /* ---- 断言计数（由 TrajectoryAssert 调用） ---- */

    public void markChecked() {
        checks.incrementAndGet();
    }

    public int checkCount() {
        return checks.get();
    }

    public void resetChecks() {
        checks.set(0);
    }

    /* ---- 诊断 ---- */

    /** 断言失败时打印真实库内容——不给这个，端状态失败会极难定位。 */
    public String render() {
        StringBuilder sb = new StringBuilder("端状态 (userId=").append(userId).append("):\n");
        List<TaskRow> ts = tasks();
        sb.append("  memo  ").append(ts.size()).append(" 行\n");
        for (TaskRow t : ts) {
            sb.append(String.format("    #%-4d %-28s deadline=%-12s status=%s%n",
                    t.id(), truncate(t.title(), 28),
                    t.deadlineDate() == null ? "-" : t.deadlineDate(), t.status()));
        }
        List<GoalRow> gs = goals();
        sb.append("  goal  ").append(gs.size()).append(" 行\n");
        for (GoalRow g : gs) {
            sb.append(String.format("    #%-4d %-28s type=%s%n",
                    g.id(), truncate(g.name(), 28), g.goalType()));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
