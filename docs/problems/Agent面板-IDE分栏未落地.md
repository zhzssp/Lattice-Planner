# P-UI-001 · Agent 面板 IDE 左右分栏未在运行时落地

| 项 | 内容 |
|---|---|
| 状态 | **未解决**（2026-09-15 仍被复现） |
| 期望 | 打开后面板与 dashboard **并排**；拖左缘缩放时主区同步变窄；关闭后主区回全宽 |
| 实际 | 打开后仍像右侧浮层盖在 dashboard 上，主区不被挤窄 |
| 客户端 | Electron（`persist:main`）为主；浏览器次之 |
| 最近一次代码 | `3d02f69`（2026-09-15）源码已改成 flex + sash，**没有在登录后的真实 dashboard / Electron 上点过** |

---

## 1. 期望交互

对齐 Cursor / VS Code 的右侧栏，而不是「抽屉盖住页面」：

```
关闭：  [=========== dashboard 全宽 ===========]  FAB
打开：  [==== dashboard 可滚动 ====][sash][panel]
拖拽：  只改 --lp-agent-width，左栏 flex:1 跟着变窄
关闭：  右栏宽度归零，dashboard 回到视口全宽
```

覆盖页面：dashboard、笔记列表/查看/编辑、添加任务、偏好设置、MCP 设置、选择思维模式。Codex 系列页没有 Agent 面板，不在范围内。

---

## 2. 为什么看起来像「盖住」而不是「挤开」

两件事叠在一起，缺一不可。只修其中一件，用户观感仍是覆盖。

### 2.1 面板的历史模型是 `position: fixed` 浮层

旧 `chat-panel.css` 把 `#lp-agent-panel` 钉在视口右侧。浮层不占文档流，dashboard 的布局宽度不变，面板只是叠在上面。

给 `body` 加 `padding-right` / `margin-right` **挡不住**这件事：dashboard 的卡片是

```css
.container {
    max-width: 980px;
    margin: 80px auto;   /* 水平居中 */
}
```

宽屏上卡片几乎不随右边距收缩，右边空出来的是**渐变背景**，面板仍然盖在视口上。这和 IDE「编辑区变窄」不是一回事。

### 2.2 运行中的页面经常不是当前源码

Electron 使用 `partition: 'persist:main'`。关窗口只进托盘，**进程不退**，旧 `chat-panel.css` 会继续把面板钉成 `fixed`。

无头浏览器对**同一套新 CSS**测过（上一轮静态 probe）：

| DOM | overlap | 是否并排 |
|---|---|---|
| 有 `.lp-page-shell` | 0px | 是（左约 978 + 右 440） |
| 没有这层壳 | 约 261px | 否，面板叠在卡片上 |

所以「源码里已经是分栏」和「你屏幕上已经是分栏」不是同一个命题。

---

## 3. 失败路径（按时间）

都宣称修了分栏，用户侧始终没看到。

| 提交 | 做法 | 为什么不够 |
|---|---|---|
| `870188a` | 打开时把宿主页向左推 | 面板仍是 `fixed`；980px 居中卡片几乎不缩 |
| `6e6a8f9` | JS 包 `#lp-agent-host`，声称回到文档流 | 运行页仍吃到旧浮层 CSS / 旧 HTML |
| `5c7479a` | 模板加 `.lp-page-shell` | 壳一旦没套上（缓存旧 HTML），`body` 仍是纵向排版 |
| `b77ef36` | 缺壳时 JS 补壳；禁 HTML 缓存 | 补壳救得了 DOM，救不了已被 `persist` 盘住的 `position:fixed` |
| `6b42d63` | fragment 内联 `!important`；Electron `clearCache` | 内联能压过旧 CSS，但要用户**托盘退出再开**；未在实页确认 |
| `46c33da` | `AgentLayoutFilter` 改写全部 HTML，打 `split-v11` 指纹 | 改写 Content-Length / 编码风险高；掩盖「模板没加载到」；用户仍看不到分栏 |
| `3d02f69` | 拆掉注入栈，收成 flex + 独立 sash；夹具测试绿灯 | **夹具 ≠ Electron 实页**。本机当时 8080 没在跑，没有对着登录后 dashboard 点开关 |

`3d02f69` 之后源码意图是对的，当前结构：

- `.lp-page-shell` > `.lp-page-main` + `.lp-agent-workbench`（`display: contents`）
- workbench 里：FAB（`position: fixed`）+ `#lp-agent-sash` + `.lp-agent-mount`
- 打开：`html.lp-agent-open`，mount 宽度 `--lp-agent-width`（默认 440px）
- 拖拽：sash 的 `clientX` 相对 shell 右缘，写入 CSS 变量和 `localStorage`（`lp-agent-width-v2`）
- 面板规则是 `position: relative`，**源码里不再给面板写 `position: fixed`**

这份结构若在**当前进程、当前 CSS**里生效，几何上就是分栏。用户仍看到覆盖，说明运行时至少有一层没吃到这份源码，或吃到了但视觉上仍像覆盖（见 §5）。

---

## 4. 测试为什么是绿的（假闭环）

`AgentPanelLayoutTest` 做了两件事：

1. **源码契约**：fragment 有 sash、CSS 里面板是 `relative`、宿主页有 `lp-page-shell`、Filter/layout-boot 已删除。
2. **Chrome headless 夹具**：把**当前** `chat-panel.css` / `chat-panel.js` **内联**进一张假 dashboard，再点 FAB / 拖 sash / 关面板。1440×900 下 overlap=0、拖拽主区变窄、关闭右栏收回。

夹具证明「新 CSS/JS 自己能分栏」。它**故意绕开**了真正让用户失败的环境：

- Electron `persist:main` 里的旧 CSS
- 未退出的托盘进程
- 仍在跑的旧 Spring Boot classpath / Thymeleaf 缓存
- 需要登录才能看到的真实 `dashboard.html`
- `display: contents` 在 Electron 自带 Chromium 上是否把 FAB/sash/mount 提升成 shell 的 flex 子项

这和评测体系里「假绿」是同一类错误：**测量的不是用户在用的那一层**。

---

## 5. 运行时仍失败时，优先怀疑这几层

按这个顺序查，不要先改 CSS。

1. **进程是不是旧的**  
   托盘里退出 Lattice-Planner，再开。只关窗口等于没更新。Spring Boot 也要停干净再启，避免旧 JVM 仍占 8080。

2. **实际生效的 CSS 是不是 `v=12` 这份**  
   DevTools → `#lp-agent-panel` 的 computed `position`。  
   - `fixed`：仍是旧文件，分栏 CSS 根本没生效。  
   - `relative` 且面板与主区矩形相交（overlap > 0）：壳/flex 没成立。  
   - `relative` 且 overlap≈0，但「看起来像盖住」：多半是左栏卡片仍 `max-width: 980px; margin: auto`，`html.lp-agent-open` 没加上，或加了但左栏空白被当成「被盖住」。

3. **DOM 里有没有 `.lp-page-shell` / `.lp-agent-workbench` / `#lp-agent-sash`**  
   没有 sash、还在用 `#lp-agent-resizer`：模板不是 `3d02f69`。

4. **`html` 上有没有 `lp-agent-open`**  
   没有则右栏宽度规则不会打开，FAB 也不会藏。

5. **不要再加**  
   内联 `!important`、HTML Filter、debug 芯片、`max-width: calc(100% - var(--lp-agent-width))`。这些已经走过一遍，把问题从「布局」变成「缓存战争」，没有让实页变绿。

---

## 6. 怎样才算关闭本题

必须在**用户同款路径**上量几何，不能只跑 `AgentPanelLayoutTest`。

环境：重启后的 Spring Boot + **托盘退出再开**的 Electron（或同版本 Chrome 打开 `http://localhost:8080/dashboard`，已登录）。

控制台执行：

```js
(function () {
  var main = document.querySelector('.lp-page-main');
  var panel = document.getElementById('lp-agent-panel');
  var mount = document.querySelector('.lp-agent-mount');
  var mr = main.getBoundingClientRect();
  var pr = panel.getBoundingClientRect();
  var overlap = Math.max(0, Math.min(mr.right, pr.right) - Math.max(mr.left, pr.left));
  return {
    position: getComputedStyle(panel).position,
    htmlClass: document.documentElement.className,
    cssHref: (document.querySelector('link[href*="chat-panel"]') || {}).href,
    main: { left: Math.round(mr.left), w: Math.round(mr.width), right: Math.round(mr.right) },
    panel: { left: Math.round(pr.left), w: Math.round(pr.width), right: Math.round(pr.right) },
    mountW: mount ? Math.round(mount.getBoundingClientRect().width) : 0,
    overlap: Math.round(overlap),
    sideBySide: pr.width > 80 && pr.left >= mr.right - 2
  };
})();
```

关闭条件（打开态）：

- `position === "relative"`
- `overlap === 0`（允许 1～2px 亚像素）
- `sideBySide === true`
- 主区宽度明显小于视口（约 `viewport - panelWidth`）
- 拖 sash 变宽时 `main.w` 下降
- 关闭后 `mountW < 8`，FAB 可见，主区回到接近视口宽
- 规划模式图表在打开/拖拽后跟左栏重绘（`notifyHostResize` 已派发 `window.resize`）

把上述 JSON 贴回本页「验证记录」后，才能把状态改成 `已验证关闭`。

---

## 7. 若实页测量仍失败：下一刀改哪里

先用 §5 的 computed style 分诊，再改代码。

| 分诊结果 | 下一刀 |
|---|---|
| `position: fixed` | 运行时仍是旧 CSS。查 Electron 缓存、静态资源 URL 是否带 `v=12`、是否还有第二份 `chat-panel.css`。不要给面板加回 `fixed`。 |
| `relative` 但 overlap>0 | flex 壳没成立。查 `display: contents` 是否把 workbench 子节点提升失败；必要时改成 **sash/mount 作为 `.lp-page-shell` 的直接子节点**，不要依赖 `contents`。 |
| overlap=0 但观感仍像覆盖 | 左栏卡片还在 980px 居中。确认 `html.lp-agent-open` 以及 `.lp-page-main .container { max-width: none; width: auto }` 是否生效。 |
| 点 FAB 没反应 | FAB 仍被 0 宽 `overflow:hidden` 的 mount 裁掉（旧 DOM）。当前源码已把 FAB 移出 mount；若实页 FAB 还在 mount 里，模板不是新的。 |

源码入口：

- 模板：[src/main/resources/templates/fragments/agent-panel.html](../../src/main/resources/templates/fragments/agent-panel.html)
- 布局 CSS：[src/main/resources/static/agent/chat-panel.css](../../src/main/resources/static/agent/chat-panel.css)（唯一布局源）
- 开关 / 拖拽：[src/main/resources/static/agent/chat-panel.js](../../src/main/resources/static/agent/chat-panel.js) 的 `setPanelOpen`、`initResize`
- 夹具测试：[src/test/java/org/zhzssp/memorandum/agenteval/unit/AgentPanelLayoutTest.java](../../src/test/java/org/zhzssp/memorandum/agenteval/unit/AgentPanelLayoutTest.java)（只证明 CSS 自身，不证明 Electron）

---

## 8. 教训（下次动手前先读）

1. **浮层加边距 ≠ 分栏。** 居中定宽卡片会把「让出来的空间」变成背景，不是编辑区。
2. **Electron 关窗口不是重启。** `persist:main` + 托盘会让旧 CSS 活过无数次「我已经刷新了」。
3. **夹具绿灯不能关产品 bug。** 内联当前 CSS 的 headless 页，测不到用户磁盘上的那份缓存。
4. **补丁堆叠会让真因消失。** Filter / 内联 `!important` / debug 芯片解决的是「怎么强制新样式」，不是「分栏算法」。`3d02f69` 已经拆掉它们；不要为了「这次一定能看见」再加回来，除非 §6 的实页测量已经做完、且分诊指向缓存而不是布局。

## 验证记录

（空。关闭本题时在这里贴 §6 的 JSON，并注明 Electron 版本 / 是否托盘退出后再开。）
