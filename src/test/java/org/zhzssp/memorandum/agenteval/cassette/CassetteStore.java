package org.zhzssp.memorandum.agenteval.cassette;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 录制盒的持久化与指纹计算。
 *
 * <p>录制文件存放于 {@code src/test/resources/agent-eval/cassettes/<caseId>.json}，
 * <b>随代码一起提交</b>——这样 CI 无需 API Key、无需联网即可跑全套 Agent 评测。
 * 这是把"Agent 测试"变成"可在 CI 中常态运行的回归测试"的前提。
 */
public final class CassetteStore {

    /** 相对项目根目录。录制时写这里，回放时优先读这里（保证 IDE 里录制后立刻可用） */
    private static final String CASSETTE_DIR = "src/test/resources/agent-eval/cassettes";

    private static final ObjectMapper OM = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            // 容忍未知字段：允许在录制盒里写 _comment 等人工注释，
            // 也让录制盒格式后续演进时保持向后兼容（旧文件仍可被新代码读取）
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * 需要从指纹计算中剔除的易变片段。
     *
     * <p>system prompt 里含「今天是 2026-08-18」这类日期，若不规范化，
     * 每过一天所有用例的指纹都会漂移，漂移警告就失去了意义（全是噪声）。
     */
    private static final List<Pattern> VOLATILE_PATTERNS = List.of(
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}"),              // 日期
            Pattern.compile("\\d{2}:\\d{2}:\\d{2}"),              // 时间
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") // UUID
    );

    private CassetteStore() {
    }

    public static Path pathFor(String caseId) {
        return Paths.get(CASSETTE_DIR, caseId + ".json");
    }

    public static boolean exists(String caseId) {
        return Files.exists(pathFor(caseId));
    }

    public static void save(Cassette cassette) {
        try {
            Path p = pathFor(cassette.getCaseId());
            Files.createDirectories(p.getParent());
            OM.writeValue(p.toFile(), cassette);
        } catch (IOException e) {
            throw new UncheckedIOException("写入录制盒失败：" + cassette.getCaseId(), e);
        }
    }

    public static Cassette load(String caseId) {
        Path p = pathFor(caseId);
        if (!Files.exists(p)) {
            throw new IllegalStateException(
                    "缺少录制盒：" + p.toAbsolutePath() + "\n"
                            + "请先以录制模式运行该用例：-Dagent.eval.mode=record "
                            + "（需配置 DEEPSEEK_API_KEY，会产生真实 API 调用）");
        }
        try {
            return OM.readValue(p.toFile(), Cassette.class);
        } catch (IOException e) {
            throw new UncheckedIOException("解析录制盒失败：" + caseId, e);
        }
    }

    /**
     * 计算请求指纹：模型 + 温度 + 规范化后的 messages。
     *
     * <p>规范化会把日期/时间/UUID 替换为占位符，避免"只是过了一天"就判定漂移。
     */
    public static String fingerprint(LlmTransport.ChatRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.model()).append('|').append(req.temperature()).append('|');
        try {
            sb.append(OM.writeValueAsString(req.messages()));
        } catch (Exception e) {
            sb.append(String.valueOf(req.messages()));
        }
        String normalized = sb.toString();
        for (Pattern p : VOLATILE_PATTERNS) {
            normalized = p.matcher(normalized).replaceAll("<VOLATILE>");
        }
        return sha256Short(normalized);
    }

    /** messages 的可读摘要，仅用于人工检视录制文件时判断"这一步在问什么"。 */
    public static String digest(LlmTransport.ChatRequest req) {
        try {
            List<?> msgs = req.messages();
            if (msgs == null || msgs.isEmpty()) return "(empty)";
            Object last = msgs.get(msgs.size() - 1);
            String text = (last instanceof Map<?, ?> m)
                    ? String.valueOf(m.get("content")) : String.valueOf(last);
            text = text.replaceAll("\\s+", " ").trim();
            return "[" + msgs.size() + " msgs] last=" + truncate(text, 200);
        } catch (Exception e) {
            return "(digest failed)";
        }
    }

    private static String sha256Short(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
