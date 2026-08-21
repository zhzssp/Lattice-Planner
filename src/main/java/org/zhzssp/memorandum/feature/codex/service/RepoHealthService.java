package org.zhzssp.memorandum.feature.codex.service;

import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.codex.entity.*;
import org.zhzssp.memorandum.feature.codex.repository.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓库健康度聚合（仪表盘 + 可观测指标数据源）。
 *
 * <p>这里的每个数字都对应一个「知识库会不会烂掉」的具体病症：</p>
 * <table>
 *   <tr><td>死链</td><td>改标题后静默失效，点了才 404</td></tr>
 *   <tr><td>anchor 断链</td><td>比死链更隐蔽——文件在，节没了</td></tr>
 *   <tr><td>孤岛</td><td>沉淀了但检索/导航都到不了 = 白沉淀</td></tr>
 *   <tr><td>截断</td><td>索引只覆盖了文档一部分，Agent 却声称已检索</td></tr>
 *   <tr><td>增量命中率</td><td>长期为 0 说明 blobHash 判定坏了</td></tr>
 * </table>
 */
@Service
public class RepoHealthService {

    private final KbDocumentRepository docRepo;
    private final KbChunkRepository chunkRepo;
    private final KbLinkRepository linkRepo;
    private final KbIndexRunRepository runRepo;
    private final KbSectionRepository sectionRepo;

    public RepoHealthService(KbDocumentRepository docRepo,
                             KbChunkRepository chunkRepo,
                             KbLinkRepository linkRepo,
                             KbIndexRunRepository runRepo,
                             KbSectionRepository sectionRepo) {
        this.docRepo = docRepo;
        this.chunkRepo = chunkRepo;
        this.linkRepo = linkRepo;
        this.runRepo = runRepo;
        this.sectionRepo = sectionRepo;
    }

    /** 一条断链的可读描述。 */
    public record BrokenLinkView(String srcPath, String rawTarget, String reason) {}

    /** 健康快照。 */
    public record Health(long documents,
                         long chunks,
                         long truncatedDocs,
                         long brokenLinks,
                         long orphanDocs,
                         long sections,
                         Map<String, Long> kindDistribution,
                         Map<String, Object> lastRun) {}

    public Health snapshot(Long repoId) {
        long docs = docRepo.countByRepoId(repoId);
        long chunks = chunkRepo.countByRepoId(repoId);
        long truncated = docRepo.countByRepoIdAndTruncatedTrue(repoId);
        long broken = linkRepo.countByRepoIdAndBrokenTrue(repoId);
        long orphan = linkRepo.findOrphanDocumentIds(repoId).size();

        long sections = 0;
        for (KbDocument d : docRepo.findByRepoId(repoId)) {
            sections += sectionRepo.findByDocumentIdOrderByOrdAsc(d.getId()).size();
        }

        Map<String, Long> dist = new LinkedHashMap<>();
        for (KbDocumentRepository.KindCount kc : docRepo.countByKind(repoId)) {
            dist.put(kc.getKind().label(), kc.getCnt());
        }

        Map<String, Object> last = new LinkedHashMap<>();
        KbIndexRun run = runRepo.findLatest(repoId);
        if (run != null) {
            last.put("mode", run.getMode().name());
            last.put("status", run.getStatus().name());
            last.put("startedAt", run.getStartedAt() == null ? null : run.getStartedAt().toString());
            last.put("durationMs", run.getDurationMs());
            last.put("docsTotal", run.getDocsTotal());
            last.put("docsReindexed", run.getDocsReindexed());
            last.put("docsSkipped", run.getDocsSkipped());
            last.put("docsRemoved", run.getDocsRemoved());
            last.put("chunksWritten", run.getChunksWritten());
            last.put("embedCalls", run.getEmbedCalls());
            // 增量索引有效性：长期为 0 就该怀疑 blobHash 计算
            int total = run.getDocsTotal() == null ? 0 : run.getDocsTotal();
            int skip = run.getDocsSkipped() == null ? 0 : run.getDocsSkipped();
            last.put("skipRate", total == 0 ? 0.0 : round4((double) skip / total));
            last.put("error", run.getError());
        }

        return new Health(docs, chunks, truncated, broken, orphan, sections, dist, last);
    }

    /** 断链清单（供 CI 报告与仪表盘）。 */
    public List<BrokenLinkView> brokenLinks(Long repoId, int limit) {
        Map<Long, String> pathById = new LinkedHashMap<>();
        for (KbDocument d : docRepo.findByRepoId(repoId)) {
            pathById.put(d.getId(), d.getPath());
        }
        List<BrokenLinkView> out = new ArrayList<>();
        for (KbLink l : linkRepo.findByRepoIdAndBrokenTrue(repoId)) {
            if (out.size() >= limit) break;
            out.add(new BrokenLinkView(
                    pathById.getOrDefault(l.getSrcDocumentId(), "?"),
                    l.getRawTarget(),
                    l.getBrokenReason() == null ? "UNKNOWN" : l.getBrokenReason().name()));
        }
        return out;
    }

    /** 被截断的文档清单——需要用户决定是否调大上限。 */
    public List<Map<String, Object>> truncatedDocuments(Long repoId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbDocument d : docRepo.findByRepoId(repoId)) {
            if (!Boolean.TRUE.equals(d.getTruncated())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", d.getPath());
            m.put("charCount", d.getCharCount());
            m.put("chunkCount", d.getChunkCount());
            m.put("lossRatio", round4(d.getLossRatio() == null ? 0 : d.getLossRatio()));
            out.add(m);
        }
        return out;
    }

    /** 孤岛文档（无任何入链）。 */
    public List<String> orphanDocuments(Long repoId, int limit) {
        List<Long> ids = linkRepo.findOrphanDocumentIds(repoId);
        List<String> out = new ArrayList<>();
        for (Long id : ids) {
            if (out.size() >= limit) break;
            docRepo.findById(id).ifPresent(d -> out.add(d.getPath()));
        }
        return out;
    }

    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }
}
