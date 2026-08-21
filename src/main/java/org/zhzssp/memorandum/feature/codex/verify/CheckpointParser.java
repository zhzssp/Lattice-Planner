package org.zhzssp.memorandum.feature.codex.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从既有 Markdown 检验册解析出 checkpoint 条目。
 *
 * <h3>为什么必须支持解析而不是只支持声明式</h3>
 * <p>目标仓库已有 <strong>86 条</strong>手写检验，分布在 9 册 Markdown 里。
 * 若只支持 front-matter 声明式，用户得先把 86 条全部重写一遍才能用——
 * 这种「先交作业才能开始」的设计会让功能永远停在纸面。</p>
 *
 * <h3>如实标注判据强度</h3>
 * <p>解析出的判据只能是「退出码 + 关键词」，达不到声明式 {@code expect} 的精确度。
 * 因此一律标记 {@link KbCheckpoint.VerifySource#PARSED}，UI 明示「判据较弱」。
 * <strong>不假装精确</strong>——否则用户会以为「机器判过了就一定对」。</p>
 *
 * <h3>格式容忍度（实测得出）</h3>
 * <p>真实仓库的写法并不统一，解析器必须容忍：</p>
 * <ul>
 *   <li>标题层级 {@code ###} 与 {@code ####} 混用（08-distributed 用 4 级）；</li>
 *   <li>分隔符是全角 {@code ｜}，偶有半角 {@code |}；</li>
 *   <li>字段值可能带反引号（{@code **资源**：`本地`}）；</li>
 *   <li>「通过标准」可能带括注（{@code **通过标准**（机器可判定）：}）。</li>
 * </ul>
 * <p>宁松勿严：解析不出某个字段就留空，绝不因此丢掉整个条目。</p>
 */
@Component
public class CheckpointParser {

    private static final Logger log = LoggerFactory.getLogger(CheckpointParser.class);

    /** 条目标题：### 或 #### + L0-XXX-01 + 全角/半角分隔符 + 标题。 */
    private static final Pattern ENTRY = Pattern.compile(
            "(?m)^#{3,4}\\s+(L[0-3])-([A-Za-z0-9]+)-(\\d+)\\s*[｜|]\\s*(.+?)\\s*$");

    /** 元信息行：- **资源**：xxx */
    private static final Pattern META = Pattern.compile(
            "(?m)^\\s*[-*]\\s*\\*\\*(检验什么|前置|资源|预计耗时)\\*\\*\\s*[：:]\\s*(.+?)\\s*$");

    /** 段落标题：**验收命令**： / **通过标准**（机器可判定）： */
    private static final Pattern BLOCK = Pattern.compile(
            "(?m)^\\s*\\*\\*(任务|先预测再动手|验收命令|通过标准|常见失败[^*]*)\\*\\*[^：:\\n]*[：:]?\\s*$");

    /** fenced 代码块。 */
    private static final Pattern FENCE = Pattern.compile(
            "(?s)```[a-zA-Z0-9]*\\s*\\n(.*?)```");

    /** 耗时：1.5h / 半天 / 2h（含…） */
    private static final Pattern HOURS = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*h");

    private final ObjectMapper om;

    public CheckpointParser(ObjectMapper om) {
        this.om = om;
    }

    /** 解析出的一条（尚未落库）。 */
    public record Parsed(String code,
                         KbCheckpoint.Level level,
                         String title,
                         String checksWhat,
                         String prerequisite,
                         String resourceTag,
                         BigDecimal estHours,
                         String predictionQuestions,
                         String passCriteria,
                         String blindSpots,
                         String verifyJson,
                         boolean hasCommand) {}

    /**
     * 解析一册检验册。
     *
     * @param content  Markdown 全文
     * @param labHint  该册对应的动手项目目录（从册头「对应动手项目」提取，可为 null）
     */
    public List<Parsed> parse(String content, String labHint) {
        List<Parsed> out = new ArrayList<>();
        if (content == null || content.isBlank()) return out;

        String lab = (labHint != null && !labHint.isBlank()) ? labHint : detectLab(content);

        // 先定位全部条目标题的位置与分组值。
        // Matcher 是有状态的，分组值必须在 find() 循环内即时取出。
        List<int[]> spans = new ArrayList<>();
        List<String[]> groups = new ArrayList<>();
        Matcher m = ENTRY.matcher(content);
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            groups.add(new String[]{m.group(1), m.group(2), m.group(3), m.group(4)});
        }
        if (spans.isEmpty()) return out;

        for (int i = 0; i < spans.size(); i++) {
            int bodyStart = spans.get(i)[1];
            int bodyEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : content.length();
            String body = content.substring(bodyStart, Math.max(bodyStart, bodyEnd));

            String[] g = groups.get(i);
            String code = g[0] + "-" + g[1].toUpperCase() + "-" + g[2];
            KbCheckpoint.Level level = KbCheckpoint.Level.of(g[0]);
            String title = cleanInline(g[3]);

            Map<String, String> meta = parseMeta(body);
            Map<String, String> blocks = parseBlocks(body);

            String cmdBlock = blocks.get("验收命令");
            String cmd = firstFenceContent(cmdBlock);
            String verifyJson = buildVerifyJson(cmd, lab, blocks.get("通过标准"));

            out.add(new Parsed(
                    code,
                    level,
                    title,
                    trimTo(meta.get("检验什么"), 1000),
                    trimTo(meta.get("前置"), 500),
                    normalizeResource(meta.get("资源")),
                    parseHours(meta.get("预计耗时")),
                    trimTo(blocks.get("先预测再动手"), 4000),
                    trimTo(blocks.get("通过标准"), 4000),
                    trimTo(findBlindSpots(blocks), 4000),
                    verifyJson,
                    cmd != null && !cmd.isBlank()));
        }
        return out;
    }

    /* ---------------- 字段解析 ---------------- */

    private Map<String, String> parseMeta(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = META.matcher(body);
        while (m.find()) {
            map.putIfAbsent(m.group(1), cleanInline(m.group(2)));
        }
        return map;
    }

    /** 段落切分：从 **X**： 到下一个段落标题之间的内容。 */
    private Map<String, String> parseBlocks(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        List<int[]> marks = new ArrayList<>();
        List<String> names = new ArrayList<>();

        Matcher m = BLOCK.matcher(body);
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            names.add(m.group(1).strip());
        }
        for (int i = 0; i < marks.size(); i++) {
            int from = marks.get(i)[1];
            int to = (i + 1 < marks.size()) ? marks.get(i + 1)[0] : body.length();
            if (to <= from) continue;
            map.putIfAbsent(names.get(i), body.substring(from, to).strip());
        }
        return map;
    }

    /** 「常见失败 → 盲点」的标题写法不统一，按前缀匹配。 */
    private String findBlindSpots(Map<String, String> blocks) {
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            if (e.getKey().startsWith("常见失败")) return e.getValue();
        }
        return null;
    }

    /** 取段落里第一个 fenced 代码块的内容。 */
    private String firstFenceContent(String block) {
        if (block == null) return null;
        Matcher m = FENCE.matcher(block);
        return m.find() ? m.group(1).strip() : null;
    }

    /**
     * 从「验收命令」代码块构造 verify 配置。
     *
     * <h4>三个刻意的保守选择</h4>
     * <ol>
     *   <li><strong>只取能安全执行的行</strong>：注释、{@code cd}、heredoc、管道等一律剔除，
     *       不尝试"智能"重组多行脚本——猜错的代价是在用户机器上执行了意料之外的命令。</li>
     *   <li><strong>cwd 从 {@code cd xxx} 推断</strong>：这是仓库里最常见的首行写法。</li>
     *   <li><strong>expect 只放退出码</strong>：从自然语言「通过标准」提取关键词很容易误判
     *       （中文标准里常含"不应出现""消失"等否定语），宁可只判退出码并如实标注
     *       {@code PARSED}，也不要给一个看起来精确实际不可靠的断言。</li>
     * </ol>
     */
    private String buildVerifyJson(String rawCmd, String lab, String passCriteria) {
        if (rawCmd == null || rawCmd.isBlank()) return null;

        String cwd = lab;
        List<String> candidates = new ArrayList<>();
        for (String raw : rawCmd.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // 首行 cd 用于推断工作目录，本身不执行
            if (line.startsWith("cd ")) {
                String d = line.substring(3).strip();
                if (!d.isEmpty() && !d.startsWith("/") && !d.contains("$")) {
                    cwd = d;
                }
                continue;
            }
            // 含 shell 特性的行不作为候选：解析器不做 shell 语义模拟
            if (line.contains("|") || line.contains(">") || line.contains("<")
                    || line.contains("&&") || line.contains("||") || line.contains(";")
                    || line.contains("$(") || line.contains("`") || line.contains("EOF")) {
                continue;
            }
            candidates.add(line);
        }
        if (candidates.isEmpty()) return null;

        Map<String, Object> v = new LinkedHashMap<>();
        // 取第一条候选：仓库惯例是把主验收命令放在最前（如 bash scripts/all.sh）
        v.put("cmd", candidates.get(0));
        if (cwd != null && !cwd.isBlank()) v.put("cwd", cwd);
        v.put("timeout", 600);
        v.put("expect", List.of(Map.of("kind", "exit_code", "value", 0)));
        if (candidates.size() > 1) {
            // 保留其余候选供用户在 UI 里手动选择，不自动串联执行
            v.put("alternatives", candidates.subList(1, Math.min(candidates.size(), 6)));
        }
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            log.debug("[Codex] verify JSON 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /** 从册头「对应动手项目」提取 lab 目录名。 */
    private String detectLab(String content) {
        Matcher m = Pattern.compile("对应动手项目\\*\\*\\s*[：:]\\s*\\[`([^`\\]]+)`]")
                .matcher(content);
        if (!m.find()) return null;
        String p = m.group(1).replace("../", "").replace("./", "");
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isBlank() ? null : p;
    }

    /** 资源标签归一：去反引号与括注。 */
    private String normalizeResource(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.replace("`", "").strip();
        int paren = t.indexOf('（');
        if (paren > 0) t = t.substring(0, paren).strip();
        paren = t.indexOf('(');
        if (paren > 0) t = t.substring(0, paren).strip();
        return switch (t) {
            case "本地" -> "local";
            case "本地+工具链" -> "local+toolchain";
            case "单卡GPU" -> "gpu1";
            case "多卡GPU" -> "gpuN";
            case "多机多卡" -> "multinode";
            default -> trimTo(t, 30);
        };
    }

    private BigDecimal parseHours(String s) {
        if (s == null || s.isBlank()) return null;
        Matcher m = HOURS.matcher(s);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1));
            } catch (Exception ignored) {
                // 数字形状异常时退回按关键词估算
            }
        }
        if (s.contains("半天")) return new BigDecimal("4");
        if (s.contains("一天") || s.contains("1 天") || s.contains("1天")) {
            return new BigDecimal("8");
        }
        return null;
    }

    /** 去掉行内 Markdown 标记，保留可读文本。 */
    private String cleanInline(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replace("`", "")
                .replace("**", "")
                .strip();
        return t.isBlank() ? null : t;
    }

    private String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isBlank()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
