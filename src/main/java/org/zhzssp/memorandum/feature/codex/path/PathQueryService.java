package org.zhzssp.memorandum.feature.codex.path;

import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.codex.entity.KbPathSnapshot;
import org.zhzssp.memorandum.feature.codex.entity.KbPoint;
import org.zhzssp.memorandum.feature.codex.entity.KbStation;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.repository.KbPathSnapshotRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbPointRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbStationRepository;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把路径索引读成 Agent / HTTP 共用的视图。权威仍在 Git 文件。
 */
@Service
public class PathQueryService {

    private final RepoRegistryService registry;
    private final KbStationRepository stationRepo;
    private final KbPointRepository pointRepo;
    private final KbPathSnapshotRepository snapRepo;
    private final PathApplyService applyService;
    private final PathProjector projector;
    private final PathChurnService churn;
    private final DocWriteGuard writeGuard;

    public PathQueryService(RepoRegistryService registry,
                            KbStationRepository stationRepo,
                            KbPointRepository pointRepo,
                            KbPathSnapshotRepository snapRepo,
                            PathApplyService applyService,
                            PathProjector projector,
                            PathChurnService churn,
                            DocWriteGuard writeGuard) {
        this.registry = registry;
        this.stationRepo = stationRepo;
        this.pointRepo = pointRepo;
        this.snapRepo = snapRepo;
        this.applyService = applyService;
        this.projector = projector;
        this.churn = churn;
        this.writeGuard = writeGuard;
    }

    public KnowledgeRepo resolve(Long userId, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(userId, repoName.strip()).orElse(null);
        }
        var all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    public Map<String, Object> view(KnowledgeRepo repo) {
        KbPathSnapshot snap = snapRepo.findByRepoId(repo.getId()).orElse(null);
        List<KbStation> stations = stationRepo.findByRepoIdOrderByOrdinalAsc(repo.getId());
        List<KbPoint> points = pointRepo.findByRepoIdOrderByPointIdAsc(repo.getId());
        Map<String, List<Map<String, Object>>> byStation = new LinkedHashMap<>();
        for (KbPoint p : points) {
            byStation.computeIfAbsent(p.getStationId(), k -> new ArrayList<>())
                    .add(Map.of(
                            "id", p.getPointId(),
                            "level", p.getLevel().name(),
                            "statement", p.getStatement()));
        }
        List<Map<String, Object>> stationViews = new ArrayList<>();
        for (KbStation s : stations) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", s.getStationId());
            x.put("title", s.getTitle());
            x.put("ordinal", s.getOrdinal());
            x.put("cursor", s.isCursorFlag());
            x.put("sources", s.getSourcesCsv());
            x.put("lab", s.getLab());
            x.put("next", s.getNextCsv());
            x.put("points", byStation.getOrDefault(s.getStationId(), List.of()));
            stationViews.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repoId", repo.getId());
        m.put("repo", repo.getName());
        m.put("file", LearningPathParser.DEFAULT_PATH);
        m.put("present", !stations.isEmpty());
        m.put("parseOk", snap != null && snap.isParseOk());
        m.put("error", snap == null ? null : snap.getParseError());
        m.put("version", snap == null ? 0 : snap.getVersion());
        m.put("cursor", snap == null ? null : snap.getCursor());
        m.put("must", snap == null ? 0 : snap.getMustCount());
        m.put("skip", snap == null ? 0 : snap.getSkipCount());
        m.put("writeEnabled", writeGuard.enabled(repo.getUserId()));
        m.put("readable", registry.readable(repo.getUserId()));
        m.put("stations", stationViews);
        m.put("content", applyService.readExisting(repo));
        m.put("projections", projector.listProjections(repo.getId()));
        m.put("churn", churn.summary(repo.getId()));
        m.put("coverage", projector.coverage(repo.getId()));
        if (stations.isEmpty()) {
            m.put("message", "尚未抽出路径。去资料页蒸馏后预览 PATH_DELTA，或手写 "
                    + LearningPathParser.DEFAULT_PATH + " 再同步。");
        }
        return m;
    }
}
