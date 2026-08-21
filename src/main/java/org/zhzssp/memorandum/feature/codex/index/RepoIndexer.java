package org.zhzssp.memorandum.feature.codex.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.feature.codex.entity.*;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.repository.*;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingClient;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingVectorCache;
import org.zhzssp.memorandum.feature.codex.service.CodexMetrics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 仓库索引编排器。
 *
 * <h3>核心职责</h3>
 * <p>把「Git 工作副本」变成「可检索的派生索引」，并保证这个过程是
 * <strong>增量的</strong>、<strong>可重建的</strong>、<strong>可观测的</strong>。</p>
 *
 * <h3>增量判定为何用 git 的 blob hash</h3>
 * <p>{@code git hash-object} 的结果与 git 自己的对象库一致，因此天然处理了
 * {@code core.autocrlf} 的行尾转换。若改成应用层读文件算 SHA-256，
 * 在 Windows 上会与 git 视角不一致，表现为「增量索引永远不命中」——
 * 而这种故障几乎不会被归因到行尾，只会被当成「索引有点慢」。</p>
 *
 * <h3>为什么必须支持「外部修改」</h3>
 * <p>用户会绕过本软件直接在 IDE 里改文件（这本来就是 Git-native 架构的目的）。
 * 因此索引器<strong>不能假设自己是唯一写入方</strong>：每次同步都重新比对全部
 * blob hash，而不是只处理「软件自己写过的文件」。</p>
 */
@Service
public class RepoIndexer {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexer.class);

    private final GitClient git;
    private final RepoScanner scanner;
    private final FrontMatterParser fmParser;
    private final MarkdownStructureParser structureParser;
    private final SectionAwareChunker chunker;
    private final LinkExtractor linkExtractor;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingVectorCache vectorCache;

    private final KnowledgeRepoRepository repoRepo;
    private final KbDocumentRepository docRepo;
    private final KbSectionRepository sectionRepo;
    private final KbChunkRepository chunkRepo;
    private final KbLinkRepository linkRepo;
    private final KbIndexRunRepository runRepo;
    private final CodexMetrics metrics;

    @Value("${codex.index.max-chunks-per-document:400}")
    private int maxChunksPerDocument;

    @Value("${codex.index.section-aware:true}")
    private boolean sectionAware;

    @Value("${codex.index.embed-batch:32}")
    private int embedBatch;

    @Value("${codex.index.embed-enabled:true}")
    private boolean embedEnabled;

    public RepoIndexer(GitClient git,
                       RepoScanner scanner,
                       FrontMatterParser fmParser,
                       MarkdownStructureParser structureParser,
                       SectionAwareChunker chunker,
                       LinkExtractor linkExtractor,
                       EmbeddingClient embeddingClient,
                       EmbeddingVectorCache vectorCache,
                       KnowledgeRepoRepository repoRepo,
                       KbDocumentRepository docRepo,
                       KbSectionRepository sectionRepo,
                       KbChunkRepository chunkRepo,
                       KbLinkRepository linkRepo,
                       KbIndexRunRepository runRepo,
                       CodexMetrics metrics) {
        this.git = git;
        this.scanner = scanner;
        this.fmParser = fmParser;
        this.structureParser = structureParser;
        this.chunker = chunker;
        this.linkExtractor = linkExtractor;
        this.embeddingClient = embeddingClient;
        this.vectorCache = vectorCache;
        this.repoRepo = repoRepo;
        this.docRepo = docRepo;
        this.sectionRepo = sectionRepo;
        this.chunkRepo = chunkRepo;
        this.linkRepo = linkRepo;
        this.runRepo = runRepo;
        this.metrics = metrics;
    }

    /** 索引结果摘要（返回给 UI / 工具）。 */
    public record IndexReport(long runId,
                              KbIndexRun.Mode mode,
                              int docsTotal,
                              int docsReindexed,
                              int docsSkipped,
                              int docsRemoved,
                              int chunksWritten,
                              int embedCalls,
                              int brokenLinks,
                              int truncatedDocs,
                              long durationMs,
                              String error) {

        public boolean success() {
            return error == null;
        }

        /** 增量命中率——增量索引是否真的生效的直接证据。 */
        public double skipRate() {
            return docsTotal <= 0 ? 0.0 : (double) docsSkipped / docsTotal;
        }
    }

    /**
     * 索引一个仓库。
     *
     * @param repo  仓库
     * @param full  true = 全量（忽略 blobHash，用于 rebuild-index 验证「可重建」）
     */
    @Transactional
    public IndexReport index(KnowledgeRepo repo, boolean full) {
        long t0 = System.currentTimeMillis();
        KbIndexRun run = new KbIndexRun();
        run.setRepoId(repo.getId());
        run.setMode(full ? KbIndexRun.Mode.FULL : KbIndexRun.Mode.INCREMENTAL);
        run.setStartedAt(LocalDateTime.now());
        run.setStatus(KbIndexRun.Status.RUNNING);
        run = runRepo.save(run);

        int reindexed = 0, skipped = 0, removed = 0, chunksWritten = 0;
        int embedCalls = 0, truncatedDocs = 0;
        String error = null;

        try {
            Path root = Paths.get(repo.getLocalPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("仓库路径不存在：" + root);
            }

            RepoLayout layout = RepoLayout.defaults(maxChunksPerDocument, sectionAware);
            List<RepoScanner.ScannedFile> files = scanner.scan(root, layout);

            if (full) {
                // 全量：先清空派生数据。这正是「可重建」验收的执行路径。
                clearDerived(repo.getId());
            }

            // 批量算 hash 与末次提交，避免 N 次进程调用
            List<Path> absPaths = files.stream().map(RepoScanner.ScannedFile::absolute).toList();
            List<String> hashes = git.blobHashes(root, absPaths);
            Map<String, GitClient.CommitInfo> commits = git.lastCommits(root,
                    files.stream().map(RepoScanner.ScannedFile::relativePath).toList());

            Map<String, KbDocument> existing = new HashMap<>();
            for (KbDocument d : docRepo.findByRepoId(repo.getId())) {
                existing.put(d.getPath(), d);
            }
            Set<String> seen = new HashSet<>();

            for (int i = 0; i < files.size(); i++) {
                RepoScanner.ScannedFile f = files.get(i);
                seen.add(f.relativePath());
                String hash = (i < hashes.size()) ? hashes.get(i) : null;
                KbDocument prev = existing.get(f.relativePath());

                // 增量跳过：hash 一致说明内容未变，chunk 与 section 全部可复用
                if (!full && prev != null && hash != null && hash.equals(prev.getBlobHash())) {
                    skipped++;
                    continue;
                }

                DocIndexResult r = indexOneDocument(repo, root, f, hash,
                        commits.get(f.relativePath()), prev, layout);
                if (r == null) continue;
                reindexed++;
                chunksWritten += r.chunks();
                embedCalls += r.embedCalls();
                if (r.truncated()) truncatedDocs++;
            }

            // 清理仓库中已删除的文档（外部删除也要能感知）
            for (Map.Entry<String, KbDocument> e : existing.entrySet()) {
                if (seen.contains(e.getKey())) continue;
                deleteDocumentData(e.getValue().getId());
                docRepo.delete(e.getValue());
                removed++;
            }

            // 二次扫描解析链接目标（必须等全部文档都已落库才能解析 path → documentId）
            int broken = resolveLinks(repo.getId());

            repo.setLastSyncedSha(safeHead(root));
            repo.setLastSyncedAt(LocalDateTime.now());
            repo.setSyncStatus(KnowledgeRepo.SyncStatus.IDLE);
            repo.setSyncError(null);
            repoRepo.save(repo);

            // 向量缓存失效：只清该仓库的桶，不动笔记桶
            vectorCache.invalidate(repo.getUserId(), EmbeddingVectorCache.scopeOfRepo(repo.getId()));

            run.setDocsTotal(files.size());
            run.setDocsReindexed(reindexed);
            run.setDocsSkipped(skipped);
            run.setDocsRemoved(removed);
            run.setChunksWritten(chunksWritten);
            run.setEmbedCalls(embedCalls);
            run.setBrokenLinks(broken);
            run.setTruncatedDocs(truncatedDocs);
            run.setStatus(KbIndexRun.Status.SUCCESS);
            run.setDurationMs(System.currentTimeMillis() - t0);
            run = runRepo.save(run);

            log.info("[Codex] 仓库「{}」索引完成：mode={} total={} reindexed={} skipped={}({}%) "
                            + "removed={} chunks={} embedCalls={} broken={} truncated={} {}ms",
                    repo.getName(), run.getMode(), files.size(), reindexed, skipped,
                    files.isEmpty() ? 0 : Math.round(100.0 * skipped / files.size()),
                    removed, chunksWritten, embedCalls, broken, truncatedDocs,
                    run.getDurationMs());

            metrics.recordIndexRun(true, reindexed, skipped, embedCalls, truncatedDocs);
            return new IndexReport(run.getId(), run.getMode(), files.size(), reindexed, skipped,
                    removed, chunksWritten, embedCalls, broken, truncatedDocs,
                    run.getDurationMs(), null);

        } catch (Exception ex) {
            error = ex.getMessage();
            log.warn("[Codex] 仓库「{}」索引失败：{}", repo.getName(), error, ex);
            run.setStatus(KbIndexRun.Status.FAILED);
            run.setError(truncate(error, 500));
            run.setDurationMs(System.currentTimeMillis() - t0);
            runRepo.save(run);

            repo.setSyncStatus(KnowledgeRepo.SyncStatus.ERROR);
            repo.setSyncError(truncate(error, 500));
            repoRepo.save(repo);

            metrics.recordIndexRun(false, reindexed, skipped, embedCalls, truncatedDocs);
            return new IndexReport(run.getId(), run.getMode(), 0, reindexed, skipped, removed,
                    chunksWritten, embedCalls, 0, truncatedDocs,
                    System.currentTimeMillis() - t0, error);
        }
    }

    private record DocIndexResult(int chunks, int embedCalls, boolean truncated) {}

    /** 索引单篇文档。 */
    private DocIndexResult indexOneDocument(KnowledgeRepo repo,
                                            Path root,
                                            RepoScanner.ScannedFile f,
                                            String hash,
                                            GitClient.CommitInfo commit,
                                            KbDocument prev,
                                            RepoLayout layout) {
        // PDF 等二进制源只登记元信息，不切片（正文提取交给 MCP 文档工具按需进行）
        boolean textual = isTextual(f.relativePath());

        String content = "";
        if (textual) {
            try {
                content = Files.readString(f.absolute(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 编码异常不应中断整仓索引：退回宽松读取
                try {
                    content = new String(Files.readAllBytes(f.absolute()), StandardCharsets.UTF_8);
                } catch (Exception e2) {
                    log.warn("[Codex] 读取失败，跳过 {}：{}", f.relativePath(), e2.getMessage());
                    return null;
                }
            }
        }

        FrontMatterParser.Result fm = fmParser.parse(content);
        MarkdownStructureParser.Result struct = textual
                ? structureParser.parse(content, fm.bodyStart())
                : new MarkdownStructureParser.Result(null, List.of());

        KbDocument doc = (prev != null) ? prev : new KbDocument();
        doc.setRepoId(repo.getId());
        doc.setUserId(repo.getUserId());
        doc.setPath(f.relativePath());

        // kind 优先取 front-matter 声明，其次按路径规则推断——
        // 「全部字段可选」的具体体现：没声明也能正确归类
        String fmKind = fm.str("kind");
        doc.setKind(fmKind != null && !fmKind.isBlank()
                ? KbDocument.DocKind.of(fmKind)
                : f.rule().kind());
        String fmSubkind = fm.str("subkind");
        doc.setSubkind(fmSubkind != null && !fmSubkind.isBlank() ? fmSubkind : f.rule().subkind());

        String title = firstNonBlank(fm.str("title"), struct.title(), fileNameOf(f.relativePath()));
        doc.setTitle(truncate(title, 500));
        doc.setFrontMatterJson(fmParser.toJson(fm.data()));
        doc.setBlobHash(hash != null ? hash : String.valueOf(content.hashCode()));
        doc.setCharCount(content.length());
        doc.setFmValid(!fm.hasError());
        doc.setFmError(truncate(fm.firstError(), 500));
        if (commit != null) {
            doc.setGitUpdatedAt(commit.committedAt());
            doc.setGitLastAuthor(truncate(commit.author(), 120));
        }
        doc.setIndexedAt(LocalDateTime.now());
        doc.setTruncated(false);
        doc.setLossRatio(0.0);
        doc.setChunkCount(0);
        doc = docRepo.save(doc);

        // 重建前清空该文档的派生数据
        deleteDocumentData(doc.getId());

        if (!textual) {
            return new DocIndexResult(0, 0, false);
        }

        // ---- 章节 ----
        Map<Integer, Long> sectionIdByOrd = new HashMap<>();
        Map<Integer, String> anchorByOrd = new HashMap<>();
        for (MarkdownStructureParser.Section s : struct.sections()) {
            KbSection sec = new KbSection();
            sec.setDocumentId(doc.getId());
            sec.setAnchor(truncate(s.anchor(), 250));
            sec.setHeading(truncate(s.heading(), 500));
            sec.setHeadingPath(truncate(s.headingPath(), 1000));
            sec.setLevel(s.level());
            sec.setOrd(s.ord());
            sec.setCharStart(s.charStart());
            sec.setCharEnd(s.charEnd());
            try {
                sec = sectionRepo.save(sec);
                sectionIdByOrd.put(s.ord(), sec.getId());
                anchorByOrd.put(s.ord(), sec.getAnchor());
            } catch (Exception e) {
                // anchor 唯一约束冲突（同名标题超出 GitHub 去重规则的极端情况）：跳过该节
                log.debug("[Codex] 章节落库跳过 {}#{}：{}",
                        f.relativePath(), s.anchor(), e.getMessage());
            }
        }

        // ---- 切片 ----
        SectionAwareChunker.Result cr = chunker.chunk(doc.getTitle(), content,
                fm.bodyStart(),
                layout.sectionAware() ? struct.sections() : List.of(),
                layout.maxChunksPerDocument());

        if (cr.truncated()) {
            // 落库 + 告警：截断绝不静默（延续项目既有原则）
            doc.setTruncated(true);
            doc.setLossRatio(cr.lossRatio());
            log.warn("[Codex] {} 切片触顶：共 {} 字符仅覆盖 {} 字符（丢失约 {}%），chunk={} 达上限 {}。"
                            + "该文档后半部分无法被检索到，可调大 codex.index.max-chunks-per-document。",
                    f.relativePath(), cr.charsTotal(), cr.charsUsed(),
                    Math.round(cr.lossRatio() * 100), cr.chunks().size(),
                    layout.maxChunksPerDocument());
        }

        // ---- 向量化（分批；失败降级为仅关键字可检索）----
        List<SectionAwareChunker.Chunk> chunks = cr.chunks();
        List<float[]> vectors = new ArrayList<>(Collections.nCopies(chunks.size(), null));
        int embedCalls = 0;
        if (embedEnabled && !chunks.isEmpty()) {
            int batch = Math.max(1, embedBatch);
            for (int i = 0; i < chunks.size(); i += batch) {
                int to = Math.min(chunks.size(), i + batch);
                List<String> texts = new ArrayList<>(to - i);
                for (int j = i; j < to; j++) texts.add(chunks.get(j).content());
                try {
                    List<float[]> vs = embeddingClient.embed(texts);
                    embedCalls++;
                    if (vs != null && vs.size() == texts.size()) {
                        for (int j = 0; j < vs.size(); j++) vectors.set(i + j, vs.get(j));
                    }
                } catch (Exception e) {
                    // 关键降级：embedding 不可用时仍落 chunk，FULLTEXT 通路照常工作
                    log.warn("[Codex] {} 第 {}~{} 批 embedding 失败，该批降级为仅关键字检索：{}",
                            f.relativePath(), i, to, e.getMessage());
                }
            }
        }

        String model = null;
        try {
            model = embeddingClient.modelName();
        } catch (Exception ignored) {
            // 未配置 embedding 时取模型名也会失败，属预期
        }

        for (int i = 0; i < chunks.size(); i++) {
            SectionAwareChunker.Chunk c = chunks.get(i);
            KbChunk kc = new KbChunk();
            kc.setUserId(repo.getUserId());
            kc.setRepoId(repo.getId());
            kc.setDocumentId(doc.getId());
            kc.setSectionId(c.sectionOrd() == null ? null : sectionIdByOrd.get(c.sectionOrd()));
            kc.setChunkIdx(c.idx());
            kc.setHeadingPath(truncate(c.headingPath(), 1000));
            kc.setAnchor(truncate(c.anchor(), 250));
            kc.setContent(c.content());
            float[] v = vectors.get(i);
            if (v != null) {
                kc.setEmbedding(embeddingClient.serialize(v));
                kc.setDim(v.length);
                kc.setModel(model);
            }
            kc.setBlobHash(doc.getBlobHash());
            chunkRepo.save(kc);
        }
        doc.setChunkCount(chunks.size());
        docRepo.save(doc);

        // ---- 链接 ----
        for (LinkExtractor.Extracted e : linkExtractor.extract(content, fm.bodyStart())) {
            KbLink l = new KbLink();
            l.setRepoId(repo.getId());
            l.setSrcDocumentId(doc.getId());
            l.setRawTarget(truncate(e.rawTarget(), 760));
            l.setTargetPath(truncate(normalizeTarget(f.relativePath(), e.path()), 500));
            l.setTargetAnchor(truncate(e.anchor(), 250));
            l.setKind(e.kind());
            linkRepo.save(l);
        }

        return new DocIndexResult(chunks.size(), embedCalls, cr.truncated());
    }

    /**
     * 二次扫描：把 targetPath 解析为 documentId，并判定死链。
     *
     * <p>必须在全部文档落库后再做——否则「A 指向 B」时若 B 还没被索引，
     * 会被误判为死链。这是两趟处理的原因。</p>
     */
    private int resolveLinks(Long repoId) {
        Map<String, Long> byPath = new HashMap<>();
        Map<Long, Set<String>> anchorsByDoc = new HashMap<>();
        for (KbDocument d : docRepo.findByRepoId(repoId)) {
            byPath.put(d.getPath(), d.getId());
            Set<String> anchors = new HashSet<>();
            for (KbSection s : sectionRepo.findByDocumentIdOrderByOrdAsc(d.getId())) {
                anchors.add(s.getAnchor());
            }
            anchorsByDoc.put(d.getId(), anchors);
        }
        // [[标题]] 需要按标题反查
        Map<String, Long> byTitle = new HashMap<>();
        for (KbDocument d : docRepo.findByRepoId(repoId)) {
            if (d.getTitle() != null) byTitle.putIfAbsent(d.getTitle().strip(), d.getId());
        }

        int broken = 0;
        List<KbLink> links = linkRepo.findByRepoId(repoId);
        for (KbLink l : links) {
            if (l.getKind() == KbLink.LinkKind.EXTERNAL) continue;

            Long targetId = null;
            if (l.getKind() == KbLink.LinkKind.WIKI) {
                targetId = byTitle.get(l.getTargetPath() == null ? "" : l.getTargetPath().strip());
                if (targetId == null) {
                    l.setBroken(true);
                    l.setBrokenReason(KbLink.BrokenReason.NO_FILE);
                    broken++;
                    linkRepo.save(l);
                    continue;
                }
                l.setTargetDocumentId(targetId);
                l.setBroken(false);
                linkRepo.save(l);
                continue;
            }

            String tp = l.getTargetPath();
            if (tp == null || tp.isBlank()) {
                // 同文档内 anchor 跳转
                targetId = l.getSrcDocumentId();
            } else {
                targetId = byPath.get(tp);
                if (targetId == null) {
                    // 目标可能是目录（如 docs/notes/）或未被索引的文件类型（.sh/.py/.cu）
                    // 这类不算死链，只是不在索引范围内，标记为无目标但不 broken
                    if (isIndexableTarget(tp)) {
                        l.setBroken(true);
                        l.setBrokenReason(KbLink.BrokenReason.NO_FILE);
                        broken++;
                    } else {
                        l.setBroken(false);
                    }
                    linkRepo.save(l);
                    continue;
                }
            }
            l.setTargetDocumentId(targetId);

            String anchor = l.getTargetAnchor();
            if (anchor != null && !anchor.isBlank()) {
                Set<String> anchors = anchorsByDoc.getOrDefault(targetId, Set.of());
                if (!anchors.contains(anchor)) {
                    // 文件存在但 anchor 失效——改标题的典型后果，IDE 完全不报错
                    l.setBroken(true);
                    l.setBrokenReason(KbLink.BrokenReason.NO_ANCHOR);
                    broken++;
                    linkRepo.save(l);
                    continue;
                }
            }
            l.setBroken(false);
            l.setBrokenReason(null);
            linkRepo.save(l);
        }
        return broken;
    }

    /** 目标是否属于「应当被索引」的类型；目录与脚本不算死链。 */
    private boolean isIndexableTarget(String path) {
        String p = path.toLowerCase();
        if (p.endsWith("/")) return false;
        return p.endsWith(".md") || p.endsWith(".markdown");
    }

    /** 相对链接 → 仓库内规范化路径。 */
    public static String normalizeTarget(String srcPath, String target) {
        if (target == null || target.isBlank()) return null;
        if (target.startsWith("/")) {
            return normalizeSlashes(target.substring(1));
        }
        int slash = srcPath.lastIndexOf('/');
        String dir = (slash < 0) ? "" : srcPath.substring(0, slash);
        String joined = dir.isEmpty() ? target : dir + "/" + target;
        return normalizeSlashes(joined);
    }

    /** 折叠 {@code .} 与 {@code ..}，统一正斜杠。 */
    public static String normalizeSlashes(String p) {
        String[] parts = p.replace('\\', '/').split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!stack.isEmpty()) stack.pollLast();
                continue;
            }
            stack.addLast(part);
        }
        return String.join("/", stack);
    }

    /** 删除单篇文档的全部派生数据。 */
    private void deleteDocumentData(Long documentId) {
        chunkRepo.deleteByDocumentId(documentId);
        sectionRepo.deleteByDocumentId(documentId);
        linkRepo.deleteBySrcDocumentId(documentId);
    }

    /** 清空整仓派生数据（rebuild-index 的执行路径，也是「可重建」的验收依据）。 */
    @Transactional
    public void clearDerived(Long repoId) {
        chunkRepo.deleteByRepoId(repoId);
        sectionRepo.deleteByRepoId(repoId);
        linkRepo.deleteByRepoId(repoId);
        docRepo.deleteByRepoId(repoId);
    }

    private String safeHead(Path root) {
        try {
            return git.headSha(root);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isTextual(String path) {
        String p = path.toLowerCase();
        return p.endsWith(".md") || p.endsWith(".markdown") || p.endsWith(".txt");
    }

    private static String fileNameOf(String path) {
        int i = path.lastIndexOf('/');
        String name = (i < 0) ? path : path.substring(i + 1);
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
