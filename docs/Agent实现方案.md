# Lattice-Planner 内置 Agent 实现方案

> 版本：v3（基于 v2 + 全量源码核对的精修版）  
> 核对范围：所有 Service 真实方法签名、实体真实字段、事件类、Security 配置、模板结构、Electron preload 现状、`application.properties` 现状。  
> 设计原则：**复用 > 新建**、**渐进式 > 推翻**、**够用 > 大而全**、**所有代码骨架可直接复制到工程编译**。

---

## 0. 版本变更说明（v2 → v3）

v2 的整体架构判断（原地扩展 `feature/agent/`、自研 ReAct、Electron IPC 桥代替 MCP 子进程、复用 `LlmGateway` / 事件系统）**已被源码核对确认全部成立**。但 v2 在以下事实细节上与真实代码有偏差，v3 全部修正：

| v2 中的写法 | 真实情况 | 影响 |
|---|---|---|
| `taskService.createForCurrentUser(...)` | 真实是 `saveTask(Task task, User user)`，调用方负责 `new Task()` + setter | 工具代码必须重写 |
| `taskService.findByStatus(...)` | 不存在；只有 `searchTasks` / `getTodayActionableTasks` / `findFuzzyTasksNeedingSplit` | 工具映射调整 |
| `TaskStatus.COMPLETED` | 实际枚举值是 `PENDING / DONE / ARCHIVED / SHELVED` | 入参规约 |
| `NoteService` 已存在 | **不存在**，逻辑全在 `NoteController` | 必须新增 `NoteService` |
| WebSocket 已经启用 | 仅引依赖，**没有任何 `@EnableWebSocket` / Handler** | 需要从零写 Config + Handler |
| Security 默认放过 `/ws/**` | `anyRequest().authenticated()`，CSRF 默认开启 | 必须显式给 WS 加 CSRF 豁免 |
| `agent.llm.model=deepseek-chat` | 实配是 `deepseek-reasoner` | Reasoner 输出含 `<think>...</think>`，**ToolCallParser 必须先剥离** |
| 模板有 layout 共用 | 6 个页面完全独立无 fragment | 注入面板需要逐页 include |
| `Link.LinkSourceType` 含 GOAL | 真实只有 `TASK / NOTE`；目标方向必须是 `source=TASK, target=GOAL` | 链接代码方向规约 |
| 删除/搁置任务发事件 | **不发事件**（直接走 Repository） | V1 不开放删除工具 |

> v3 把所有代码骨架对齐到上表的真实情况，每段代码都可直接复制到工程里通过编译。

---

## 1. 与现有源码的对接清单（事实基线）

### 1.1 必须复用的现有构件

| 构件 | 完整路径 | 在 Agent 中的角色 |
|---|---|---|
| LLM 网关 | `feature/agent/service/LlmGateway.generateText(String)` | **新增 `generateChat(List<Map<String,String>>)` 重载** |
| 单次目标拆解 | `feature/agent/service/PlannerAgentService.draftPlan(GoalPlanRequest)` | 包装为工具 `planner.draft_goal_plan` |
| 落库 Goal+Task | `feature/agent/service/AgentPlanApplyService.apply(User, GoalPlanResponse)` | 包装为工具 `planner.apply_goal_plan` |
| 任务保存（带事件） | `core/service/TaskService.saveTask(Task, User)` 发 `TaskCreatedEvent` | `task.create` 内部调用 |
| 任务搜索 | `TaskService.searchTasks(Long userId, String keyword, LocalDateTime, LocalDateTime)` | `task.search` |
| 任务完成 | `TaskService.completeTask(Task, User)` 发 `TaskCompletedEvent` | `task.complete` |
| 任务归档 | `TaskService.archiveTask(Task, User)` 发 `TaskArchivedEvent` | `task.archive` |
| 今日可行动 | `TaskService.getTodayActionableTasks(User)` | `task.today` |
| 模糊任务 | `TaskService.findFuzzyTasksNeedingSplit(User, long days)` | `task.fuzzy_pending` |
| 目标列表 | `GoalService.findActiveGoalsByUser(User)` / `findGoalsByUser(User)` | `goal.list` |
| 目标-任务关联 | `GoalService.linkTaskToGoals(Long taskId, List<Long>, User)` | `goal.link_task` |
| 目标归档 | `GoalService.archive(Long goalId, User)` | `goal.archive`（**requiresConfirm**） |
| 得分计算 | `feature/insight/service/InsightScoreService` | `insight.daily_scores` |
| 周期复盘 | `feature/insight/service/AiSummaryService.summarizeScores(...)` | `insight.summarize_period` |
| 事件→联动 | `feature/goal/listener/GoalEventListener` 监听 Task 三大事件 | **零代码自动联动**：Agent 写 task → 事件 → 自动重算目标进度 |
| LLM 配置 | `application.properties` 中 `agent.llm.api-key/model/base-url` | 直接复用 |

### 1.2 必须新建/修改的构件

```
新增：
  feature/agent/chat/{AgentChatController, AgentChatWebSocketHandler}.java
  feature/agent/runtime/{AgentOrchestrator, PromptBuilder, ToolCallParser,
                         ConversationMemory, AgentContext, LongTermMemoryService}.java
  feature/agent/tool/{AgentTool, ToolParam, ToolDefinition, ToolRegistry,
                      LocalBridgeProxy}.java
  feature/agent/tool/impl/{TaskTools, GoalTools, NoteTools, InsightTools,
                           PlannerTools, LocalDocTools}.java
  feature/agent/policy/ToolConfirmCoordinator.java
  core/service/NoteService.java                     ← 抽离 NoteController 笔记逻辑
  config/AgentWebSocketConfig.java
  resources/static/agent/{chat-panel.html, .css, .js}
  resources/templates/fragments/agent-panel.html
  electron-app/permission-config.json

修改：
  feature/agent/service/LlmGateway.java   ← 增 generateChat 方法 + chatModelOverride 字段
  controller/NoteController.java          ← 改用 NoteService
  config/WebSecurityConfig.java           ← 放行 /ws/agent/**、CSRF 豁免
  entity/NoteType.java                    ← 追加 AGENT_MEMO 枚举值
  application.properties                  ← 增 agent.chat.* 配置
  electron-app/main.js                    ← 新增 ipcMain.handle('local:*')
  electron-app/preload.js                 ← 暴露 window.lattice.localBridge
  templates/{addMemo,dashboard,preferenceSettings,selectFeatures}.html
                                          ← body 末尾注入 fragment
```

---

## 2. 总体架构

```
┌────────────── Electron 客户端（electron-app/） ──────────────────┐
│ 渲染进程：现有 Thymeleaf 页面 + 新增 chat-panel fragment         │
│   │ WebSocket  /ws/agent/{sessionId}                             │
│   │ HTTP       /api/agent/chat/history                           │
│   ▼                                                              │
│ preload.js: window.lattice.localBridge.{readFile,listDir,readPdf}│
│ main.js:    ipcMain.handle('local:*') + 黑白名单 + 弹窗           │
└──────────────────────────────────────────────────────────────────┘
                       ▲ WebSocket 双向（msgType 协议）
                       │
┌────────────────────── Spring Boot 后端 ───────────────────────────┐
│ feature/agent/                                                    │
│   chat/  AgentChatController / AgentChatWebSocketHandler          │
│   runtime/                                                        │
│     AgentOrchestrator        ★ ReAct 主循环                      │
│     PromptBuilder            系统 Prompt + 工具描述拼接           │
│     ToolCallParser           剥离 <think> + 抽 JSON              │
│     ConversationMemory       内存 ConcurrentMap                   │
│     LongTermMemoryService    落 Note(AGENT_MEMO)                  │
│     AgentContext             ThreadLocal<User, sessionId>         │
│   tool/                                                           │
│     AgentTool / ToolParam (注解)                                  │
│     ToolDefinition / ToolRegistry                                 │
│     LocalBridgeProxy         本地工具 → WS 反向下发              │
│     impl/{TaskTools, GoalTools, NoteTools, InsightTools,          │
│           PlannerTools, LocalDocTools}                            │
│   policy/ToolConfirmCoordinator   异步 future 桥接弹窗结果       │
│                                                                   │
│ ─── 完全不动 ───                                                  │
│ core/service/TaskService（发布 3 大事件）                         │
│ feature/goal/listener/GoalEventListener（自动联动）              │
│ feature/insight/service/{InsightScoreService, AiSummaryService}   │
│ feature/agent/service/{LlmGateway, PlannerAgentService,           │
│                        AgentPlanApplyService}                     │
└───────────────────────────────────────────────────────────────────┘
                              ▼
                   MySQL（已有 task/goal/link/note/user）
```

---

## 3. 模块详细设计与代码骨架

### 3.1 工具系统

#### 3.1.1 注解定义

```java
// feature/agent/tool/AgentTool.java
package org.zhzssp.memorandum.feature.agent.tool;
import java.lang.annotation.*;

@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
    String name();
    String description();
    boolean requiresConfirm() default false;
    String[] tags() default {};
}
```

```java
// feature/agent/tool/ToolParam.java
package org.zhzssp.memorandum.feature.agent.tool;
import java.lang.annotation.*;

@Target(ElementType.PARAMETER) @Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {
    String value();
    String desc();
    boolean required() default false;
}
```

#### 3.1.2 ToolDefinition

```java
// feature/agent/tool/ToolDefinition.java
package org.zhzssp.memorandum.feature.agent.tool;
import java.lang.reflect.Method;
import java.util.List;

public record ToolDefinition(
        String name, String description, boolean requiresConfirm,
        List<String> tags, Object bean, Method method, List<ParamDef> params) {
    public record ParamDef(String name, String desc, boolean required, Class<?> javaType) {}
}
```

#### 3.1.3 ToolRegistry（自动扫描 + 反射调用）

```java
// feature/agent/tool/ToolRegistry.java
package org.zhzssp.memorandum.feature.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {
    private final ApplicationContext ctx;
    private final ObjectMapper om;
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    public ToolRegistry(ApplicationContext ctx, ObjectMapper om) {
        this.ctx = ctx; this.om = om;
    }

    @PostConstruct
    public void scan() {
        for (Object bean : ctx.getBeansWithAnnotation(org.springframework.stereotype.Component.class).values()) {
            Class<?> userClass = AopUtils.getTargetClass(bean);
            for (Method m : userClass.getDeclaredMethods()) {
                AgentTool ann = m.getAnnotation(AgentTool.class);
                if (ann == null) continue;
                List<ToolDefinition.ParamDef> params = new ArrayList<>();
                for (Parameter p : m.getParameters()) {
                    ToolParam pa = p.getAnnotation(ToolParam.class);
                    if (pa == null)
                        throw new IllegalStateException("@AgentTool 方法 " + m + " 的参数缺少 @ToolParam");
                    params.add(new ToolDefinition.ParamDef(pa.value(), pa.desc(), pa.required(), p.getType()));
                }
                tools.put(ann.name(), new ToolDefinition(
                        ann.name(), ann.description(), ann.requiresConfirm(),
                        List.of(ann.tags()), bean, m, params));
            }
        }
    }

    public Collection<ToolDefinition> all() { return tools.values(); }
    public ToolDefinition get(String name) { return tools.get(name); }

    public List<Map<String, Object>> exportSchemas(Set<String> tagFilter) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolDefinition t : tools.values()) {
            if (tagFilter != null && !tagFilter.isEmpty()
                    && t.tags().stream().noneMatch(tagFilter::contains)) continue;
            ObjectNode props = om.createObjectNode();
            List<String> required = new ArrayList<>();
            for (ToolDefinition.ParamDef p : t.params()) {
                ObjectNode pn = om.createObjectNode();
                pn.put("type", jsonType(p.javaType()));
                pn.put("description", p.desc());
                props.set(p.name(), pn);
                if (p.required()) required.add(p.name());
            }
            ObjectNode parameters = om.createObjectNode();
            parameters.put("type", "object");
            parameters.set("properties", props);
            parameters.set("required", om.valueToTree(required));
            list.add(Map.of("name", t.name(),
                    "description", t.description() + (t.requiresConfirm() ? "（需用户确认）" : ""),
                    "parameters", parameters));
        }
        return list;
    }

    public Object invoke(String name, JsonNode args) throws Exception {
        ToolDefinition t = tools.get(name);
        if (t == null) throw new IllegalArgumentException("未知工具：" + name);
        Object[] real = new Object[t.params().size()];
        for (int i = 0; i < t.params().size(); i++) {
            ToolDefinition.ParamDef p = t.params().get(i);
            JsonNode v = args == null ? null : args.get(p.name());
            if (v == null || v.isNull()) {
                if (p.required()) throw new IllegalArgumentException("缺少必填参数：" + p.name());
                real[i] = null;
            } else {
                real[i] = om.treeToValue(v, p.javaType());
            }
        }
        return t.method().invoke(t.bean(), real);
    }

    private String jsonType(Class<?> c) {
        if (c == String.class) return "string";
        if (c == Integer.class || c == int.class || c == Long.class || c == long.class) return "integer";
        if (c == Double.class || c == double.class || c == Float.class || c == float.class) return "number";
        if (c == Boolean.class || c == boolean.class) return "boolean";
        if (Collection.class.isAssignableFrom(c) || c.isArray()) return "array";
        return "object";
    }
}
```

#### 3.1.4 AgentContext（线程级当前用户）

```java
// feature/agent/runtime/AgentContext.java
package org.zhzssp.memorandum.feature.agent.runtime;
import org.zhzssp.memorandum.entity.User;

public final class AgentContext {
    private static final ThreadLocal<User> USER = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();
    public static void set(User u, String sid) { USER.set(u); SESSION.set(sid); }
    public static void clear() { USER.remove(); SESSION.remove(); }
    public static User requireUser() {
        User u = USER.get();
        if (u == null) throw new IllegalStateException("AgentContext.user 未初始化");
        return u;
    }
    public static String sessionId() { return SESSION.get(); }
}
```

#### 3.1.5 真实工具样例：`TaskTools`

```java
// feature/agent/tool/impl/TaskTools.java
package org.zhzssp.memorandum.feature.agent.tool.impl;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.core.service.TaskService;
import org.zhzssp.memorandum.entity.*;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.repository.TaskRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class TaskTools {
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    public TaskTools(TaskService s, TaskRepository r) { this.taskService = s; this.taskRepository = r; }

    @AgentTool(name = "task.create", tags = {"task","write"},
               description = "创建一个待办任务（PENDING）。deadline 可空；energy 可填 LOW/MEDIUM/HIGH。")
    public TaskView create(
        @ToolParam(value = "title", desc = "任务标题", required = true) String title,
        @ToolParam(value = "description", desc = "任务备注，可空") String description,
        @ToolParam(value = "deadline", desc = "yyyy-MM-dd 截止日期，可空") String deadline,
        @ToolParam(value = "energy", desc = "精力 LOW/MEDIUM/HIGH，可空") String energy,
        @ToolParam(value = "preferredSlot", desc = "MORNING/AFTERNOON/EVENING，可空") String slot
    ) {
        User user = AgentContext.requireUser();
        Task t = new Task();
        t.setTitle(title);
        t.setDescription(description);
        t.setStatus(TaskStatus.PENDING);
        if (deadline != null && !deadline.isBlank())
            t.setDeadline(LocalDate.parse(deadline).atStartOfDay());
        if (energy != null && !energy.isBlank()) t.setEnergyRequirement(EnergyLevel.valueOf(energy));
        if (slot != null && !slot.isBlank()) t.setPreferredSlot(TimeSlot.valueOf(slot));
        return TaskView.of(taskService.saveTask(t, user));   // ★ 触发 TaskCreatedEvent
    }

    @AgentTool(name = "task.search", tags = {"task","read"},
               description = "按关键字 + 截止日期范围查询当前用户任务")
    public List<TaskView> search(
        @ToolParam(value = "keyword", desc = "标题关键字，可空") String keyword,
        @ToolParam(value = "from", desc = "yyyy-MM-dd 起，可空") String from,
        @ToolParam(value = "to",   desc = "yyyy-MM-dd 止，可空") String to
    ) {
        User user = AgentContext.requireUser();
        LocalDateTime s = (from == null || from.isBlank()) ? null : LocalDate.parse(from).atStartOfDay();
        LocalDateTime e = (to   == null || to.isBlank())   ? null : LocalDate.parse(to).atTime(23,59,59);
        return taskService.searchTasks(user.getId(), keyword, s, e).stream().map(TaskView::of).toList();
    }

    @AgentTool(name = "task.today", tags = {"task","read"},
               description = "今日可行动任务（PENDING 且 deadline 是今天或未设置）")
    public List<TaskView> today() {
        return taskService.getTodayActionableTasks(AgentContext.requireUser())
                .stream().map(TaskView::of).toList();
    }

    @AgentTool(name = "task.complete", tags = {"task","write"}, requiresConfirm = true,
               description = "把指定 id 的任务标记为完成（DONE）")
    public TaskView complete(@ToolParam(value = "id", desc = "任务 id", required = true) Long id) {
        User user = AgentContext.requireUser();
        Task t = taskRepository.findById(id).orElseThrow();
        if (!t.getUser().getId().equals(user.getId())) throw new SecurityException("非当前用户任务");
        return TaskView.of(taskService.completeTask(t, user));
    }

    @AgentTool(name = "task.archive", tags = {"task","write"}, requiresConfirm = true,
               description = "归档指定 id 的任务（ARCHIVED）")
    public TaskView archive(@ToolParam(value = "id", desc = "任务 id", required = true) Long id) {
        User user = AgentContext.requireUser();
        Task t = taskRepository.findById(id).orElseThrow();
        if (!t.getUser().getId().equals(user.getId())) throw new SecurityException("非当前用户任务");
        return TaskView.of(taskService.archiveTask(t, user));
    }

    /** 给 LLM 序列化用的瘦视图，避免回传整个 User */
    public record TaskView(Long id, String title, String description, String status,
                           String deadline, String energy, String slot) {
        static TaskView of(Task t) {
            return new TaskView(t.getId(), t.getTitle(), t.getDescription(),
                    t.getEffectiveStatus().name(),
                    t.getDeadline() == null ? null : t.getDeadline().toLocalDate().toString(),
                    t.getEnergyRequirement() == null ? null : t.getEnergyRequirement().name(),
                    t.getPreferredSlot() == null ? null : t.getPreferredSlot().name());
        }
    }
}
```

#### 3.1.6 其余工具的实现规约（按 TaskTools 模式照搬）

| 工具 | 内部调用 | 备注 |
|---|---|---|
| `goal.list` | `goalService.findActiveGoalsByUser(user)` | 只返回 id/name/goalType/progress |
| `goal.create` | `new Goal()` + `setUser/setName/setGoalType` + `goalService.save(g)` | requiresConfirm |
| `goal.archive` | `goalService.archive(goalId, user)` | requiresConfirm |
| `goal.link_task` | `goalService.linkTaskToGoals(taskId, goalIds, user)` | requiresConfirm；source=TASK target=GOAL 已由 service 保证 |
| `note.list` / `note.create` | 调新建 `NoteService`（见 §3.5） | `note.create` requiresConfirm=false（笔记是低风险） |
| `insight.daily_scores` | `insightScoreService` 现有方法（按真实签名调） | 返回 DailyScore 列表 |
| `insight.summarize_period` | `aiSummaryService.summarizeScores(start, end, scores)` | 内部已有 LLM + 本地兜底 |
| `planner.draft_goal_plan` | `plannerAgentService.draftPlan(new GoalPlanRequest(...))` | requiresConfirm=false（只读规划） |
| `planner.apply_goal_plan` | `agentPlanApplyService.apply(user, GoalPlanResponse)` | **强制 requiresConfirm=true** |

> 工具数量 V1 控制在 **15 个左右**，足够覆盖 80% 需求；多了会让 LLM 选择困难。

---

### 3.2 ReAct 主循环

#### 3.2.1 LlmGateway 增量

> 在 `feature/agent/service/LlmGateway.java` 顶部新增字段，并新增 `generateChat` 方法（不动现有 `generateText`）。

```java
@Value("${agent.chat.model:deepseek-chat}")
private String chatModelOverride;

public String generateChat(java.util.List<java.util.Map<String,String>> messages) {
    String apiKey = resolveApiKey();
    if (apiKey == null || apiKey.isBlank())
        throw new IllegalStateException("agent.llm.api-key 未配置");
    try {
        String endpoint = normalizeBaseUrl(baseUrl) + "/v1/chat/completions";
        // ★ chat 模式默认强制 deepseek-chat，避免 reasoner 推理段干扰 JSON 解析
        String chatModel = (chatModelOverride == null || chatModelOverride.isBlank())
                ? "deepseek-chat" : chatModelOverride;
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "model", chatModel,
                "temperature", 0.2,
                "messages", messages));
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        var resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new IllegalStateException("LLM HTTP " + resp.statusCode() + " - " + resp.body());
        var node = objectMapper.readTree(resp.body())
                .path("choices").path(0).path("message").path("content");
        return node.isMissingNode() ? "" : node.asText("").trim();
    } catch (java.io.IOException | InterruptedException ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        throw new IllegalStateException("调用 LLM 失败", ex);
    }
}
```

#### 3.2.2 ToolCallParser（剥离 reasoner 思考链）

```java
// feature/agent/runtime/ToolCallParser.java
package org.zhzssp.memorandum.feature.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ToolCallParser {
    private static final Pattern THINK = Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper om;
    public ToolCallParser(ObjectMapper om) { this.om = om; }

    public record ToolCall(String name, JsonNode arguments) {}

    public ToolCall parse(String raw) {
        if (raw == null) return null;
        String s = THINK.matcher(raw).replaceAll("").trim();
        Matcher fm = FENCE.matcher(s);
        String candidate = fm.find() ? fm.group(1).trim() : s;
        int start = candidate.indexOf('{'), end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = om.readTree(candidate.substring(start, end + 1));
            if (!node.has("tool")) return null;
            return new ToolCall(node.get("tool").asText(), node.path("arguments"));
        } catch (Exception ex) { return null; }
    }

    public String stripThinking(String raw) {
        return raw == null ? "" : THINK.matcher(raw).replaceAll("").trim();
    }
}
```

#### 3.2.3 ConversationMemory & PromptBuilder

```java
// feature/agent/runtime/ConversationMemory.java
package org.zhzssp.memorandum.feature.agent.runtime;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversationMemory {
    public record Msg(String role, String content) {}
    private final Map<String, Deque<Msg>> store = new ConcurrentHashMap<>();
    private static final int WINDOW = 30;

    public List<Msg> history(String sid) {
        return new ArrayList<>(store.getOrDefault(sid, new ArrayDeque<>()));
    }
    public void append(String sid, String role, String content) {
        Deque<Msg> q = store.computeIfAbsent(sid, k -> new ArrayDeque<>());
        q.addLast(new Msg(role, content));
        while (q.size() > WINDOW) q.pollFirst();
    }
    public void clear(String sid) { store.remove(sid); }
}
```

```java
// feature/agent/runtime/PromptBuilder.java
package org.zhzssp.memorandum.feature.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;

import java.time.LocalDate;
import java.util.*;

@Component
public class PromptBuilder {
    private final ToolRegistry registry;
    private final ObjectMapper om;
    public PromptBuilder(ToolRegistry r, ObjectMapper o) { this.registry = r; this.om = o; }

    public List<Map<String,String>> build(String mode, List<ConversationMemory.Msg> history,
                                          String longTermMemo) throws Exception {
        Set<String> tagFilter = switch (mode == null ? "chat" : mode) {
            case "plan"    -> Set.of("task","goal","planner","read","write");
            case "reflect" -> Set.of("task","goal","insight","note","read");
            default        -> null;   // chat：全部工具
        };
        String toolsJson = om.writerWithDefaultPrettyPrinter()
                .writeValueAsString(registry.exportSchemas(tagFilter));
        String sys = """
你是 Lattice-Planner 内置的规划助手 Lattice-Agent。今天是 %s。
你与用户协作管理目标 / 任务 / 笔记 / 复盘，并可读取用户本地文档。

【可用工具】（必须使用工具完成读写，不要编造数据）
%s

【输出协议】（严格遵守）
- 如需调用工具：仅输出一个 JSON 对象，形如 {"tool":"task.search","arguments":{"keyword":"周报"}}。
  不要解释，不要 Markdown 围栏。
- 如果已能给出最终答复：直接输出自然语言中文，不要再输出 JSON。
- 一次只能调用一个工具；看到工具结果后再决定下一步。
- 标 "需用户确认" 的工具会触发弹窗，请只在用户明确意图后调用。

【用户长期记忆】
%s
""".formatted(LocalDate.now(), toolsJson,
              (longTermMemo == null || longTermMemo.isBlank()) ? "(暂无)" : longTermMemo);

        List<Map<String,String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role","system","content", sys));
        for (var m : history) msgs.add(Map.of("role", m.role(), "content", m.content()));
        return msgs;
    }
}
```

#### 3.2.4 AgentOrchestrator（核心循环）

```java
// feature/agent/runtime/AgentOrchestrator.java
package org.zhzssp.memorandum.feature.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;
import org.zhzssp.memorandum.feature.agent.policy.ToolConfirmCoordinator;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;

import java.util.Map;
import java.util.UUID;

@Component
public class AgentOrchestrator {
    private final LlmGateway llm;
    private final ToolRegistry registry;
    private final ToolCallParser parser;
    private final PromptBuilder promptBuilder;
    private final ConversationMemory memory;
    private final ToolConfirmCoordinator confirmCoordinator;
    private final AgentChatWebSocketHandler ws;
    private final ObjectMapper om;

    @Value("${agent.chat.max-steps:8}") private int maxSteps;

    public AgentOrchestrator(LlmGateway llm, ToolRegistry r, ToolCallParser p, PromptBuilder pb,
                             ConversationMemory m, ToolConfirmCoordinator c,
                             @Lazy AgentChatWebSocketHandler ws, ObjectMapper om) {
        this.llm = llm; this.registry = r; this.parser = p; this.promptBuilder = pb;
        this.memory = m; this.confirmCoordinator = c; this.ws = ws; this.om = om;
    }

    public void handleUserTurn(String sid, String userInput, String mode, String longTermMemo) throws Exception {
        memory.append(sid, "user", userInput);

        for (int step = 0; step < maxSteps; step++) {
            var msgs = promptBuilder.build(mode, memory.history(sid), longTermMemo);
            String llmRaw = llm.generateChat(msgs);
            ToolCallParser.ToolCall call = parser.parse(llmRaw);

            if (call == null) {  // 终态：自然语言回答
                String finalAnswer = parser.stripThinking(llmRaw);
                memory.append(sid, "assistant", finalAnswer);
                ws.sendAssistant(sid, finalAnswer);
                ws.sendDone(sid);
                return;
            }

            ToolDefinition def = registry.get(call.name());
            String callId = UUID.randomUUID().toString();
            ws.sendToolStart(sid, callId, call.name(), call.arguments());

            // 高危工具：等用户在 UI 点"允许 / 拒绝"
            if (def != null && def.requiresConfirm()) {
                boolean ok = confirmCoordinator.askUser(sid, callId, call.name(), call.arguments()).get();
                if (!ok) {
                    ws.sendToolResult(sid, callId, "USER_REJECTED");
                    memory.append(sid, "assistant",
                            om.writeValueAsString(Map.of("tool", call.name(), "arguments", call.arguments())));
                    memory.append(sid, "user",
                            "工具被用户拒绝执行：" + call.name() + "，请改用其他方式或终止。");
                    continue;
                }
            }

            String resultJson;
            try {
                Object result = registry.invoke(call.name(), call.arguments());
                resultJson = result == null ? "null" : om.writeValueAsString(result);
            } catch (Throwable t) {
                resultJson = om.writeValueAsString(Map.of(
                        "error", t.getClass().getSimpleName(),
                        "message", String.valueOf(t.getMessage())));
            }
            ws.sendToolResult(sid, callId, resultJson);
            memory.append(sid, "assistant",
                    om.writeValueAsString(Map.of("tool", call.name(), "arguments", call.arguments())));
            memory.append(sid, "user",
                    "[tool_result " + call.name() + "]\n" + truncate(resultJson, 4000));
        }
        ws.sendAssistant(sid, "（已达最大推理步数，已停止。请换种说法或拆分为更小的步骤。）");
        ws.sendDone(sid);
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...[truncated]");
    }
}
```

> **设计要点**：①工具失败不抛给前端，把错误 JSON 喂回 LLM = 朴素 Reflexion；②工具调用 + 结果交错塞进 user/assistant 角色，避免 DeepSeek 校验拒绝非标准 role。

---

### 3.3 WebSocket + Security 整合（v2 漏掉的硬骨头）

#### 3.3.1 启用 WebSocket

```java
// config/AgentWebSocketConfig.java
package org.zhzssp.memorandum.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;

@Configuration @EnableWebSocket
public class AgentWebSocketConfig implements WebSocketConfigurer {
    private final AgentChatWebSocketHandler handler;
    public AgentWebSocketConfig(AgentChatWebSocketHandler h) { this.handler = h; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/agent/{sessionId}")
                .setAllowedOriginPatterns("*");   // 兼容 Electron file:// 与 http://localhost
    }
}
```

#### 3.3.2 SecurityConfig 修改（diff）

```java
// config/WebSecurityConfig.java —— filterChain 内修改
http
    .csrf(csrf -> csrf
        .ignoringRequestMatchers("/user-logged-in", "/due-dates",
                                 "/ws/agent/**", "/api/agent/**"))   // ← 追加
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/", "/register", "/login", "/css/**", "/js/**", "/user-logged-in").permitAll()
        // /ws/agent/** 需要登录 → 靠 JSESSIONID 携带 Principal
        .anyRequest().authenticated())
    .formLogin(login -> login.loginPage("/login")
        .defaultSuccessUrl("/select-features", true).permitAll())
    .logout(logout -> logout.permitAll());
```

#### 3.3.3 WebSocketHandler

```java
// feature/agent/chat/AgentChatWebSocketHandler.java
package org.zhzssp.memorandum.feature.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.policy.ToolConfirmCoordinator;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.runtime.AgentOrchestrator;
import org.zhzssp.memorandum.feature.agent.runtime.LongTermMemoryService;
import org.zhzssp.memorandum.feature.agent.tool.LocalBridgeProxy;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper om;
    private final UserRepository userRepository;
    private final AgentOrchestrator orchestrator;
    private final LocalBridgeProxy localBridgeProxy;
    private final ToolConfirmCoordinator confirmCoordinator;
    private final LongTermMemoryService longTermMemoryService;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public AgentChatWebSocketHandler(ObjectMapper om, UserRepository ur,
                                     @Lazy AgentOrchestrator orch, LocalBridgeProxy lb,
                                     ToolConfirmCoordinator cc, LongTermMemoryService ltm) {
        this.om = om; this.userRepository = ur; this.orchestrator = orch;
        this.localBridgeProxy = lb; this.confirmCoordinator = cc; this.longTermMemoryService = ltm;
    }

    private String sidOf(WebSocketSession s) {
        String path = s.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override public void afterConnectionEstablished(WebSocketSession s) { sessions.put(sidOf(s), s); }
    @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus c) { sessions.remove(sidOf(s)); }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode root = om.readTree(message.getPayload());
        String type = root.path("msgType").asText();
        String sid = sidOf(session);
        Principal p = session.getPrincipal();
        if (p == null) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        User user = userRepository.findByUsername(p.getName()).orElseThrow();

        switch (type) {
            case "chat" -> {
                String text = root.path("text").asText();
                String mode = root.path("mode").asText("chat");
                new Thread(() -> {
                    AgentContext.set(user, sid);
                    try {
                        String memo = longTermMemoryService.snippetFor(user);
                        orchestrator.handleUserTurn(sid, text, mode, memo);
                    } catch (Exception ex) {
                        sendError(sid, ex.getMessage());
                    } finally { AgentContext.clear(); }
                }, "agent-" + sid).start();
            }
            case "localResult" -> localBridgeProxy.onLocalResult(
                    root.path("reqId").asText(), root.path("result"));
            case "confirmReply" -> confirmCoordinator.onReply(
                    root.path("reqId").asText(), root.path("approved").asBoolean(false));
            default -> sendError(sid, "未知 msgType: " + type);
        }
    }

    private synchronized void send(String sid, Object payload) {
        WebSocketSession s = sessions.get(sid);
        if (s == null || !s.isOpen()) return;
        try { s.sendMessage(new TextMessage(om.writeValueAsString(payload))); }
        catch (Exception ignore) {}
    }
    public void sendAssistant(String sid, String text) { send(sid, Map.of("msgType","assistant","text", text)); }
    public void sendToolStart(String sid, String cid, String tool, Object args) {
        send(sid, Map.of("msgType","toolStart","callId", cid, "tool", tool, "args", args));
    }
    public void sendToolResult(String sid, String cid, String result) {
        send(sid, Map.of("msgType","toolResult","callId", cid, "result", result));
    }
    public void sendLocalCall(String sid, String reqId, String tool, Object args) {
        send(sid, Map.of("msgType","localCall","reqId", reqId, "tool", tool, "args", args));
    }
    public void sendConfirmReq(String sid, String reqId, String summary) {
        send(sid, Map.of("msgType","confirmReq","reqId", reqId, "summary", summary));
    }
    public void sendDone(String sid) { send(sid, Map.of("msgType","done")); }
    public void sendError(String sid, String msg) { send(sid, Map.of("msgType","error","message", msg)); }
}
```

#### 3.3.4 ToolConfirmCoordinator

```java
// feature/agent/policy/ToolConfirmCoordinator.java
package org.zhzssp.memorandum.feature.agent.policy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class ToolConfirmCoordinator {
    private final AgentChatWebSocketHandler ws;
    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
    public ToolConfirmCoordinator(@Lazy AgentChatWebSocketHandler ws) { this.ws = ws; }

    public CompletableFuture<Boolean> askUser(String sid, String reqId, String tool, JsonNode args) {
        var f = new CompletableFuture<Boolean>();
        pending.put(reqId, f);
        ws.sendConfirmReq(sid, reqId, "Agent 想调用工具 " + tool + "，参数：" + args.toString());
        return f.orTimeout(60, TimeUnit.SECONDS).exceptionally(ex -> false);
    }

    public void onReply(String reqId, boolean approved) {
        var f = pending.remove(reqId);
        if (f != null) f.complete(approved);
    }
}
```

---

### 3.4 本地能力桥（Electron）

#### 3.4.1 后端 LocalBridgeProxy

```java
// feature/agent/tool/LocalBridgeProxy.java
package org.zhzssp.memorandum.feature.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class LocalBridgeProxy {
    private final AgentChatWebSocketHandler ws;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    public LocalBridgeProxy(@Lazy AgentChatWebSocketHandler ws) { this.ws = ws; }

    public JsonNode call(String tool, Map<String,Object> args) throws Exception {
        String sid = AgentContext.sessionId();
        if (sid == null) throw new IllegalStateException("当前线程无 sessionId");
        String reqId = UUID.randomUUID().toString();
        var f = new CompletableFuture<JsonNode>();
        pending.put(reqId, f);
        try {
            ws.sendLocalCall(sid, reqId, tool, args);
            return f.orTimeout(30, TimeUnit.SECONDS).get();
        } finally { pending.remove(reqId); }
    }
    public void onLocalResult(String reqId, JsonNode result) {
        var f = pending.remove(reqId);
        if (f != null) f.complete(result);
    }
}
```

#### 3.4.2 LocalDocTools

```java
// feature/agent/tool/impl/LocalDocTools.java
@Component
public class LocalDocTools {
    private final LocalBridgeProxy bridge;
    public LocalDocTools(LocalBridgeProxy b) { this.bridge = b; }

    @AgentTool(name = "local.list_dir", tags = {"local","read"}, requiresConfirm = true,
               description = "列出本地目录下的所有文件与子目录（需在 Electron 白名单内）")
    public java.util.List<String> listDir(@ToolParam(value = "path", desc = "绝对路径", required = true) String path) throws Exception {
        return bridge.call("list_dir", java.util.Map.of("path", path)).findValuesAsText("name");
    }

    @AgentTool(name = "local.read_file", tags = {"local","read"}, requiresConfirm = true,
               description = "读取本地文本文件（utf-8），支持 md/txt/json/yml")
    public String readFile(@ToolParam(value = "path", desc = "绝对路径", required = true) String path) throws Exception {
        return bridge.call("read_file", java.util.Map.of("path", path)).path("content").asText();
    }

    @AgentTool(name = "local.read_pdf", tags = {"local","read"}, requiresConfirm = true,
               description = "读取本地 PDF 的纯文本内容")
    public String readPdf(@ToolParam(value = "path", desc = "绝对路径", required = true) String path) throws Exception {
        return bridge.call("read_pdf", java.util.Map.of("path", path)).path("content").asText();
    }
}
```

#### 3.4.3 Electron `main.js` 增量

```js
// electron-app/main.js 顶部新增
const fs = require('fs/promises');
const fssync = require('fs');
const pathModule = require('path');
const PERM = JSON.parse(fssync.readFileSync(
    pathModule.join(__dirname, 'permission-config.json'), 'utf-8'));

function isAllowed(p) {
  const norm = pathModule.resolve(p);
  if (PERM.denyDirs.some(d => norm.startsWith(pathModule.resolve(d)))) return false;
  return PERM.allowDirs.some(d => norm.startsWith(pathModule.resolve(d)));
}

// 在 createWindow 之前注册（位置示意）
ipcMain.handle('local:list_dir', async (_e, { path }) => {
  if (!isAllowed(path)) throw new Error('PATH_NOT_ALLOWED');
  const items = await fs.readdir(path, { withFileTypes: true });
  return items.map(it => ({ name: it.name, isDir: it.isDirectory() }));
});
ipcMain.handle('local:read_file', async (_e, { path }) => {
  if (!isAllowed(path)) throw new Error('PATH_NOT_ALLOWED');
  const ext = pathModule.extname(path).slice(1).toLowerCase();
  if (!PERM.allowExt.includes(ext)) throw new Error('EXT_NOT_ALLOWED');
  const stat = await fs.stat(path);
  if (stat.size > (PERM.maxFileBytes || 2 * 1024 * 1024)) throw new Error('FILE_TOO_LARGE');
  return { content: await fs.readFile(path, 'utf-8') };
});
ipcMain.handle('local:read_pdf', async (_e, { path }) => {
  if (!isAllowed(path)) throw new Error('PATH_NOT_ALLOWED');
  const pdfParse = require('pdf-parse');                 // V2 时 npm i pdf-parse
  const r = await pdfParse(await fs.readFile(path));
  return { content: r.text };
});
```

#### 3.4.4 Electron `preload.js` 增量（在原文件追加）

```js
// electron-app/preload.js（在原 contextBridge.exposeInMainWorld('latticePlanner', ...) 之后追加）
contextBridge.exposeInMainWorld('lattice', {
  localBridge: {
    listDir:  (p) => ipcRenderer.invoke('local:list_dir',  { path: p }),
    readFile: (p) => ipcRenderer.invoke('local:read_file', { path: p }),
    readPdf:  (p) => ipcRenderer.invoke('local:read_pdf',  { path: p })
  }
});
```

#### 3.4.5 `electron-app/permission-config.json`

```json
{
  "allowDirs": ["D:/notes", "D:/learning", "C:/Users/PUBLIC/Documents"],
  "denyDirs":  ["C:/Windows", "C:/Program Files"],
  "allowExt":  ["md","txt","json","yml","yaml","csv","log","pdf"],
  "maxFileBytes": 2097152
}
```

> 用户首次启动客户端时检查该文件存在性，不存在则生成默认模板。

---

### 3.5 笔记业务下沉到 NoteService（v3 新增必要项）

```java
// core/service/NoteService.java
package org.zhzssp.memorandum.core.service;

import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.NoteRepository;

import java.util.List;

@Service
public class NoteService {
    private final NoteRepository noteRepository;
    public NoteService(NoteRepository r) { this.noteRepository = r; }

    public List<Note> listByUser(User u) { return noteRepository.findByUser(u); }
    public Note create(User u, String title, String content, NoteType type) {
        Note n = new Note();
        n.setUser(u); n.setTitle(title); n.setContent(content);
        n.setType(type == null ? NoteType.SCRATCH : type);
        return noteRepository.save(n);
    }
    public List<Note> listByUserAndType(User u, NoteType t) {
        return noteRepository.findByUser(u).stream().filter(n -> n.getType() == t).toList();
    }
}
```

`NoteController` 替换为调用 `NoteService.listByUser` / `NoteService.create`。

**新增枚举值**：

```java
// entity/NoteType.java
public enum NoteType { SCRATCH, LEARNING, PROJECT, RETROSPECTIVE, AGENT_MEMO }
```

> 由于 `@Enumerated(EnumType.STRING)`，追加新值不影响旧数据；MySQL 的 `note.type` 字段是 `varchar`，不需要 DDL 变更。

---

### 3.6 长期记忆 LongTermMemoryService

```java
// feature/agent/runtime/LongTermMemoryService.java
package org.zhzssp.memorandum.feature.agent.runtime;

import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.core.service.NoteService;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class LongTermMemoryService {
    private final NoteService noteService;
    private final LlmGateway llm;
    public LongTermMemoryService(NoteService n, LlmGateway l) { this.noteService = n; this.llm = l; }

    public String snippetFor(User user) {
        List<Note> memos = noteService.listByUserAndType(user, NoteType.AGENT_MEMO).stream()
                .sorted(Comparator.comparing(Note::getCreatedAt).reversed())
                .limit(5).toList();
        if (memos.isEmpty()) return "";
        return memos.stream().map(n -> "- " + n.getTitle() + "：" + n.getContent())
                .collect(Collectors.joining("\n"));
    }

    public void archive(User user, List<ConversationMemory.Msg> history) {
        if (history == null || history.isEmpty()) return;
        String dialog = history.stream()
                .map(m -> m.role() + ": " + m.content())
                .collect(Collectors.joining("\n"));
        try {
            String memo = llm.generateText(
                "请把下面这段用户与助手的对话凝练为 3~6 行用户画像 / 偏好 / 待办线索，纯文本输出：\n\n" + dialog);
            noteService.create(user, "Agent 长期记忆 " + java.time.LocalDate.now(), memo, NoteType.AGENT_MEMO);
        } catch (Exception ignore) {}
    }
}
```

> 归档触发时机：①用户在 UI 点"结束会话"按钮 → 前端发 `{msgType:"archive"}`（建议在 V3 加）；②`AgentChatWebSocketHandler.afterConnectionClosed` 异步触发。

---

### 3.7 Web 端 Chat Panel（IDE 风抽屉）

#### 3.7.1 模板片段

```html
<!-- src/main/resources/templates/fragments/agent-panel.html -->
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<div th:fragment="panel">
    <link rel="stylesheet" th:href="@{/agent/chat-panel.css}">
    <button id="lp-agent-fab" type="button" title="打开 Lattice-Agent">AI</button>
    <aside id="lp-agent-panel" aria-hidden="true">
        <header>
            <span>Lattice-Agent</span>
            <select id="lp-agent-mode">
                <option value="chat">Chat</option>
                <option value="plan">自动规划</option>
                <option value="reflect">复盘</option>
            </select>
            <button id="lp-agent-close" type="button">×</button>
        </header>
        <ol id="lp-agent-stream"></ol>
        <footer>
            <textarea id="lp-agent-input" placeholder="向 Agent 提问，按 Ctrl+Enter 发送…"></textarea>
            <button id="lp-agent-send" type="button">发送</button>
        </footer>
    </aside>
    <script th:src="@{/agent/chat-panel.js}"></script>
</div>
</body>
</html>
```

> **注入方式**：因为现有 6 个模板没有 layout 共用，需要在 `dashboard.html`、`addMemo.html`、`preferenceSettings.html`、`selectFeatures.html` 这 **4 个登录后页面** 的 `</body>` 之前加：
> ```html
> <div th:replace="~{fragments/agent-panel :: panel}"></div>
> ```
> `login.html` / `register.html` 不注入。

#### 3.7.2 chat-panel.js（核心 ~150 行）

```js
// src/main/resources/static/agent/chat-panel.js
(function () {
  const sid = (crypto.randomUUID && crypto.randomUUID()) ||
              (Date.now() + '-' + Math.random().toString(16).slice(2));
  const stream = document.getElementById('lp-agent-stream');
  const input = document.getElementById('lp-agent-input');
  const send = document.getElementById('lp-agent-send');
  const modeSel = document.getElementById('lp-agent-mode');
  const fab = document.getElementById('lp-agent-fab');
  const panel = document.getElementById('lp-agent-panel');
  document.getElementById('lp-agent-close').onclick = () => panel.classList.remove('open');
  fab.onclick = () => panel.classList.add('open');

  // 1) 建立 WebSocket（同源，自动带 JSESSIONID Cookie）
  const wsUrl = (location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + location.host
              + '/ws/agent/' + sid;
  const ws = new WebSocket(wsUrl);
  ws.onopen = () => addBubble('system', '已连接到 Agent');
  ws.onclose = () => addBubble('system', '连接已断开');
  ws.onerror = () => addBubble('system', '连接错误');

  ws.onmessage = async (ev) => {
    const m = JSON.parse(ev.data);
    switch (m.msgType) {
      case 'assistant':  addBubble('assistant', m.text); break;
      case 'toolStart':  addToolCard(m.callId, m.tool, m.args); break;
      case 'toolResult': fillToolResult(m.callId, m.result); break;
      case 'localCall':  await handleLocalCall(m); break;
      case 'confirmReq': addConfirm(m.reqId, m.summary); break;
      case 'error':      addBubble('system', '错误：' + m.message); break;
      case 'done':       send.disabled = false; break;
    }
  };

  // 2) 发送
  function sendChat() {
    const text = input.value.trim();
    if (!text) return;
    addBubble('user', text);
    ws.send(JSON.stringify({ msgType: 'chat', text, mode: modeSel.value }));
    input.value = '';
    send.disabled = true;
  }
  send.onclick = sendChat;
  input.addEventListener('keydown', (e) => {
    if (e.ctrlKey && e.key === 'Enter') sendChat();
  });

  // 3) 反向调用本地工具（仅 Electron 环境可用）
  async function handleLocalCall(m) {
    if (!window.lattice || !window.lattice.localBridge) {
      ws.send(JSON.stringify({ msgType: 'localResult', reqId: m.reqId,
        result: { error: 'BRIDGE_NOT_AVAILABLE' } }));
      return;
    }
    const fn = window.lattice.localBridge[
      ({ list_dir: 'listDir', read_file: 'readFile', read_pdf: 'readPdf' })[m.tool]
    ];
    try {
      const result = await fn(m.args.path);
      ws.send(JSON.stringify({ msgType: 'localResult', reqId: m.reqId, result }));
    } catch (e) {
      ws.send(JSON.stringify({ msgType: 'localResult', reqId: m.reqId,
        result: { error: e.message || String(e) } }));
    }
  }

  // 4) 用户确认
  function addConfirm(reqId, summary) {
    const li = document.createElement('li');
    li.className = 'lp-confirm';
    li.innerHTML = '<div>' + escapeHtml(summary) + '</div>' +
      '<button data-ok="1">允许</button><button data-ok="0">拒绝</button>';
    li.querySelectorAll('button').forEach(b => b.onclick = () => {
      ws.send(JSON.stringify({
        msgType: 'confirmReply', reqId, approved: b.dataset.ok === '1'
      }));
      li.querySelectorAll('button').forEach(x => x.disabled = true);
    });
    stream.appendChild(li); scrollEnd();
  }

  // 5) UI helpers
  function addBubble(role, text) {
    const li = document.createElement('li');
    li.className = 'lp-bubble lp-' + role;
    li.textContent = text;
    stream.appendChild(li); scrollEnd();
  }
  function addToolCard(cid, tool, args) {
    const li = document.createElement('li');
    li.className = 'lp-tool'; li.dataset.cid = cid;
    li.innerHTML = '<div class="lp-tool-h">⚙ ' + tool + '</div>' +
      '<pre class="lp-tool-args">' + escapeHtml(JSON.stringify(args, null, 2)) + '</pre>' +
      '<pre class="lp-tool-res">运行中…</pre>';
    stream.appendChild(li); scrollEnd();
  }
  function fillToolResult(cid, result) {
    const li = stream.querySelector('li[data-cid="' + cid + '"]');
    if (!li) return;
    li.querySelector('.lp-tool-res').textContent = truncate(result, 1200);
  }
  function escapeHtml(s){return String(s).replace(/[&<>"']/g, c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
  function truncate(s, n){return s && s.length > n ? s.slice(0,n)+'…' : s;}
  function scrollEnd(){stream.scrollTop = stream.scrollHeight;}
})();
```

#### 3.7.3 chat-panel.css（视觉骨架）

```css
/* src/main/resources/static/agent/chat-panel.css */
#lp-agent-fab{position:fixed;right:24px;bottom:24px;z-index:9998;
  width:48px;height:48px;border-radius:50%;border:none;
  background:#3b82f6;color:#fff;font-weight:700;cursor:pointer;
  box-shadow:0 4px 12px rgba(0,0,0,.2)}
#lp-agent-panel{position:fixed;top:0;right:-420px;width:400px;height:100%;
  background:#fff;box-shadow:-2px 0 12px rgba(0,0,0,.12);
  display:flex;flex-direction:column;transition:right .25s ease;z-index:9999;font-size:13px}
#lp-agent-panel.open{right:0}
#lp-agent-panel header{display:flex;align-items:center;gap:8px;
  padding:10px 12px;border-bottom:1px solid #eee;font-weight:600}
#lp-agent-panel header select{margin-left:auto}
#lp-agent-stream{flex:1;list-style:none;margin:0;padding:12px;overflow-y:auto}
#lp-agent-stream li{margin-bottom:10px;padding:8px 10px;border-radius:8px;line-height:1.5;white-space:pre-wrap}
.lp-user{background:#e0f2fe;align-self:flex-end}
.lp-assistant{background:#f1f5f9}
.lp-system{background:#fef9c3;font-size:12px;color:#92400e}
.lp-tool{background:#f8fafc;border:1px solid #e2e8f0}
.lp-tool-h{font-weight:600;margin-bottom:4px;color:#0f172a}
.lp-tool pre{margin:4px 0;padding:6px;background:#0f172a;color:#e2e8f0;border-radius:4px;
  font-size:11px;max-height:160px;overflow:auto}
.lp-confirm{background:#fef3c7;border:1px solid #fbbf24}
.lp-confirm button{margin:6px 6px 0 0;padding:4px 12px;cursor:pointer}
#lp-agent-panel footer{padding:8px;border-top:1px solid #eee}
#lp-agent-input{width:100%;height:64px;resize:vertical;padding:6px;border:1px solid #ddd;border-radius:4px}
#lp-agent-send{margin-top:6px;padding:6px 16px;background:#3b82f6;color:#fff;border:none;border-radius:4px;cursor:pointer}
#lp-agent-send:disabled{opacity:.5;cursor:wait}
```

---

## 4. WebSocket 协议（最终版）

| 方向 | msgType | 字段 | 备注 |
|---|---|---|---|
| C→S | `chat` | `text`, `mode` | mode ∈ {chat, plan, reflect} |
| C→S | `localResult` | `reqId`, `result` | result 是 JsonNode（成功 `{content:...}` / 失败 `{error:...}`） |
| C→S | `confirmReply` | `reqId`, `approved` | bool |
| S→C | `assistant` | `text` | LLM 终态回答 |
| S→C | `toolStart` | `callId`, `tool`, `args` | UI 显示运行中卡片 |
| S→C | `toolResult` | `callId`, `result` | result 已是 JSON 字符串 |
| S→C | `localCall` | `reqId`, `tool`, `args` | 反向下发本地工具 |
| S→C | `confirmReq` | `reqId`, `summary` | 渲染允许/拒绝按钮 |
| S→C | `done` | — | 一轮对话结束，前端解禁发送按钮 |
| S→C | `error` | `message` | 兜底错误 |

> 一律 JSON 文本帧，UTF-8。`sessionId` 由前端生成（`crypto.randomUUID`）写在 URL 中，不再随消息体传递。

---

## 5. 配置增量

`build.gradle` 增量（V2 时再加 pdfbox 备选；当前 Electron 端用 npm 的 `pdf-parse`，**Java 端无需新增依赖**）：

```groovy
// 现有依赖足够；pdfbox 仅在希望后端也做 PDF 解析时加。V1 不需要。
```

`application.properties` 新增：

```properties
# Agent Chat 配置
agent.chat.model=deepseek-chat
agent.chat.max-steps=8
agent.chat.history-window=30
agent.chat.session-idle-archive-minutes=30
```

`electron-app/package.json` V2 阶段新增（用于 PDF）：

```bash
npm i pdf-parse
```

---

## 6. 安全清单

| 风险 | 措施 | 落点 |
|---|---|---|
| LLM 乱调写库工具 | `@AgentTool(requiresConfirm=true)` + `ToolConfirmCoordinator` 弹窗 | `ToolRegistry` + 各 `*Tools` |
| 越权读其他用户数据 | 所有工具入口均 `AgentContext.requireUser()`；写操作内手动校验 `t.getUser().getId().equals(user.getId())` | `*Tools` 实现 |
| Prompt Injection | 系统 Prompt 强约束 + 写工具一律 requiresConfirm | `PromptBuilder` |
| 读取敏感目录 | Electron `permission-config.json` 双名单 + 文件大小 + 后缀名校验 | `electron-app/main.js` |
| WebSocket 未携带登录态 | Spring Security 默认 `anyRequest().authenticated()` 已强制；`session.getPrincipal()==null` 时关闭连接 | `AgentChatWebSocketHandler` |
| CSRF 拦截 WS 握手 | `WebSecurityConfig.csrf().ignoringRequestMatchers("/ws/agent/**")` | `WebSecurityConfig` |
| 暴露 LLM Key | 沿用 `agent.llm.api-key`，仅服务端持有 | `LlmGateway` |
| 工具循环 / 失控 | `agent.chat.max-steps=8` 硬上限，结束兜底回复 | `AgentOrchestrator` |
| 大文件 / OOM | Electron 侧 `maxFileBytes=2MB`；后端 `truncate(resultJson, 4000)` 喂回 LLM | 双端共同把关 |

---

## 7. 工具 V1 完整清单（15 个，足够 80% 场景）

| 工具名 | tags | requiresConfirm | 说明 |
|---|---|---|---|
| `task.create` | task,write | ❌ | 创建任务（低风险） |
| `task.search` | task,read | ❌ | 关键字 + 日期范围搜索 |
| `task.today` | task,read | ❌ | 今日可行动 |
| `task.complete` | task,write | ✅ | 标记完成 |
| `task.archive` | task,write | ✅ | 归档 |
| `task.fuzzy_pending` | task,read | ❌ | 列出需拆分的模糊任务 |
| `goal.list` | goal,read | ❌ | 当前活跃目标 |
| `goal.create` | goal,write | ✅ | 新建目标 |
| `goal.archive` | goal,write | ✅ | 归档目标（级联归档任务） |
| `goal.link_task` | goal,write | ✅ | 关联任务到目标 |
| `note.list` | note,read | ❌ | 列出当前用户笔记 |
| `note.create` | note,write | ❌ | 创建笔记（低风险） |
| `insight.daily_scores` | insight,read | ❌ | 取日期范围内每日得分 |
| `insight.summarize_period` | insight,read | ❌ | 调 `AiSummaryService` |
| `planner.draft_goal_plan` | planner,read | ❌ | 调 `PlannerAgentService.draftPlan` |
| `planner.apply_goal_plan` | planner,write | ✅ | 调 `AgentPlanApplyService.apply` |

V2 追加 3 个：

| 工具名 | tags | requiresConfirm | 说明 |
|---|---|---|---|
| `local.list_dir` | local,read | ✅ | 列本地目录 |
| `local.read_file` | local,read | ✅ | 读 md/txt/json/yml |
| `local.read_pdf` | local,read | ✅ | 读 PDF |

---

## 8. 分阶段落地（详细到天）

### V1 — Chat 面板 + 业务工具（10~14 天）

| Day | 任务 | 验收 |
|---|---|---|
| 1 | `@AgentTool` / `@ToolParam` / `ToolDefinition` / `ToolRegistry` + 单元测试 | 启动后日志打印 `Registered N tools` |
| 2 | `AgentContext` + `LlmGateway.generateChat` 增量 | 直接构造一段 messages 调通 DeepSeek 返回 |
| 3 | `ToolCallParser` + `ConversationMemory` + `PromptBuilder` 单测 | reasoner 含 think 段也能稳定 parse |
| 4 | `AgentWebSocketConfig` + `AgentChatWebSocketHandler` 骨架 + `WebSecurityConfig` diff | 浏览器 `new WebSocket('/ws/agent/x')` 握手成功，能接到 chat 消息 |
| 5 | `ToolConfirmCoordinator` + `AgentOrchestrator` 串起来 | 手动 mock 一个 echo 工具，端到端跑通一轮 |
| 6 | `TaskTools`（5 个方法） | 浏览器对话："创建一个明天的任务：写周报"，能在 dashboard 看到新任务 |
| 7 | 新建 `NoteService` + 改 `NoteController` + `NoteTools` | 笔记接口回归测试通过 |
| 8 | `GoalTools`（4 个方法）+ requiresConfirm 联调 | 对话："归档目标 5"触发弹窗 |
| 9 | `InsightTools` + `PlannerTools`（2+2 个方法） | 对话："看下我过去 30 天的执行情况"，得到 LLM 总结 |
| 10 | `chat-panel.html/.css/.js` + `fragments/agent-panel.html` | 抽屉可弹出，消息流正常 |
| 11 | 4 个登录后页面注入 fragment + 视觉打磨 | 各页面均能呼出面板 |
| 12 | 多用户隔离测试（同时两个 JSESSIONID） | A 用户看不到 B 用户的任务/历史 |
| 13 | 端到端联调 + Bug 修复 | 验收 §10 三条 Demo 全部跑通 |
| 14 | 录屏 + 更新 README "Agent 使用指南" | 交付 |

### V2 — 本地文档桥（7~10 天）

| Day | 任务 |
|---|---|
| 1 | `electron-app/permission-config.json` + `main.js` 增量 + `preload.js` 增量 |
| 2 | 后端 `LocalBridgeProxy` + `LocalDocTools.{listDir, readFile}` |
| 3 | 前端 `handleLocalCall` 反向调用 + 异常路径测试 |
| 4 | `npm i pdf-parse` + `read_pdf` |
| 5-6 | 联调："读 D:/learning/spring-ai/ 的所有 md，挑出我没掌握的章节，生成下周冲刺计划" |
| 7 | 黑名单防御测试（尝试读 C:/Windows） |
| 8-10 | UI 体验打磨 + `@文件` 引用按钮 |

### V3 — 长期记忆 + 反思（5~7 天）

| Day | 任务 |
|---|---|
| 1 | `LongTermMemoryService.archive` 接入 `afterConnectionClosed` |
| 2 | `NoteType` 增 `AGENT_MEMO` + `snippetFor` 注入系统 Prompt |
| 3-4 | `AgentOrchestrator` 增加显式 Reflexion：连续 2 次同名工具失败时强制换工具 |
| 5-7 | （可选）pgvector + `memory.search` 工具 |

### V4 — MCP 兼容适配器（仅当面试需要时再做，3~5 天）

- 在 `ToolRegistry` 旁加 `MCPCompatibilityAdapter`：
  - **对外接入**：能加载外部标准 MCP Server，把它的 `tools/list` 注册进 `ToolRegistry`；
  - **对外暴露**：把 `ToolRegistry` 反向暴露成一个 MCP Server，让 Cursor/Claude Desktop 接入。

---

## 9. 创新性叙事（面试版三段）

1. **垂直 OKR Agent 闭环**：不是又一个 Chat + 工具，而是把 OKR 方法论落到 Agent 上：目标→拆解→任务→执行→评分→复盘→再拆解，全部在一个进程里发生，所有副作用都落到强结构化业务表里。
2. **事件回环（最大亮点）**：Agent 调 `task.create` → Spring 发 `TaskCreatedEvent` → 既有的 `GoalEventListener` 自动重算目标进度 → 下一轮 Agent 决策能看到新进度。**Agent 副作用与领域事件天然耦合**——这种设计在通用 Agent 框架里几乎没有，是"在已有 DDD 工程里嵌 Agent"的特有红利。
3. **本地桥 = 内嵌 MCP**：不引入独立 MCP 子进程组，借助 Electron `preload` 暴露 IPC + 后端 WebSocket 反向调度，**等价于"内嵌 MCP Server"**，安全语义完全等同（后端 JVM 不直接 IO 本地资源），但实施成本降一个数量级。预留 `MCPCompatibilityAdapter` 作为生态兼容入口。

---

## 10. 验收 Demo 脚本

录屏一镜到底 ~120 秒，覆盖三条典型路径：

### Demo A — 业务闭环（V1 完成即可演示）

> 用户："帮我创建一个目标：三个月内吃透 Spring AI；约束：每周 6 小时。"  
> Agent → `planner.draft_goal_plan(goalStatement="...", constraints=["每周6小时"])` → 展示拆解任务树 →  
> 用户："看起来不错，应用它。" → Agent → `planner.apply_goal_plan` → **弹窗确认** → 落库 →  
> Agent → `goal.list` 确认结果 → "已为你创建目标'三个月内吃透 Spring AI'，含 12 个任务，请到 dashboard 的目标树视图查看"。  
> 验证点：dashboard 刷新后目标树确实出现该目标，目标进度由 `GoalEventListener` 自动算出。

### Demo B — 本地文档→规划（V2 完成）

> 用户："@D:/learning/spring-ai 下所有 md 挑出我没掌握的章节，生成下周冲刺计划。"  
> Agent → `local.list_dir` **弹窗** → 多次 `local.read_file` **弹窗（首次允许后可批量放行）** → 内部分析 →  
> `planner.draft_goal_plan` → 展示 → 用户确认 → `planner.apply_goal_plan`。  
> 验证点：后端 JVM 全程没有任何 `java.io.File` 调用本地磁盘。

### Demo C — 复盘（V1 完成即可演示）

> 用户："看下我过去 30 天的执行情况。"  
> Agent → `insight.daily_scores(from, to)` → `insight.summarize_period` → 给出文字总结 + 可执行建议 →  
> Agent → `note.create(type=RETROSPECTIVE)` 把总结自动存档 →  
> 下一次新会话启动时，`LongTermMemoryService.snippetFor` 自动把这段总结摘要注入系统 Prompt（V3）。

---

## 11. 风险与备选

| 风险 | 概率 | 备选 |
|---|---|---|
| `deepseek-chat` 输出非严格 JSON | 中 | `ToolCallParser` 已具 think + fence + 大括号定位三层兜底；最差时把错误回灌让 LLM 重试 |
| 多步循环 token 爆炸 | 低 | `ConversationMemory.WINDOW=30` + 单次 `truncate(resultJson, 4000)` |
| WebSocket 断线导致 `pending` 永不完成 | 中 | `LocalBridgeProxy` 30s + `ToolConfirmCoordinator` 60s 超时 → 完成为否 |
| `ToolConfirmCoordinator` / `LocalBridgeProxy` 与 `AgentChatWebSocketHandler` 形成循环依赖 | 高 | 已用 `@Lazy` 注入 + `LocalBridgeProxy` 由 Handler 主动 onLocalResult 回调，不会真循环 |
| 用户配错 `permission-config.json` 全部被拒 | 中 | 客户端启动时若文件不存在自动生成默认模板，并在 UI 提示 |
| 单线程 ReAct 阻塞 HTTP 端口 | 低 | Handler 内 `new Thread(...).start()` 隔离推理线程；后续可换 `Executors.newCachedThreadPool` |

---

## 12. v2 → v3 差异速查

| 维度 | v2 | v3 |
|---|---|---|
| 工具示例代码 | 假设 `taskService.createForCurrentUser` 等不存在的 API | 严格按真实 `saveTask(Task, User)` 重写，给完整可编译的 `TaskTools` |
| `NoteService` | 假定存在 | **明确指出不存在**，给出新增代码 + Controller 改造 |
| WebSocket | "只需启用即可" | 给出完整 `AgentWebSocketConfig` + Handler + Security CSRF 豁免 diff |
| LLM 模型 | 假设 chat 模型 | 揭示线上是 reasoner，要求 `ToolCallParser.stripThinking`；新增 `agent.chat.model` 配置默认强制 chat |
| 事件复用 | 一句话带过 | 明确 `task.create` 内部走 `taskService.saveTask` → `TaskCreatedEvent` → `GoalEventListener` 链路；显式说删除/搁置不发事件，故 V1 不开放删除工具 |
| UI 落地 | "建一个 fragment 引入" | 给完整 HTML/CSS/JS 骨架，明确说明 6 个模板没 layout，逐页注入 4 个 |
| 阶段计划 | 按周粒度 | 按天粒度 + 每日验收点 |
| 安全清单 | 4 行 | 9 行，含真实落点（CSRF 豁免、`session.getPrincipal()==null` close、ThreadLocal、文件大小） |

