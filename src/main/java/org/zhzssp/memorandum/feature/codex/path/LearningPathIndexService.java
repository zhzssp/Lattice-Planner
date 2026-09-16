package org.zhzssp.memorandum.feature.codex.path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbPathSnapshot;
import org.zhzssp.memorandum.feature.codex.entity.KbPoint;
import org.zhzssp.memorandum.feature.codex.entity.KbStation;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbPathSnapshotRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbPointRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbStationRepository;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 从 Git 工作副本的路径文件重建车站/要点索引。
 */
@Service
public class LearningPathIndexService {

    private static final Logger log = LoggerFactory.getLogger(LearningPathIndexService.class);

    public record SyncResult(boolean present, boolean parseOk, String error,
                             int stations, int must, int skip) {}

    private final LearningPathParser parser;
    private final RepoRegistryService registry;
    private final KbDocumentRepository docRepo;
    private final KbStationRepository stationRepo;
    private final KbPointRepository pointRepo;
    private final KbPathSnapshotRepository snapRepo;
    private final PathProjector projector;
    private final PathChurnService churn;

    public LearningPathIndexService(LearningPathParser parser,
                                    RepoRegistryService registry,
                                    KbDocumentRepository docRepo,
                                    KbStationRepository stationRepo,
                                    KbPointRepository pointRepo,
                                    KbPathSnapshotRepository snapRepo,
                                    PathProjector projector,
                                    PathChurnService churn) {
        this.parser = parser;
        this.registry = registry;
        this.docRepo = docRepo;
        this.stationRepo = stationRepo;
        this.pointRepo = pointRepo;
        this.snapRepo = snapRepo;
        this.projector = projector;
        this.churn = churn;
    }

    @Transactional
    public SyncResult syncFromRepo(KnowledgeRepo repo) {
        Optional<KbDocument> doc = findPathDoc(repo.getId());
        if (doc.isEmpty()) {
            pointRepo.deleteByRepoId(repo.getId());
            stationRepo.deleteByRepoId(repo.getId());
            upsertSnap(repo, null, 1, null, false, "尚未抽出路径（缺少 docs/learning-path.md）。",
                    0, 0, 0);
            reconcileQuietly(repo);
            return new SyncResult(false, false, "NO_PATH_FILE", 0, 0, 0);
        }

        Path file = registry.rootOf(repo).resolve(doc.get().getPath());
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            saveError(repo, doc.get(), "读路径文件失败：" + e.getMessage());
            return new SyncResult(true, false, e.getMessage(), 0, 0, 0);
        }

        LearningPathParser.ParsedPath parsed = parser.parseDocument(content, true);
        if (!parsed.ok()) {
            saveError(repo, doc.get(), parsed.error());
            return new SyncResult(true, false, parsed.error(), 0, 0, 0);
        }

        pointRepo.deleteByRepoId(repo.getId());
        stationRepo.deleteByRepoId(repo.getId());

        int ord = 0;
        String cursor = parsed.cursor();
        if (cursor == null && !parsed.stations().isEmpty()) {
            cursor = parsed.stations().get(0).id();
        }
        for (LearningPathParser.ParsedStation s : parsed.stations()) {
            KbStation row = new KbStation();
            row.setRepoId(repo.getId());
            row.setUserId(repo.getUserId());
            row.setDocumentId(doc.get().getId());
            row.setStationId(s.id());
            row.setTitle(s.title());
            row.setOrdinal(ord++);
            row.setSourcesCsv(join(s.sources()));
            row.setLab(s.lab());
            row.setNextCsv(join(s.next()));
            row.setCursorFlag(s.id().equals(cursor));
            row = stationRepo.save(row);
            for (LearningPathParser.ParsedPoint p : s.points()) {
                KbPoint pt = new KbPoint();
                pt.setRepoId(repo.getId());
                pt.setUserId(repo.getUserId());
                pt.setStationRowId(row.getId());
                pt.setStationId(s.id());
                pt.setPointId(p.id());
                pt.setLevel(p.level());
                pt.setStatement(trunc(p.statement(), 1000));
                pointRepo.save(pt);
            }
        }
        upsertSnap(repo, doc.get(), parsed.version(), cursor, true, null,
                parsed.stations().size(), parsed.mustCount(), parsed.skipCount());
        try {
            churn.record(repo, parsed);
        } catch (Exception e) {
            log.debug("[Codex/Path] churn 记录跳过：{}", e.getMessage());
        }
        log.info("[Codex/Path] 仓库「{}」路径已同步：{} 站，MUST {} / SKIP {}",
                repo.getName(), parsed.stations().size(), parsed.mustCount(), parsed.skipCount());
        reconcileQuietly(repo);
        return new SyncResult(true, true, null, parsed.stations().size(),
                parsed.mustCount(), parsed.skipCount());
    }

    private void reconcileQuietly(KnowledgeRepo repo) {
        try {
            projector.reconcile(repo.getUserId(), repo);
        } catch (Exception e) {
            log.debug("[Codex/Path] 投影对账跳过：{}", e.getMessage());
        }
    }

    public Optional<KbDocument> findPathDoc(Long repoId) {
        List<KbDocument> docs = docRepo.findByRepoId(repoId);
        Optional<KbDocument> byKind = docs.stream()
                .filter(d -> d.getKind() == KbDocument.DocKind.LEARNING_PATH)
                .findFirst();
        if (byKind.isPresent()) return byKind;
        return docs.stream()
                .filter(d -> d.getPath() != null
                        && d.getPath().replace('\\', '/').endsWith("learning-path.md"))
                .findFirst();
    }

    private void saveError(KnowledgeRepo repo, KbDocument doc, String error) {
        KbPathSnapshot prev = snapRepo.findByRepoId(repo.getId()).orElse(null);
        upsertSnap(repo, doc,
                prev == null ? 1 : prev.getVersion(),
                prev == null ? null : prev.getCursor(),
                false, trunc(error, 500),
                prev == null ? 0 : prev.getStationCount(),
                prev == null ? 0 : prev.getMustCount(),
                prev == null ? 0 : prev.getSkipCount());
    }

    private void upsertSnap(KnowledgeRepo repo, KbDocument doc, int version, String cursor,
                            boolean ok, String error, int stations, int must, int skip) {
        KbPathSnapshot snap = snapRepo.findByRepoId(repo.getId()).orElseGet(KbPathSnapshot::new);
        snap.setRepoId(repo.getId());
        snap.setUserId(repo.getUserId());
        snap.setDocumentId(doc == null ? null : doc.getId());
        snap.setVersion(version);
        snap.setCursor(cursor);
        snap.setParseOk(ok);
        snap.setParseError(error);
        snap.setStationCount(stations);
        snap.setMustCount(must);
        snap.setSkipCount(skip);
        snap.setIndexedAt(LocalDateTime.now());
        snapRepo.save(snap);
    }

    private static String join(List<String> xs) {
        if (xs == null || xs.isEmpty()) return null;
        return String.join(",", xs);
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
