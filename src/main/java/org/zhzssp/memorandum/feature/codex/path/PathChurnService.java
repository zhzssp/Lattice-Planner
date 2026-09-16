package org.zhzssp.memorandum.feature.codex.path;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.feature.codex.entity.KbPathRevision;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.repository.KbPathRevisionRepository;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 路径文件的变动历史。文案是「变动变小 ≈ 在收敛」，不做 0–100 分。
 */
@Service
public class PathChurnService {

    public static final int WINDOW_DAYS = 14;

    public record Diff(int added, int removed, boolean reordered) {}

    private final KbPathRevisionRepository revRepo;
    private final RepoRegistryService registry;
    private final GitClient git;

    public PathChurnService(KbPathRevisionRepository revRepo,
                            RepoRegistryService registry,
                            GitClient git) {
        this.revRepo = revRepo;
        this.registry = registry;
        this.git = git;
    }

    public static Diff diff(List<String> prevStations, Set<String> prevPoints,
                            List<String> nextStations, Set<String> nextPoints) {
        Set<String> prevP = prevPoints == null ? Set.of() : prevPoints;
        Set<String> nextP = nextPoints == null ? Set.of() : nextPoints;
        Set<String> added = new HashSet<>(nextP);
        added.removeAll(prevP);
        Set<String> removed = new HashSet<>(prevP);
        removed.removeAll(nextP);
        List<String> prevS = prevStations == null ? List.of() : prevStations;
        List<String> nextS = nextStations == null ? List.of() : nextStations;
        boolean reordered = !prevS.isEmpty() && !nextS.isEmpty() && !prevS.equals(nextS);
        return new Diff(added.size(), removed.size(), reordered);
    }

    public void record(KnowledgeRepo repo, LearningPathParser.ParsedPath parsed) {
        if (repo == null || parsed == null || !parsed.ok()) return;
        List<String> stationIds = new ArrayList<>();
        List<String> pointIds = new ArrayList<>();
        for (var s : parsed.stations()) {
            stationIds.add(s.id());
            for (var p : s.points()) pointIds.add(p.id());
        }
        String stationsCsv = String.join(",", stationIds);
        String pointsCsv = String.join(",", pointIds);
        KbPathRevision last = revRepo.findTopByRepoIdOrderByIdDesc(repo.getId()).orElse(null);
        if (last != null
                && last.getVersion() == parsed.version()
                && Objects.equals(last.getStationIdsCsv(), stationsCsv)
                && Objects.equals(last.getPointIdsCsv(), pointsCsv)) {
            return;
        }
        Diff d = last == null
                ? new Diff(pointIds.size(), 0, false)
                : diff(split(last.getStationIdsCsv()), set(last.getPointIdsCsv()),
                stationIds, new HashSet<>(pointIds));
        KbPathRevision row = new KbPathRevision();
        row.setRepoId(repo.getId());
        row.setUserId(repo.getUserId());
        row.setVersion(parsed.version());
        row.setHeadSha(headSha(repo));
        row.setNStations(parsed.stations().size());
        row.setNMust(parsed.mustCount());
        row.setNSkip(parsed.skipCount());
        row.setAddedPoints(d.added());
        row.setRemovedPoints(d.removed());
        row.setReorderedStations(d.reordered());
        row.setStationIdsCsv(trunc(stationsCsv, 1024));
        row.setPointIdsCsv(trunc(pointsCsv, 2048));
        row.setCreatedAt(LocalDateTime.now());
        revRepo.save(row);
    }

    public Map<String, Object> summary(Long repoId) {
        LocalDateTime after = LocalDateTime.now().minusDays(WINDOW_DAYS);
        List<KbPathRevision> rows = revRepo.findByRepoIdAndCreatedAtAfterOrderByCreatedAtAsc(
                repoId, after);
        int added = 0;
        int removed = 0;
        int reorderEvents = 0;
        List<Map<String, Object>> events = new ArrayList<>();
        for (KbPathRevision r : rows) {
            added += r.getAddedPoints();
            removed += r.getRemovedPoints();
            if (r.isReorderedStations()) reorderEvents++;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("at", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
            e.put("version", r.getVersion());
            e.put("added", r.getAddedPoints());
            e.put("removed", r.getRemovedPoints());
            e.put("reordered", r.isReorderedStations());
            e.put("must", r.getNMust());
            e.put("stations", r.getNStations());
            events.add(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", WINDOW_DAYS);
        m.put("events", events.size());
        m.put("addedPoints", added);
        m.put("removedPoints", removed);
        m.put("reorderEvents", reorderEvents);
        m.put("caption", caption(added, removed, reorderEvents, events.size()));
        m.put("revisions", events);
        return m;
    }

    private static String caption(int added, int removed, int reorder, int events) {
        if (events == 0) {
            return "近 14 天路径文件没有变动。变动变小 ≈ 在收敛；这不是分数。";
        }
        return "近 14 天路径变动 " + events + " 次：要点 +" + added + " / −" + removed
                + (reorder > 0 ? "，站顺序改过 " + reorder + " 次。" : "。")
                + " 变动变小 ≈ 在收敛；改笔记不改路径时这里应为 0。";
    }

    private String headSha(KnowledgeRepo repo) {
        try {
            return git.headSha(registry.rootOf(repo));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return List.of(csv.split(","));
    }

    private static Set<String> set(String csv) {
        return new HashSet<>(split(csv));
    }

    /** 注销仓库时删除 churn 历史；全量重建索引不删，以便仍能看见收敛轨迹。 */
    @Transactional
    public void deleteForRepo(Long repoId) {
        if (repoId != null) revRepo.deleteByRepoId(repoId);
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
