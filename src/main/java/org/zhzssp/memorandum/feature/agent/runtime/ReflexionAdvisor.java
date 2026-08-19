package org.zhzssp.memorandum.feature.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 显式 Reflexion 顾问（方案 D）：把「工具失败」从<strong>被动回灌</strong>
 * 升级为<strong>带策略的主动干预</strong>。
 *
 * <h3>改造前的问题</h3>
 * <p>原实现只有隐式 Reflexion——把错误 JSON 原样喂回 LLM，指望它自己想明白。
 * 实际观察到两类典型退化：
 * <ul>
 *   <li><strong>原地打转</strong>：同一工具用同样的错参数反复调用，直到步数耗尽。
 *       模型没有「这条路已经试过了」的记忆，因为每步看到的都只是又一条错误。</li>
 *   <li><strong>误判可重试性</strong>：权限/白名单拒绝这类<strong>确定性失败</strong>
 *       被当成偶发错误反复重试，而参数错误这类<strong>本可修复</strong>的失败
 *       又被轻易放弃、改去乱试别的工具。</li>
 * </ul>
 *
 * <h3>两层设计</h3>
 * <ol>
 *   <li><strong>建议层</strong>：按失败模式注入差异化策略提示（可重试 / 换工具 / 别重试直接说明）。
 *       这一层是「劝」，依赖模型遵从。</li>
 *   <li><strong>执行层强制</strong>：同一工具在本轮内失败达到阈值后进入 <em>banned</em>，
 *       模型若无视提示再次调用，{@code AgentOrchestrator} 直接短路返回
 *       {@code TOOL_BANNED_THIS_TURN}，<strong>不真正执行</strong>。
 *       这才是「强制换工具」——只做第 1 层的话，模型完全可能继续撞墙。</li>
 * </ol>
 *
 * <h3>状态生命周期</h3>
 * <p>{@link TurnState} 是<strong>单轮（single user turn）作用域</strong>的，
 * 每次 {@code handleUserTurn} 新建。刻意不做跨轮持久化：
 * 用户下一轮可能已经补充了缺失信息，把上一轮的封禁带过去会误伤。
 *
 * <p>开关 {@code agent.chat.reflexion.enabled=false} 时完全旁路。
 */
@Component
public class ReflexionAdvisor {

    /** 被封禁工具再次被调用时返回的 error code。 */
    public static final String BANNED_ERROR = "TOOL_BANNED_THIS_TURN";

    /**
     * 失败模式分类。决定「该不该重试」以及「重试前要改什么」。
     */
    public enum FailureMode {
        /** 参数问题（缺失/类型错）——可修复，鼓励带正确参数重试 */
        INVALID_ARGUMENTS(true),
        /** 目标资源不存在（多为编造 id）——不该重试，应先检索拿真实 id */
        RESOURCE_NOT_FOUND(false),
        /** 权限/白名单/功能禁用——确定性失败，重试毫无意义 */
        DENIED(false),
        /** 用户拒绝确认——用户意志，禁止绕过 */
        USER_REJECTED(false),
        /** 调用了不存在的工具（工具幻觉）——重试同名必然再失败 */
        UNKNOWN_TOOL(false),
        /** 疑似偶发（超时/网络/上游 5xx）——可重试一次 */
        TRANSIENT(true),
        /** 工具存在但在当前 scope 不可见——策略性拒绝，重试毫无意义（方案 K） */
        TOOL_NOT_VISIBLE(false),
        /** 无法归类 */
        OTHER(true),
        /** 未失败 */
        NONE(true);

        private final boolean retryable;

        FailureMode(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final ObjectMapper om;

    @Value("${agent.chat.reflexion.enabled:true}")
    private boolean enabled;

    /** 同一工具在本轮内失败多少次后强制封禁。 */
    @Value("${agent.chat.reflexion.fail-threshold:2}")
    private int failThreshold;

    public ReflexionAdvisor(ObjectMapper om) {
        this.om = om;
    }

    public boolean enabled() {
        return enabled;
    }

    public int failThreshold() {
        return failThreshold;
    }

    /** 新建一轮的状态。禁用时返回一个惰性状态（所有查询恒为「无干预」）。 */
    public TurnState newTurn() {
        return new TurnState(enabled, Math.max(1, failThreshold));
    }

    /* ------------------------------------------------------------------ */
    /* 失败识别与分类                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * 从工具返回的 JSON 判断失败模式。
     *
     * <p>只做<strong>字符串/字段级</strong>的轻量识别：工具实现分散在多个模块，
     * 统一异常体系是更彻底的方案但改动面太大，而分类只要「够准到能选对策略」即可，
     * 误判的代价仅是给了一条次优提示，不影响正确性。
     */
    public FailureMode classify(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return FailureMode.NONE;
        JsonNode n;
        try {
            n = om.readTree(resultJson);
        } catch (Exception e) {
            return FailureMode.NONE;
        }
        if (!n.isObject()) return FailureMode.NONE;

        String code = n.path("error").asText("");
        String status = n.path("status").asText("");
        String message = n.path("message").asText("");
        JsonNode isErrNode = n.path("isError");
        boolean isErrFlag = isErrNode.isBoolean() && isErrNode.asBoolean();

        // 先判定「是否失败」，再判定「哪种失败」。
        // 三条失败信号：有 error 字段 / isError=true / status 本身表达失败语义。
        // 注意 {"status":"ok"} 这类成功响应也带 status，不能一见 status 就当失败。
        boolean statusFailure = isFailureStatus(status);
        if (code.isEmpty() && !isErrFlag && !statusFailure) {
            return FailureMode.NONE;
        }

        if ("USER_REJECTED".equalsIgnoreCase(status) || "USER_REJECTED".equalsIgnoreCase(code)) {
            return FailureMode.USER_REJECTED;
        }

        String hay = (code + " " + status + " " + message).toUpperCase(Locale.ROOT);

        if (hay.contains("UNKNOWN_TOOL")) {
            return FailureMode.UNKNOWN_TOOL;
        }

        // K：工具存在但在当前 scope 不可见。必须先于 DENIED 判定——它比 DENIED 更具体
        if (hay.contains("TOOL_NOT_VISIBLE")) {
            return FailureMode.TOOL_NOT_VISIBLE;
        }

        if (hay.contains("INVALID_ARGUMENTS") || hay.contains("ILLEGALARGUMENT")
                || hay.contains("缺少必填") || hay.contains("MISMATCHEDINPUT")
                || hay.contains("JSONPARSE") || hay.contains("NUMBERFORMAT")) {
            return FailureMode.INVALID_ARGUMENTS;
        }
        if (hay.contains("WRITE_DISABLED") || hay.contains("DENIED") || hay.contains("FORBIDDEN")
                || hay.contains("PERMISSION") || hay.contains("NOT_ALLOWED")
                || hay.contains("白名单") || hay.contains("越权") || hay.contains("无权")
                || hay.contains("已禁用") || hay.contains("不允许")) {
            return FailureMode.DENIED;
        }
        if (hay.contains("NOT_FOUND") || hay.contains("NOSUCHELEMENT")
                || hay.contains("ENTITYNOTFOUND") || hay.contains("EMPTYRESULTDATAACCESS")
                || hay.contains("不存在") || hay.contains("未找到") || hay.contains("找不到")) {
            return FailureMode.RESOURCE_NOT_FOUND;
        }
        if (hay.contains("TIMEOUT") || hay.contains("SOCKET") || hay.contains("CONNECT")
                || hay.contains("IOEXCEPTION") || hay.contains("UNAVAILABLE")
                || hay.contains("NOT_READY") || hay.contains("超时")) {
            return FailureMode.TRANSIENT;
        }
        return FailureMode.OTHER;
    }

    /**
     * {@code status} 字段是否表达失败语义。
     *
     * <p>工具约定并不统一：多数用 {@code error}，少数用 {@code status}
     * （如确认被拒的 {@code USER_REJECTED}、只读降级的 {@code WRITE_DISABLED}）。
     * 这里白名单式识别失败态，避免把 {@code {"status":"ok"}} 误判成失败。
     */
    private boolean isFailureStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase(Locale.ROOT);
        return s.contains("REJECT") || s.contains("DENIED") || s.contains("DISABLED")
                || s.contains("FORBIDDEN") || s.contains("ERROR") || s.contains("FAIL");
    }

    /* ------------------------------------------------------------------ */
    /* 策略生成                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 记录一次工具失败，并返回要追加到回灌内容里的策略提示。
     *
     * @return {@code null} 表示本次无需额外干预（保持原隐式 Reflexion 行为）
     */
    public String onFailure(TurnState st, String tool, FailureMode mode) {
        if (st == null || !st.active) return null;
        int n = st.fail(tool, mode);

        // 确定性失败：一次就够，立即封禁，不浪费第二步去证明它还是会失败
        if (!mode.retryable()) {
            st.ban(tool);
            return hintForNonRetryable(tool, mode);
        }
        if (n >= st.threshold) {
            st.ban(tool);
            return hintForExhausted(tool, mode, n);
        }
        return hintForRetryable(tool, mode, n);
    }

    /** 记录一次成功，清掉该工具的失败计数（说明模型已自修复）。 */
    public void onSuccess(TurnState st, String tool) {
        if (st == null || !st.active) return;
        st.clear(tool);
    }

    /** 被封禁工具再次被调用时返回的结果 JSON（不执行工具）。 */
    public Map<String, Object> bannedResult(TurnState st, String tool) {
        FailureMode mode = st.lastMode(tool);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", BANNED_ERROR);
        m.put("tool", tool);
        m.put("failures", st.failures(tool));
        m.put("reason", "该工具在本轮已失败 " + st.failures(tool) + " 次（"
                + describeMode(mode) + "），已被禁止在本轮继续调用。");
        m.put("hint", "禁止再次调用 " + tool + "。" + alternativeFor(mode)
                + "若确实无其它可行路径，请直接用自然语言向用户说明遇到的障碍，不要继续尝试工具。");
        return m;
    }

    private String hintForRetryable(String tool, FailureMode mode, int n) {
        String base = "⚠️ 工具 " + tool + " 第 " + n + " 次失败（" + describeMode(mode) + "）。";
        return switch (mode) {
            case INVALID_ARGUMENTS -> base
                    + "这是参数问题，工具本身可用：请对照错误里的 expectedParams 修正参数后重试一次。"
                    + "注意不要编造参数值——需要真实 id 时先用查询工具获取。"
                    + "再失败一次将禁止调用该工具。";
            case TRANSIENT -> base
                    + "疑似偶发故障，允许原样重试一次；若仍失败请改用其它路径。";
            default -> base
                    + "请先判断是「参数需要修正」还是「这条路本身不通」："
                    + "前者修正后重试，后者立即换工具或换思路。再失败一次将禁止调用该工具。";
        };
    }

    private String hintForNonRetryable(String tool, FailureMode mode) {
        return switch (mode) {
            case DENIED -> "⛔ 工具 " + tool + " 被策略/权限拒绝，这是确定性结果，"
                    + "重试一定会得到同样结果。禁止再次调用该工具。"
                    + "请直接用自然语言向用户说明该操作当前不可用及原因，不要尝试绕过。";
            case USER_REJECTED -> "⛔ 用户拒绝了 " + tool + " 的执行授权。"
                    + "这是用户的明确意愿，禁止以任何方式重试或绕过（包括换用等效工具）。"
                    + "请询问用户是想调整参数还是取消该操作。";
            case RESOURCE_NOT_FOUND -> "⛔ 工具 " + tool + " 操作的目标不存在，"
                    + "多数情况是 id 被编造或已过期。禁止用同一 id 重试。"
                    + "请先调用查询/检索类工具拿到真实存在的 id，再执行操作；"
                    + "若确认目标不存在，请如实告知用户。";
            case UNKNOWN_TOOL -> "⛔ 工具 " + tool + " 不存在（你可能记错或自行拼造了工具名）。"
                    + "禁止再次调用该名称。请严格从系统提示【可用工具】列表里"
                    + "逐字复制工具名，注意前缀（本地工具无 mcp. 前缀，MCP 工具必须带 mcp.<server>. 前缀）；"
                    + "若列表中没有能完成该目标的工具，请直接告知用户此能力不可用。";
            case TOOL_NOT_VISIBLE -> "⛔ 工具 " + tool + " 在当前模式/角色下不可见，这是策略边界，"
                    + "换参数或重试都不会通过。禁止再次调用该工具，也不要尝试用其它写工具绕过。"
                    + "请在【可用工具】列表范围内重新规划，或用自然语言说明当前模式不支持该操作。";
            default -> "⛔ 工具 " + tool + " 失败且不可重试，请改用其它路径。";
        };
    }

    private String hintForExhausted(String tool, FailureMode mode, int n) {
        return "⛔ 工具 " + tool + " 已连续失败 " + n + " 次（" + describeMode(mode) + "），"
                + "已达失败上限，禁止在本轮再次调用它。"
                + alternativeFor(mode)
                + "若无其它可行路径，请直接用自然语言向用户说明遇到的问题，"
                + "不要继续空转——继续调用只会消耗推理步数而不会成功。";
    }

    /** 按失败模式给出「换什么」的具体方向，而不是空喊「换个工具」。 */
    private String alternativeFor(FailureMode mode) {
        return switch (mode) {
            case INVALID_ARGUMENTS ->
                    "建议：确认所需参数是否真的能从当前上下文得到；若信息不足，直接向用户追问缺失信息。";
            case RESOURCE_NOT_FOUND ->
                    "建议：先用检索/列表类工具（如 kb.semantic_search、task.list）取得真实存在的对象及其 id。";
            case DENIED ->
                    "建议：改用只读方式达成目标，或向用户说明该能力当前不可用。";
            case USER_REJECTED ->
                    "建议：与用户确认意图后再决定，不要自行重试。";
            case UNKNOWN_TOOL ->
                    "建议：回到【可用工具】列表逐字核对工具名与前缀，或改用列表中已有的等效工具。";
            case TRANSIENT ->
                    "建议：改用不依赖该外部服务的替代路径，或告知用户稍后重试。";
            case TOOL_NOT_VISIBLE ->
                    "建议：回到【可用工具】列表，只调用当前模式可见的工具；若确需写操作，请告知用户切换模式。";
            default ->
                    "建议：换一个能达成同一目标的工具，或改变解题思路。";
        };
    }

    private String describeMode(FailureMode mode) {
        return switch (mode) {
            case INVALID_ARGUMENTS -> "参数错误";
            case RESOURCE_NOT_FOUND -> "目标资源不存在";
            case DENIED -> "权限/策略拒绝";
            case USER_REJECTED -> "用户拒绝授权";
            case UNKNOWN_TOOL -> "工具不存在";
            case TRANSIENT -> "疑似偶发故障";
            case TOOL_NOT_VISIBLE -> "工具当前不可见";
            case OTHER -> "执行失败";
            case NONE -> "无失败";
        };
    }

    /* ------------------------------------------------------------------ */

    /**
     * 单轮作用域的 Reflexion 状态。非线程安全——一轮 ReAct 在单线程内串行推进。
     *
     * <p>{@code active=false}（开关关闭）时所有写操作空转、所有查询返回「无干预」，
     * 使 {@code AgentOrchestrator} 无需到处判断开关。
     */
    public static final class TurnState {

        private final boolean active;
        private final int threshold;
        private final Map<String, Integer> failures = new HashMap<>();
        private final Map<String, FailureMode> lastMode = new HashMap<>();
        private final Set<String> banned = new HashSet<>();
        /** 已发生过失败、正处于「等待自修复」状态的工具 */
        private final Set<String> pendingRepair = new HashSet<>();

        private TurnState(boolean active, int threshold) {
            this.active = active;
            this.threshold = threshold;
        }

        int fail(String tool, FailureMode mode) {
            lastMode.put(tool, mode);
            pendingRepair.add(tool);
            return failures.merge(tool, 1, Integer::sum);
        }

        void clear(String tool) {
            failures.remove(tool);
            pendingRepair.remove(tool);
        }

        void ban(String tool) {
            banned.add(tool);
        }

        public boolean isBanned(String tool) {
            return active && banned.contains(tool);
        }

        /**
         * 该工具此前是否已失败过——即本次调用是一次「自修复尝试」。
         * 用于统计自修复成功率（方案 E 的效果只能靠这个数字证明）。
         */
        public boolean isRepairAttempt(String tool) {
            return active && pendingRepair.contains(tool);
        }

        public int failures(String tool) {
            return failures.getOrDefault(tool, 0);
        }

        public FailureMode lastMode(String tool) {
            return lastMode.getOrDefault(tool, FailureMode.OTHER);
        }
    }
}
