# 架构说明：插件化与概念隔离

## 包结构

### 核心层（Core Layer）

核心层包含极简的业务实体和基础服务，不依赖任何插件。

```
core/
├── event/              # 事件定义
│   ├── TaskCompletedEvent.java
│   ├── TaskCreatedEvent.java
│   └── TaskArchivedEvent.java
└── service/            # 核心服务
    └── TaskService.java
```

**核心实体**（位于 `entity/`）：
- `Task` - 任务实体
- `Note` - 笔记实体
- `Link` - 弱关联实体
- `User` - 用户实体

**核心原则**：
- 核心层不依赖插件层
- 核心服务通过事件机制与插件解耦
- 核心实体保持极简，不包含业务扩展字段

### 插件层（Feature/Plugin Layer）

插件层通过事件监听扩展核心功能，不直接修改核心代码。

```
feature/
└── goal/               # 目标功能插件
    ├── entity/         # Goal 实体（插件层）
    ├── repository/     # GoalRepository
    ├── service/        # GoalService
    ├── controller/    # GoalController
    └── listener/      # GoalEventListener（事件监听器）
```

**插件原则**：
- 插件依赖核心，核心不依赖插件
- 通过事件监听扩展行为，而非直接修改核心代码
- 每个插件独立，互不依赖

## 事件驱动架构

### 事件发布

核心服务在关键操作时发布事件：

```java
// TaskService.java
public Task completeTask(Task task, User user) {
    task.setStatus(TaskStatus.DONE);
    Task saved = taskRepository.save(task);
    eventPublisher.publishEvent(new TaskCompletedEvent(saved, user));
    return saved;
}
```

### 事件监听

插件通过 `@EventListener` 监听事件并扩展行为：

```java
// GoalEventListener.java
@EventListener
public void onTaskCompleted(TaskCompletedEvent event) {
    // 可以在这里实现：更新目标进度、推荐笔记等
    // 未来扩展点，不污染核心代码
}
```

## 扩展新功能

### 添加新插件

1. 在 `feature/` 下创建新插件目录（如 `feature/insight/`）
2. 创建事件监听器监听核心事件
3. 实现插件特定的业务逻辑
4. 核心代码无需修改

### 示例：添加统计插件

```java
// feature/insight/listener/StatisticsEventListener.java
@Component
public class StatisticsEventListener {
    
    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        // 更新统计信息
    }
    
    @EventListener
    public void onTaskCreated(TaskCreatedEvent event) {
        // 更新任务计数
    }
}
```

## 优势

1. **核心极简**：核心层保持最小化，易于理解和维护
2. **插件独立**：每个插件独立开发和部署
3. **易于扩展**：添加新功能只需新增监听器，不修改核心代码
4. **解耦设计**：核心与插件通过事件解耦，降低耦合度
5. **向后兼容**：核心层变更不影响插件层

## 当前实现

- ✅ 核心层：TaskService 事件发布
- ✅ 插件层：Goal 功能插件
- ✅ 事件系统：TaskCompletedEvent, TaskCreatedEvent, TaskArchivedEvent
- ✅ 事件监听：GoalEventListener

## 未来扩展

- 📋 统计插件（insight）：任务完成率、时间分析等
- 🤖 AI 插件（ai）：任务总结、笔记推荐等
- 📊 分析插件（analytics）：学习模式分析、目标聚类等
