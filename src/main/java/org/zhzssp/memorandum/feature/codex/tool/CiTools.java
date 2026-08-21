package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.ci.CiCheck;
import org.zhzssp.memorandum.feature.codex.ci.KnowledgeCiService;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识 CI 工具（只读）。
 *
 * <h3>喂给模型的结果结构是刻意设计的</h3>
 * <p>每条发现都带 {@code locator}（{@code 文件:行}）与 {@code hint}（怎么改）。
 * 只给「有问题」而不给位置和改法，模型就只能把报告转述给用户——
 * 那样这个工具的价值仅等于一个 grep。带上位置与改法，模型才能直接接着修。</p>
 *
 * <p>{@code SKIPPED} 的项<strong>必须一并返回并附原因</strong>：
 * 否则模型会把「9 项检查有 7 项 OK」总结成「知识库很健康」，
 * 而实际上另外两项根本没跑。</p>
 */
@Component
public class CiTools {

    /** 单次返回给 LLM 的发现条数上限：报告本身不该打爆上下文。 */
    private static final int MAX_FINDINGS_TO_MODEL = 40;

    private final KnowledgeCiService ci;
    private final RepoRegistryService registry;

    public CiTools(KnowledgeCiService ci, RepoRegistryService registry) {
        this.ci = ci;
        this.registry = registry;
    }

    @AgentTool(name = "ci.run_local", tags = {"codex", "read"},
            description = "对知识仓库跑一轮知识 CI（只读，不修改任何文件）：死链、锚点失效、"
                    + "笔记回挂双向性、示例入库、front-matter、止损线悬空、检验脚本可执行、"
                    + "主角数值一致性、孤岛文档。用户问「我的知识库有什么问题/健康吗/帮我体检」时调用。"
                    + "返回的每条发现都带文件:行位置与修复建议，可直接据此修复。"
                    + "注意 status=SKIPPED 的项是「没检查」不是「通过」，总结时必须说明。")
    public Map<String, Object> runLocal(
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName,
            @ToolParam(value = "checks", desc = "只跑指定检查，逗号分隔，如 "
                    + "DEAD_LINK,BACKREF_BIDIRECTIONAL；省略则全跑") String checks,
            @ToolParam(value = "severity", desc = "只返回不低于该级别的发现："
                    + "ERROR / WARN / INFO（默认 WARN，避免 INFO 淹没报告）") String severity
    ) {
        User u = AgentContext.requireUser();
        if (!registry.operational()) {
            return Map.of("error", "CODEX_DISABLED",
                    "message", registry.enabled()
                            ? "系统未安装 git，知识仓库功能不可用。"
                            : "知识仓库功能未启用（codex.enabled=false）。");
        }
        KnowledgeRepo repo = resolveRepo(u.getId(), repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND",
                    "message", "未找到知识仓库，可先调用 repo.list。");
        }

        Set<CiCheck.CheckId> only = parseChecks(checks);
        CiCheck.Severity floor = parseSeverity(severity);
        CiCheck.Report report = ci.run(repo, only);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", report.repoName());
        m.put("passed", report.passed());
        m.put("errors", report.errors());
        m.put("warns", report.warns());
        m.put("infos", report.infos());
        m.put("durationMs", report.durationMs());

        List<Map<String, Object>> checkRows = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        int emitted = 0;
        for (CiCheck.CheckResult cr : report.checks()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("check", cr.check().name());
            row.put("label", cr.check().label());
            row.put("status", cr.status().name());
            row.put("scanned", cr.scanned());
            row.put("errors", cr.errors());
            row.put("warns", cr.warns());
            if (cr.skipReason() != null) row.put("skipReason", cr.skipReason());
            checkRows.add(row);

            for (CiCheck.Finding f : cr.findings()) {
                if (f.severity().ordinal() > floor.ordinal()) continue;   // ERROR=0 最严重
                if (emitted >= MAX_FINDINGS_TO_MODEL) continue;
                Map<String, Object> fr = new LinkedHashMap<>();
                fr.put("check", f.check().name());
                fr.put("severity", f.severity().name());
                fr.put("locator", f.locator());
                fr.put("message", f.message());
                if (f.hint() != null) fr.put("hint", f.hint());
                findings.add(fr);
                emitted++;
            }
        }
        m.put("checks", checkRows);
        m.put("findings", findings);

        long total = report.checks().stream()
                .flatMap(c -> c.findings().stream())
                .filter(f -> f.severity().ordinal() <= floor.ordinal())
                .count();
        if (total > emitted) {
            m.put("_truncated", "共 " + total + " 条符合级别的发现，只返回前 "
                    + emitted + " 条。修完这批再重跑。");
        }
        long skipped = report.checks().stream()
                .filter(c -> c.status() == CiCheck.Status.SKIPPED).count();
        if (skipped > 0) {
            m.put("_skippedNotice", skipped + " 项检查因前提不满足未执行（见各项 skipReason）。"
                    + "回答时必须说明这些项「未检查」，不可当作通过。");
        }
        return m;
    }

    /* ---------------- 内部 ---------------- */

    private Set<CiCheck.CheckId> parseChecks(String raw) {
        if (raw == null || raw.isBlank()) return EnumSet.allOf(CiCheck.CheckId.class);
        Set<CiCheck.CheckId> out = EnumSet.noneOf(CiCheck.CheckId.class);
        for (String s : raw.split(",")) {
            String k = s.strip().toUpperCase().replace('-', '_');
            if (k.isEmpty()) continue;
            try {
                out.add(CiCheck.CheckId.valueOf(k));
            } catch (Exception ignored) {
                // 无效名忽略而非报错：模型偶尔会拼错一个，为此让整次调用失败不划算
            }
        }
        return out.isEmpty() ? EnumSet.allOf(CiCheck.CheckId.class) : out;
    }

    private CiCheck.Severity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) return CiCheck.Severity.WARN;
        try {
            return CiCheck.Severity.valueOf(raw.strip().toUpperCase());
        } catch (Exception e) {
            return CiCheck.Severity.WARN;
        }
    }

    private KnowledgeRepo resolveRepo(Long userId, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(userId, repoName.strip()).orElse(null);
        }
        List<KnowledgeRepo> all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }
}
