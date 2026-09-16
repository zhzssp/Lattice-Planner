package org.zhzssp.memorandum.feature.codex.path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.core.service.TaskService;
import org.zhzssp.memorandum.entity.GoalType;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TaskGranularity;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.codex.entity.KbPathSnapshot;
import org.zhzssp.memorandum.feature.codex.entity.KbPoint;
import org.zhzssp.memorandum.feature.codex.entity.KbStation;
import org.zhzssp.memorandum.feature.codex.entity.KbTaskProjection;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.repository.KbPathSnapshotRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbPointRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbStationRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbTaskProjectionRepository;
import org.zhzssp.memorandum.feature.goal.entity.Goal;
import org.zhzssp.memorandum.feature.goal.service.GoalService;
import org.zhzssp.memorandum.repository.LinkRepository;
import org.zhzssp.memorandum.repository.TaskRepository;
import org.zhzssp.memorandum.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路径 MUST → Dashboard 任务。待办只有这一份投影。
 */
@Service
public class PathProjector {

    private static final Logger log = LoggerFactory.getLogger(PathProjector.class);

    public record Report(boolean ok, String code, String message, Long goalId,
                         int created, int active, int stale, int satisfied) {}

    private final KbStationRepository stationRepo;
    private final KbPointRepository pointRepo;
    private final KbPathSnapshotRepository snapRepo;
    private final KbTaskProjectionRepository projRepo;
    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final GoalService goalService;
    private final LinkRepository linkRepository;

    @Value("${codex.path.project-window:2}")
    private int projectWindow;

    public PathProjector(KbStationRepository stationRepo,
                         KbPointRepository pointRepo,
                         KbPathSnapshotRepository snapRepo,
                         KbTaskProjectionRepository projRepo,
                         UserRepository userRepository,
                         TaskRepository taskRepository,
                         TaskService taskService,
                         GoalService goalService,
                         LinkRepository linkRepository) {
        this.stationRepo = stationRepo;
        this.pointRepo = pointRepo;
        this.snapRepo = snapRepo;
        this.projRepo = projRepo;
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.goalService = goalService;
        this.linkRepository = linkRepository;
    }

    /** 只更新已有投影的 ACTIVE/STALE/SATISFIED，不新建任务。 */
    @Transactional
    public Report reconcile(Long userId, KnowledgeRepo repo) {
        return run(userId, repo, false);
    }

    /** 为当前窗口内尚未投影的 MUST 新建任务。 */
    @Transactional
    public Report project(Long userId, KnowledgeRepo repo) {
        return run(userId, repo, true);
    }

    private Report run(Long userId, KnowledgeRepo repo, boolean createMissing) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return new Report(false, "USER_NOT_FOUND", "用户不存在", null, 0, 0, 0, 0);
        }
        KbPathSnapshot snap = snapRepo.findByRepoId(repo.getId()).orElse(null);
        List<KbStation> stations = stationRepo.findByRepoIdOrderByOrdinalAsc(repo.getId());
        if (stations.isEmpty()) {
            markAllStale(repo.getId());
            return new Report(true, "NO_PATH", "尚未抽出路径，没有可投影的 MUST。",
                    null, 0, 0, countState(repo.getId(), KbTaskProjection.State.STALE), 0);
        }
        int version = snap == null ? 1 : snap.getVersion();
        Set<String> windowIds = windowStationIds(stations);
        Set<String> windowMust = new HashSet<>();
        Map<String, KbPoint> mustById = new LinkedHashMap<>();
        for (KbPoint p : pointRepo.findByRepoIdOrderByPointIdAsc(repo.getId())) {
            if (p.getLevel() != KbPoint.Level.MUST) continue;
            mustById.put(p.getPointId(), p);
            if (windowIds.contains(p.getStationId())) {
                windowMust.add(p.getPointId());
            }
        }

        Goal goal = null;
        if (createMissing) {
            goal = ensureGoal(user, repo);
        }

        int created = 0;
        if (createMissing && goal != null) {
            for (String pid : windowMust) {
                if (projRepo.findByRepoIdAndPointId(repo.getId(), pid).isPresent()) continue;
                KbPoint p = mustById.get(pid);
                KbStation st = stations.stream()
                        .filter(s -> s.getStationId().equals(p.getStationId()))
                        .findFirst().orElse(null);
                Task task = new Task();
                task.setTitle(trunc(p.getStatement(), 180));
                task.setDescription(describe(repo, st, p));
                task.setStatus(TaskStatus.PENDING);
                task.setGranularity(TaskGranularity.STANDARD);
                Task saved = taskService.saveTask(task, user);
                Link link = new Link();
                link.setSourceType(Link.LinkSourceType.TASK);
                link.setSourceId(saved.getId());
                link.setTargetType(Link.LinkTargetType.GOAL);
                link.setTargetId(goal.getId());
                linkRepository.save(link);
                KbTaskProjection row = new KbTaskProjection();
                row.setUserId(userId);
                row.setRepoId(repo.getId());
                row.setTaskId(saved.getId());
                row.setGoalId(goal.getId());
                row.setPathVersion(version);
                row.setStationId(p.getStationId());
                row.setPointId(p.getPointId());
                row.setState(KbTaskProjection.State.ACTIVE);
                row.setUpdatedAt(LocalDateTime.now());
                projRepo.save(row);
                created++;
            }
        }

        int active = 0, stale = 0, satisfied = 0;
        for (KbTaskProjection row : projRepo.findByRepoId(repo.getId())) {
            Task task = taskRepository.findById(row.getTaskId()).orElse(null);
            boolean done = task != null && task.getEffectiveStatus() == TaskStatus.DONE;
            KbPoint still = mustById.get(row.getPointId());
            KbTaskProjection.State next;
            if (done && still != null) {
                next = KbTaskProjection.State.SATISFIED;
            } else if (still == null || still.getLevel() != KbPoint.Level.MUST
                    || !windowMust.contains(row.getPointId())) {
                next = (task != null && task.getEffectiveStatus() == TaskStatus.PENDING)
                        ? KbTaskProjection.State.STALE
                        : (done ? KbTaskProjection.State.SATISFIED : KbTaskProjection.State.STALE);
            } else {
                next = KbTaskProjection.State.ACTIVE;
            }
            row.setState(next);
            row.setPathVersion(version);
            row.setUpdatedAt(LocalDateTime.now());
            if (still != null) {
                row.setStationId(still.getStationId());
            }
            projRepo.save(row);
            switch (next) {
                case ACTIVE -> active++;
                case STALE -> stale++;
                case SATISFIED -> satisfied++;
            }
        }
        log.info("[Codex/Path] 投影仓库「{}」：新建 {}，ACTIVE {} / STALE {} / SATISFIED {}",
                repo.getName(), created, active, stale, satisfied);
        Long goalId = goal == null ? firstGoalId(repo.getId()) : goal.getId();
        return new Report(true, createMissing ? "PROJECTED" : "RECONCILED",
                createMissing
                        ? "已为当前站窗口生成 " + created + " 条任务（ACTIVE " + active
                        + " / 过时 " + stale + "）。"
                        : "已按路径窗口对账投影（未新建任务）。",
                goalId, created, active, stale, satisfied);
    }

    public List<Map<String, Object>> listProjections(Long repoId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbTaskProjection row : projRepo.findByRepoId(repoId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", row.getTaskId());
            m.put("goalId", row.getGoalId());
            m.put("stationId", row.getStationId());
            m.put("pointId", row.getPointId());
            m.put("state", row.getState().name());
            m.put("pathVersion", row.getPathVersion());
            taskRepository.findById(row.getTaskId()).ifPresent(t -> {
                m.put("title", t.getTitle());
                m.put("taskStatus", t.getEffectiveStatus().name());
            });
            out.add(m);
        }
        return out;
    }

    private void markAllStale(Long repoId) {
        for (KbTaskProjection row : projRepo.findByRepoId(repoId)) {
            if (row.getState() == KbTaskProjection.State.SATISFIED) continue;
            row.setState(KbTaskProjection.State.STALE);
            row.setUpdatedAt(LocalDateTime.now());
            projRepo.save(row);
        }
    }

    private Set<String> windowStationIds(List<KbStation> stations) {
        int cursorIdx = 0;
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).isCursorFlag()) {
                cursorIdx = i;
                break;
            }
        }
        int n = Math.max(1, projectWindow);
        Set<String> ids = new HashSet<>();
        for (int i = cursorIdx; i < stations.size() && ids.size() < n; i++) {
            ids.add(stations.get(i).getStationId());
        }
        return ids;
    }

    private Goal ensureGoal(User user, KnowledgeRepo repo) {
        String name = "学 · " + repo.getName();
        for (Goal g : goalService.findActiveGoalsByUser(user)) {
            if (name.equals(g.getName())) return g;
        }
        Long existing = firstGoalId(repo.getId());
        if (existing != null) {
            return goalService.findActiveGoalsByUser(user).stream()
                    .filter(g -> existing.equals(g.getId()))
                    .findFirst()
                    .orElseGet(() -> createGoal(user, name));
        }
        return createGoal(user, name);
    }

    private Goal createGoal(User user, String name) {
        Goal g = new Goal();
        g.setUser(user);
        g.setName(name);
        g.setGoalType(GoalType.MID_TERM);
        return goalService.save(g);
    }

    private Long firstGoalId(Long repoId) {
        return projRepo.findByRepoId(repoId).stream()
                .map(KbTaskProjection::getGoalId)
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
    }

    private int countState(Long repoId, KbTaskProjection.State state) {
        int n = 0;
        for (KbTaskProjection p : projRepo.findByRepoId(repoId)) {
            if (p.getState() == state) n++;
        }
        return n;
    }

    private static String describe(KnowledgeRepo repo, KbStation st, KbPoint p) {
        String station = st == null ? p.getStationId()
                : st.getStationId() + " · " + st.getTitle();
        String sources = st == null || st.getSourcesCsv() == null ? "—" : st.getSourcesCsv();
        return "仓库：" + repo.getName()
                + "\n站：" + station
                + "\n要点：" + p.getPointId()
                + "\n级别：MUST"
                + "\n资料：" + sources
                + "\n打开：/codex/distill?station=" + p.getStationId()
                + "&point=" + p.getPointId()
                + "\n\n此任务由学习路径投影。改顺序请改 docs/learning-path.md，不要另起一份待办。";
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
