package org.zhzssp.memorandum.feature.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统 Prompt + 历史拼接器。按 mode 选择工具子集：
 *  chat    -> 全部
 *  plan    -> 任务/目标/规划/读+写（+ kb 读，便于规划时引用历史笔记）
 *  reflect -> 任务/目标/insight/笔记/读（+ kb 读，复盘时检索过往）
 *  learn   -> kb/note/read（"我以前学过 X 吗"等纯检索问答）
 *
 * P1 重构（Prefix Caching）：拆 buildPrefix / assemble，支持前缀缓存复用。
 */
@Component
public class PromptBuilder {

    private final ToolRegistry registry;
    private final ObjectMapper om;
    private final PrefixCache prefixCache;

    public PromptBuilder(ToolRegistry registry, ObjectMapper om, PrefixCache prefixCache) {
        this.registry = registry;
        this.om = om;
        this.prefixCache = prefixCache;
    }

    /** 兼容旧签名：内部 buildPrefix + assemble。 */
    public List<Map<String, String>> build(String mode,
                                           List<ConversationMemory.Msg> history,
                                           String longTermMemo) throws JsonProcessingException {
        return assemble(buildPrefix(mode, longTermMemo), history);
    }

    /** 构造 system 前缀（可从 PrefixCache 命中复用）。 */
    public PrefixCache.CachedPrefix buildPrefix(String mode, String longTermMemo) throws JsonProcessingException {
        Set<String> tagFilter = resolveTagFilter(mode);
        String toolsJson = om.writerWithDefaultPrettyPrinter()
                .writeValueAsString(registry.exportSchemas(tagFilter));
        String memoSection = (longTermMemo == null || longTermMemo.isBlank())
                ? "(暂无)" : longTermMemo;

        String dateBucket = dateBucket();
        String toolsetHash = sha256(toolsJson);
        String memoHash = sha256(memoSection);
        PrefixCache.PrefixKey key = new PrefixCache.PrefixKey(
                mode == null ? "chat" : mode, toolsetHash, memoHash, dateBucket);

        // 命中缓存直接返回，miss 则构造并回填
        return prefixCache.getOrCompute(key, () -> {
            String sys = buildSystemPrompt(dateBucket, toolsJson, memoSection);
            String hash = sha256(sys);
            return new PrefixCache.CachedPrefix(sys, hash);
        });
    }

    /** 用已构造的前缀 + history 拼装完整 messages。 */
    public List<Map<String, String>> assemble(PrefixCache.CachedPrefix prefix,
                                               List<ConversationMemory.Msg> history) {
        List<Map<String, String>> msgs = new ArrayList<>();
        Map<String, String> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", prefix.content());
        msgs.add(sysMsg);
        for (ConversationMemory.Msg m : history) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", m.role());
            entry.put("content", m.content());
            msgs.add(entry);
        }
        return msgs;
    }

    /** 供 CRAG / 其它调用方获取当前 tagFilter（S4 访问：Self-RAG 协议提示）。 */
    public Set<String> tagFilterForMode(String mode) {
        return resolveTagFilter(mode);
    }

    /* ---- 内部 ---- */

    private Set<String> resolveTagFilter(String mode) {
        return switch (mode == null ? "chat" : mode) {
            case "plan" -> Set.of("task", "goal", "planner", "kb", "read", "write", "subagent", "mcp");
            case "reflect" -> Set.of("task", "goal", "insight", "note", "kb", "read", "subagent", "mcp");
            case "learn" -> Set.of("kb", "note", "read", "subagent", "mcp");
            default -> null;
        };
    }

    private String buildSystemPrompt(String dateBucket, String toolsJson, String memoSection) {
        return """
                你是 Lattice-Planner 内置的规划助手 Lattice-Agent。今天是 %s。
                你与用户协作管理目标 / 任务 / 笔记 / 复盘，并可读取用户本地文档。

                【可用工具】（必须使用工具完成读写操作，不要编造数据）
                %s

                【输出协议】（严格遵守）
                - 如需调用工具：仅输出一个 JSON 对象，形如
                  {"tool":"task.search","arguments":{"keyword":"周报"}}
                  不要解释，不要 Markdown 围栏，不要附带其他文字。
                - 如已能给出最终答复：直接输出自然语言中文，不要再输出 JSON。
                - 一次只能调用一个工具；看到工具结果后再决定下一步。
                - 标 "需用户确认" 的工具会触发弹窗，请只在用户明确意图后再调用。
                - 不可编造任务/目标/笔记的 id，所有 id 必须来自工具返回的真实数据。

                【知识检索原则】（涉及"我"自身经验/笔记时严格执行）
                - 涉及"我"、"我的笔记/项目/经验"、"我之前学过 X"、"上次我们说过 Y" 等表述时，
                  必须先调用 kb.semantic_search 检索个人知识库（笔记 + 已摄取本地文档）。
                - kb.semantic_search 返回的 grade 为 INCORRECT 或 degraded=true 时，
                  表示检索质量差，须在答复首句明示"未找到强相关笔记，以下基于通用知识："，
                  然后再给一般性回答，不可假装命中。
                - kb.semantic_search 返回的 grade 为 AMBIGUOUS 时，可谨慎引用但须提醒用户
                  "检索结果相关性一般，仅供参考"。
                - 命中并真正引用某篇笔记时，使用 [[标题]] 写法（不带路径），用户可点击跳转。
                - 不要把 kb 工具与 note.create 混用：semantic_search/lookup_by_title 是"读"，
                  仅在用户显式要求"记一笔"时才 note.create。

                【本地文档读取原则】（涉及用户本地磁盘文件时执行）
                - 当用户提到具体本地文件路径，或要求"读取/总结/分析某份文档（含 PDF/Word/Excel 等）"时，
                  调用 mcp.loopback.local.read_document 获取内容后再回答；不要凭空猜测文件内容。
                - 不确定文件是否存在时，可先调用 mcp.loopback.local.list_dir 列目录确认。
                - 如果白名单报错，提示用户将文件移入系统返回的允许目录（默认是用户主目录下的所有位置）。
                - 工具返回的 content 若标注 isSummarized=true，说明原文过长已被摘要，
                  回答时可提示"以下基于文档摘要"，必要时建议用户询问更细节的部分。
                - 仅使用只读工具（read_document/list_dir/read_file/read_pdf），
                  不要尝试调用 kb.ingest 等写入/摄取操作。
                - 严禁调用任何不含 mcp. 前缀的 local.* 工具（旧的 Electron 桥接工具已下线，
                  调用会失败并误导用户）。

                【用户长期记忆（来自历史 Agent 会话归档）】
                %s
                """.formatted(dateBucket, toolsJson, memoSection);
    }

    /** 天级稳定日期，避免同一天内前缀因毫秒差异漂移。 */
    private static String dateBucket() {
        return LocalDate.now().toString();
    }

    /** SHA-256 摘要（前缀 hash / toolsetHash / memoHash）。 */
    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
