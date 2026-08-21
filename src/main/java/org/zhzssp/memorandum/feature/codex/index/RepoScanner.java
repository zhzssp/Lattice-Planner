package org.zhzssp.memorandum.feature.codex.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 遍历工作副本，产出待索引文件清单。
 *
 * <p>路径一律规范化为<strong>正斜杠相对路径</strong>：Windows 上 {@code Path} 用反斜杠，
 * 但仓库里的 Markdown 链接、{@code repo.yml} 的 glob、git 的 {@code ls-files} 输出
 * 全都是正斜杠。不统一会导致三者互相匹配不上——这类 bug 只在 Windows 上出现，
 * 极难在 Linux CI 里发现。</p>
 */
@Component
public class RepoScanner {

    private static final Logger log = LoggerFactory.getLogger(RepoScanner.class);

    /** 单文件字符上限：超过则跳过并告警，防御性避免 OOM。 */
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    /**
     * 扫描到的一个文件。
     *
     * @param relativePath 正斜杠相对路径
     * @param absolute     绝对路径
     * @param rule         命中的布局规则
     */
    public record ScannedFile(String relativePath, Path absolute, RepoLayout.Rule rule) {}

    /**
     * 遍历仓库。
     *
     * @param repoRoot 工作副本根目录
     * @param layout   布局配置
     */
    public List<ScannedFile> scan(Path repoRoot, RepoLayout layout) {
        List<ScannedFile> out = new ArrayList<>();
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            log.warn("[Codex] 仓库路径不存在或不是目录：{}", repoRoot);
            return out;
        }
        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    // .git 等隐藏目录直接剪枝：不只是为了排除，
                    // .git 里有成千上万个对象文件，走进去会让扫描慢几个数量级
                    if (!dir.equals(repoRoot) && name.startsWith(".")
                            && !name.equals(".lattice")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String rel = relativize(repoRoot, dir);
                    if (!rel.isEmpty() && layout.excluded(rel + "/x")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel = relativize(repoRoot, file);
                    if (rel.isEmpty() || layout.excluded(rel)) return FileVisitResult.CONTINUE;
                    if (attrs.size() > MAX_FILE_BYTES) {
                        log.warn("[Codex] 跳过超大文件（{} 字节）：{}", attrs.size(), rel);
                        return FileVisitResult.CONTINUE;
                    }
                    RepoLayout.Rule rule = layout.resolve(rel);
                    if (rule == null) return FileVisitResult.CONTINUE;
                    out.add(new ScannedFile(rel, file, rule));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 权限不足 / 符号链接循环等：跳过单个文件，不中断整次扫描
                    log.debug("[Codex] 访问失败，跳过 {}：{}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[Codex] 遍历仓库失败 {}：{}", repoRoot, e.getMessage());
        }
        out.sort(java.util.Comparator.comparing(ScannedFile::relativePath));
        return out;
    }

    /** 绝对路径 → 正斜杠相对路径。 */
    static String relativize(Path root, Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return "";
        }
    }
}
