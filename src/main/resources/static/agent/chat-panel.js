/**
 * Lattice-Agent 对话面板前端逻辑。
 * 统一通过 WebSocket /ws/agent/{sessionId} 与后端交互。
 * 在 Electron 客户端环境下，window.lattice.localBridge 暴露本地工具桥。
 */
(function () {
    'use strict';

    function uuid() {
        if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
        return Date.now().toString(36) + '-' + Math.random().toString(16).slice(2, 10);
    }

    const sid = uuid();
    const stream = document.getElementById('lp-agent-stream');
    const input = document.getElementById('lp-agent-input');
    const sendBtn = document.getElementById('lp-agent-send');
    const modeSel = document.getElementById('lp-agent-mode');
    const fab = document.getElementById('lp-agent-fab');
    const panel = document.getElementById('lp-agent-panel');
    const closeBtn = document.getElementById('lp-agent-close');
    const status = document.getElementById('lp-agent-status');

    if (!stream || !input || !sendBtn || !panel) return;

    closeBtn.onclick = () => panel.classList.remove('open');
    fab.onclick = () => panel.classList.add('open');

    /* ------- 面板宽度：拖拽调整 + 记忆 ------- */
    (function initResize() {
        const resizer = document.getElementById('lp-agent-resizer');
        if (!resizer) return;
        const KEY = 'lp-agent-width';
        const MIN = 320;
        const maxWidth = () => Math.floor(window.innerWidth * 0.96);

        function applyWidth(px) {
            const w = Math.max(MIN, Math.min(px, maxWidth()));
            panel.style.setProperty('--lp-agent-width', w + 'px');
            return w;
        }
        // 恢复上次记忆的宽度
        const saved = parseInt(localStorage.getItem(KEY), 10);
        if (!isNaN(saved)) applyWidth(saved);

        let dragging = false;
        function onMove(e) {
            if (!dragging) return;
            const clientX = e.touches ? e.touches[0].clientX : e.clientX;
            // 面板右贴边：宽度 = 视口右边到指针的距离
            applyWidth(window.innerWidth - clientX);
            e.preventDefault();
        }
        function onUp() {
            if (!dragging) return;
            dragging = false;
            resizer.classList.remove('dragging');
            panel.classList.remove('resizing');
            document.body.classList.remove('lp-agent-resizing');
            const cur = parseInt(getComputedStyle(panel).width, 10);
            if (!isNaN(cur)) localStorage.setItem(KEY, String(cur));
            window.removeEventListener('mousemove', onMove);
            window.removeEventListener('mouseup', onUp);
            window.removeEventListener('touchmove', onMove);
            window.removeEventListener('touchend', onUp);
        }
        function onDown(e) {
            dragging = true;
            resizer.classList.add('dragging');
            panel.classList.add('resizing');
            document.body.classList.add('lp-agent-resizing');
            window.addEventListener('mousemove', onMove);
            window.addEventListener('mouseup', onUp);
            window.addEventListener('touchmove', onMove, { passive: false });
            window.addEventListener('touchend', onUp);
            e.preventDefault();
        }
        resizer.addEventListener('mousedown', onDown);
        resizer.addEventListener('touchstart', onDown, { passive: false });
        // 视口变窄时夹紧，避免超出
        window.addEventListener('resize', () => applyWidth(parseInt(getComputedStyle(panel).width, 10) || MIN));
    })();

    /* ------- WebSocket ------- */
    let ws = null;
    let wsRetry = 0;

    function setStatus(text, cls) {
        status.textContent = text;
        status.className = 'lp-agent-status' + (cls ? ' ' + cls : '');
    }

    function connect() {
        const url = (location.protocol === 'https:' ? 'wss:' : 'ws:') +
                    '//' + location.host + '/ws/agent/' + sid;
        ws = new WebSocket(url);
        ws.onopen = () => {
            wsRetry = 0;
            setStatus('已连接', 'online');
        };
        ws.onclose = () => {
            setStatus('连接已断开', 'error');
            sendBtn.disabled = false;
            // 简单重连（最多 5 次）
            if (wsRetry < 5) {
                wsRetry++;
                setTimeout(connect, 1500 * wsRetry);
            }
        };
        ws.onerror = () => setStatus('连接错误', 'error');
        ws.onmessage = (ev) => {
            let m;
            try { m = JSON.parse(ev.data); } catch (e) { return; }
            handleServerMessage(m);
        };
    }

    function handleServerMessage(m) {
        switch (m.msgType) {
            case 'assistant':  addBubble('assistant', m.text); break;
            case 'toolStart':  addToolCard(m.callId, m.tool, m.args); break;
            case 'toolResult': fillToolResult(m.callId, m.result); break;
            case 'subagentStart': addSubAgentCard(m.subId, m.roleLabel || m.role, m.instruction); break;
            case 'subagentEnd':   fillSubAgentResult(m.subId, m); break;
            case 'localCall':  handleLocalCall(m); break;
            case 'confirmReq': addConfirm(m.reqId, m.summary); break;
            case 'error':      addBubble('system', '错误：' + m.message); break;
            case 'done':       sendBtn.disabled = false; break;
        }
    }

    /* ------- 发送 ------- */
    function sendChat() {
        const text = input.value.trim();
        if (!text) return;
        if (!ws || ws.readyState !== WebSocket.OPEN) {
            addBubble('system', '尚未连接到服务端，请稍候');
            return;
        }
        addBubble('user', text);
        ws.send(JSON.stringify({
            msgType: 'chat',
            text: text,
            mode: modeSel.value
        }));
        input.value = '';
        sendBtn.disabled = true;
    }
    sendBtn.onclick = sendChat;
    input.addEventListener('keydown', (e) => {
        if (e.ctrlKey && e.key === 'Enter') sendChat();
    });

    /* ------- 反向调用：本地工具（仅 Electron 可用） ------- */
    async function handleLocalCall(m) {
        const bridge = window.lattice && window.lattice.localBridge;
        if (!bridge) {
            ws.send(JSON.stringify({
                msgType: 'localResult',
                reqId: m.reqId,
                result: { error: 'BRIDGE_NOT_AVAILABLE' }
            }));
            return;
        }
        const fnMap = { list_dir: 'listDir', read_file: 'readFile', read_pdf: 'readPdf' };
        const fnName = fnMap[m.tool];
        const fn = fnName ? bridge[fnName] : null;
        if (!fn) {
            ws.send(JSON.stringify({
                msgType: 'localResult',
                reqId: m.reqId,
                result: { error: 'UNSUPPORTED_LOCAL_TOOL', tool: m.tool }
            }));
            return;
        }
        try {
            const result = await fn(m.args && m.args.path);
            // 包一层让 Java 侧统一处理
            const wrapped = (m.tool === 'list_dir')
                ? { entries: result }
                : (typeof result === 'string' ? { content: result } : result);
            ws.send(JSON.stringify({
                msgType: 'localResult',
                reqId: m.reqId,
                result: wrapped
            }));
        } catch (e) {
            ws.send(JSON.stringify({
                msgType: 'localResult',
                reqId: m.reqId,
                result: { error: (e && e.message) || String(e) }
            }));
        }
    }

    /* ------- 用户确认 ------- */
    function addConfirm(reqId, summary) {
        const li = document.createElement('li');
        li.className = 'lp-confirm';
        const text = document.createElement('div');
        text.className = 'lp-confirm-text';
        text.textContent = summary;
        li.appendChild(text);

        const okBtn = document.createElement('button'); okBtn.dataset.ok = '1'; okBtn.textContent = '允许';
        const noBtn = document.createElement('button'); noBtn.dataset.ok = '0'; noBtn.textContent = '拒绝';
        const replyAndDisable = (approved) => {
            ws.send(JSON.stringify({ msgType: 'confirmReply', reqId, approved }));
            okBtn.disabled = true; noBtn.disabled = true;
        };
        okBtn.onclick = () => replyAndDisable(true);
        noBtn.onclick = () => replyAndDisable(false);
        li.appendChild(okBtn); li.appendChild(noBtn);
        stream.appendChild(li); scrollEnd();
    }

    /* ------- UI Helpers ------- */
    function addBubble(role, text) {
        const li = document.createElement('li');
        li.className = 'lp-bubble lp-' + role;
        if (role === 'assistant') {
            // 兜底再剥一次：万一后端没清干净，把 ```json {tool:...} ``` 与裸 tool-call JSON 也吃掉
            const cleaned = stripToolJson(text || '');
            li.innerHTML = renderMarkdown(cleaned);
        } else {
            li.textContent = text || '';
        }
        stream.appendChild(li); scrollEnd();
    }

    function addToolCard(cid, tool, args) {
        const li = document.createElement('li');
        li.className = 'lp-tool'; li.dataset.cid = cid;

        // 折叠：默认收起，仅露工具名
        const det = document.createElement('details');

        const sum = document.createElement('summary');
        sum.className = 'lp-tool-h';
        const dot = document.createElement('span');
        dot.className = 'lp-tool-dot';
        dot.textContent = '⚙';
        sum.appendChild(dot);
        sum.appendChild(document.createTextNode(' ' + tool));
        const tag = document.createElement('span');
        tag.className = 'lp-tool-status running';
        tag.textContent = '运行中';
        sum.appendChild(tag);
        det.appendChild(sum);

        const argsBox = document.createElement('div');
        argsBox.className = 'lp-tool-section';
        argsBox.innerHTML = '<div class="lp-tool-label">参数</div>';
        const pa = document.createElement('pre');
        pa.className = 'lp-tool-args';
        pa.textContent = safeStringify(args);
        argsBox.appendChild(pa);
        det.appendChild(argsBox);

        const resBox = document.createElement('div');
        resBox.className = 'lp-tool-section';
        resBox.innerHTML = '<div class="lp-tool-label">结果</div>';
        const pr = document.createElement('pre');
        pr.className = 'lp-tool-res';
        pr.textContent = '运行中…';
        resBox.appendChild(pr);
        det.appendChild(resBox);

        li.appendChild(det);
        stream.appendChild(li); scrollEnd();
    }

    function fillToolResult(cid, result) {
        const li = stream.querySelector('li[data-cid="' + cid + '"]');
        if (!li) return;
        const pre = li.querySelector('.lp-tool-res');
        if (pre) pre.textContent = truncate(result, 1500);
        const tag = li.querySelector('.lp-tool-status');
        if (tag) {
            tag.classList.remove('running');
            const isError = /"error"\s*:/.test(result || '');
            tag.classList.add(isError ? 'fail' : 'done');
            tag.textContent = isError ? '失败' : '完成';
        }
    }

    /* ------- 子代理卡片（多代理可视化） ------- */
    function addSubAgentCard(subId, roleLabel, instruction) {
        const li = document.createElement('li');
        li.className = 'lp-subagent'; li.dataset.subid = subId;

        const det = document.createElement('details');
        det.open = true; // 默认展开，运行中可见；完成后用户可自行折叠

        const sum = document.createElement('summary');
        sum.className = 'lp-sub-h';
        const icon = document.createElement('span');
        icon.className = 'lp-sub-icon';
        icon.textContent = '🤖';
        sum.appendChild(icon);
        sum.appendChild(document.createTextNode(' 子代理 · ' + (roleLabel || '')));
        const tag = document.createElement('span');
        tag.className = 'lp-sub-status running';
        tag.textContent = '推理中';
        sum.appendChild(tag);
        det.appendChild(sum);

        // 委派任务
        const insBox = document.createElement('div');
        insBox.className = 'lp-sub-section';
        insBox.innerHTML = '<div class="lp-sub-label">委派任务</div>';
        const insP = document.createElement('div');
        insP.className = 'lp-sub-ins';
        insP.textContent = instruction || '';
        insBox.appendChild(insP);
        det.appendChild(insBox);

        // 内部工具链（结束时回填）
        const toolBox = document.createElement('div');
        toolBox.className = 'lp-sub-section lp-sub-tools-box';
        toolBox.style.display = 'none';
        toolBox.innerHTML = '<div class="lp-sub-label">内部工具链</div><div class="lp-sub-tools"></div>';
        det.appendChild(toolBox);

        // 结论
        const resBox = document.createElement('div');
        resBox.className = 'lp-sub-section';
        resBox.innerHTML = '<div class="lp-sub-label">结论</div>';
        const resBody = document.createElement('div');
        resBody.className = 'lp-sub-res';
        resBody.textContent = '独立上下文推理中…';
        resBox.appendChild(resBody);
        det.appendChild(resBox);

        li.appendChild(det);
        stream.appendChild(li); scrollEnd();
    }

    function fillSubAgentResult(subId, m) {
        const li = stream.querySelector('li[data-subid="' + subId + '"]');
        if (!li) return;

        const tag = li.querySelector('.lp-sub-status');
        if (tag) {
            tag.classList.remove('running');
            tag.classList.add(m.truncated ? 'warn' : 'done');
            const stepTxt = (m.steps != null ? ' · ' + m.steps + ' 步' : '');
            tag.textContent = (m.truncated ? '已截断' : '完成') + stepTxt;
        }

        const tools = Array.isArray(m.toolsUsed) ? m.toolsUsed : [];
        if (tools.length) {
            const box = li.querySelector('.lp-sub-tools-box');
            const wrap = li.querySelector('.lp-sub-tools');
            if (box) box.style.display = '';
            if (wrap) {
                wrap.innerHTML = '';
                tools.forEach((t) => {
                    const chip = document.createElement('span');
                    chip.className = 'lp-sub-tool-chip';
                    chip.textContent = t;
                    wrap.appendChild(chip);
                });
            }
        }

        const body = li.querySelector('.lp-sub-res');
        if (body) body.innerHTML = renderMarkdown(stripToolJson(m.finalText || ''));
    }

    function safeStringify(v) {
        if (v == null) return '';
        try { return JSON.stringify(v, null, 2); } catch (e) { return String(v); }
    }
    function truncate(s, n) {
        s = s == null ? '' : String(s);
        return s.length > n ? s.slice(0, n) + '…' : s;
    }
    function scrollEnd() { stream.scrollTop = stream.scrollHeight; }

    /* ------- 设置面板：auto-approve 工具白名单 ------- */
    const settingsBtn = document.getElementById('lp-agent-settings-btn');
    const settingsPanel = document.getElementById('lp-agent-settings');
    const settingsClose = document.getElementById('lp-settings-close');
    const settingsList = document.getElementById('lp-settings-tools-list');
    const settingsSave = document.getElementById('lp-settings-save');
    const settingsStatus = document.getElementById('lp-settings-status');

    function openSettings() {
        settingsPanel.classList.add('open');
        loadToolSettings();
    }
    function closeSettings() {
        settingsPanel.classList.remove('open');
        settingsStatus.textContent = '';
    }
    if (settingsBtn) settingsBtn.onclick = openSettings;
    if (settingsClose) settingsClose.onclick = closeSettings;

    function loadToolSettings() {
        if (!settingsList) return;
        settingsList.innerHTML = '<div class="lp-settings-loading">加载中…</div>';
        if (settingsSave) settingsSave.disabled = true;
        settingsStatus.textContent = '';
        fetch('/agent/settings/tools')
            .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
            .then(data => {
                const tools = data.confirmableTools || [];
                const approved = new Set(data.autoApproved || []);
                if (tools.length === 0) {
                    settingsList.innerHTML = '<div class="lp-settings-empty">当前无可配置的工具</div>';
                } else {
                    settingsList.innerHTML = '';
                    tools.forEach(t => {
                        const label = document.createElement('label');
                        label.className = 'lp-settings-tool-item';
                        const cb = document.createElement('input');
                        cb.type = 'checkbox';
                        cb.value = t.name;
                        if (approved.has(t.name)) cb.checked = true;
                        label.appendChild(cb);
                        const nameSpan = document.createElement('span');
                        nameSpan.className = 'lp-settings-tool-name';
                        nameSpan.textContent = t.name;
                        label.appendChild(nameSpan);
                        const descSpan = document.createElement('span');
                        descSpan.className = 'lp-settings-tool-desc';
                        descSpan.textContent = t.description || '';
                        label.appendChild(descSpan);
                        settingsList.appendChild(label);
                    });
                }
                if (settingsSave) settingsSave.disabled = false;
            })
            .catch(err => {
                settingsList.innerHTML = '<div class="lp-settings-error">加载失败：' + err.message + '</div>';
                if (settingsSave) settingsSave.disabled = false;
            });
    }

    if (settingsSave) {
        settingsSave.onclick = function () {
            const checks = settingsList ? settingsList.querySelectorAll('input[type="checkbox"]:checked') : [];
            const tools = Array.from(checks).map(cb => cb.value);
            settingsSave.disabled = true;
            settingsStatus.textContent = '保存中…';
            settingsStatus.className = 'lp-settings-status saving';
            fetch('/agent/settings/auto-approve', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ tools: tools })
            })
                .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(() => {
                    settingsStatus.textContent = '已保存';
                    settingsStatus.className = 'lp-settings-status ok';
                    settingsSave.disabled = false;
                    setTimeout(() => { settingsStatus.textContent = ''; settingsStatus.className = 'lp-settings-status'; }, 2000);
                })
                .catch(err => {
                    settingsStatus.textContent = '保存失败：' + err.message;
                    settingsStatus.className = 'lp-settings-status error';
                    settingsSave.disabled = false;
                });
        };
    }

    /* ------- 终态文本前端兜底清洗：去掉 tool-call JSON ------- */
    function stripToolJson(text) {
        if (!text) return '';
        let s = text.replace(/<think>[\s\S]*?<\/think>/gi, '');
        // 围栏块内的 tool-call
        s = s.replace(/```(?:json)?\s*([\s\S]*?)```/gi, (whole, inner) => {
            const m = inner.match(/\{[\s\S]*\}/);
            if (m) {
                try {
                    const obj = JSON.parse(m[0]);
                    if (obj && typeof obj.tool === 'string') return '';
                } catch (e) { /* 普通代码块，保留 */ }
            }
            return whole;
        });
        // 独立成段的 tool-call JSON
        s = s.replace(/^\s*\{[\s\S]*?"tool"\s*:\s*"[^"]+"[\s\S]*?\}\s*$/gm, '');
        return s.replace(/\n{3,}/g, '\n\n').trim();
    }

    /* ------- 极简 Markdown 渲染（足够展示 Agent 输出） -------
     * 支持：
     *   - ```code blocks```  (多行代码，带语言提示)
     *   - `inline code`
     *   - **bold** / __bold__
     *   - *italic* / _italic_
     *   - # / ## / ### 标题
     *   - 无序列表  - / *
     *   - 有序列表  1. 2. ...
     *   - > 引用
     *   - [text](url) 链接（仅 http/https）
     *   - 段落 (\n\n)
     * 全程先 escapeHtml，杜绝 XSS。
     */
    function escapeHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function renderMarkdown(src) {
        if (!src) return '';
        // 1) 抠出代码围栏，先用占位符占位，避免内部内容被后续规则破坏
        const codeBlocks = [];
        let s = src.replace(/```([a-zA-Z0-9_+-]*)\n?([\s\S]*?)```/g, (m, lang, code) => {
            const idx = codeBlocks.length;
            codeBlocks.push(
                '<pre class="lp-md-pre"><code'
                + (lang ? ' class="lang-' + escapeHtml(lang) + '"' : '')
                + '>' + escapeHtml(code.replace(/\n$/, '')) + '</code></pre>'
            );
            return '\u0000CB' + idx + '\u0000';
        });

        // 2) 全文转义
        s = escapeHtml(s);

        // 3) 行内代码（重新 escapeHtml 后就再不用考虑 < > 了）
        s = s.replace(/`([^`\n]+)`/g, (_, c) => '<code class="lp-md-code">' + c + '</code>');

        // 4) 粗体 / 斜体
        s = s.replace(/\*\*([^\*\n]+)\*\*/g, '<strong>$1</strong>');
        s = s.replace(/__([^_\n]+)__/g,       '<strong>$1</strong>');
        s = s.replace(/(^|[^\*])\*([^\*\n]+)\*(?!\*)/g, '$1<em>$2</em>');
        s = s.replace(/(^|[^_])_([^_\n]+)_(?!_)/g,     '$1<em>$2</em>');

        // 5) 安全链接
        s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
            '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');

        // 6) 行级处理：标题、列表、引用、段落
        const lines = s.split(/\n/);
        const out = [];
        let listType = null; // 'ul' | 'ol' | null
        let inQuote = false;

        function closeList() {
            if (listType) { out.push('</' + listType + '>'); listType = null; }
        }
        function closeQuote() {
            if (inQuote) { out.push('</blockquote>'); inQuote = false; }
        }

        // GFM 表格分隔行：|---|:--:|--:| 这类（至少一个 -，可带对齐冒号）
        function isTableSep(l) {
            return /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$/.test(l) && l.indexOf('-') !== -1;
        }
        // 拆分一行单元格：去掉首尾 |，按未转义的 | 切分，再还原 \| 为字面竖线
        function splitRow(l) {
            let t = l.trim();
            if (t.startsWith('|')) t = t.slice(1);
            if (t.endsWith('|')) t = t.slice(0, -1);
            return t.split(/(?<!\\)\|/).map(c => c.trim().replace(/\\\|/g, '|'));
        }

        for (let i = 0; i < lines.length; i++) {
            let line = lines[i];

            // 占位符独占一行 -> 直接放回，不再当文本处理
            if (/^\u0000CB\d+\u0000$/.test(line)) {
                closeList(); closeQuote();
                out.push(line);
                continue;
            }

            // 表格：当前行含 |，且下一行是分隔行 -> 解析为 <table>
            if (line.indexOf('|') !== -1 && i + 1 < lines.length && isTableSep(lines[i + 1])) {
                closeList(); closeQuote();
                const header = splitRow(line);
                const aligns = splitRow(lines[i + 1]).map(c => {
                    const l = c.startsWith(':'), r = c.endsWith(':');
                    if (l && r) return 'center';
                    if (r) return 'right';
                    if (l) return 'left';
                    return '';
                });
                i += 1; // 跳过分隔行
                const rows = [];
                // 连续的数据行：含 | 且非空、非代码占位符
                while (i + 1 < lines.length
                       && lines[i + 1].indexOf('|') !== -1
                       && lines[i + 1].trim() !== ''
                       && !/^\u0000CB\d+\u0000$/.test(lines[i + 1])) {
                    i += 1;
                    rows.push(splitRow(lines[i]));
                }
                const alignAttr = (idx) => aligns[idx] ? ' style="text-align:' + aligns[idx] + '"' : '';
                let tb = '<div class="lp-md-table-wrap"><table class="lp-md-table"><thead><tr>';
                header.forEach((h, idx) => { tb += '<th' + alignAttr(idx) + '>' + h + '</th>'; });
                tb += '</tr></thead><tbody>';
                rows.forEach(r => {
                    tb += '<tr>';
                    for (let c = 0; c < header.length; c++) {
                        tb += '<td' + alignAttr(c) + '>' + (r[c] != null ? r[c] : '') + '</td>';
                    }
                    tb += '</tr>';
                });
                tb += '</tbody></table></div>';
                out.push(tb);
                continue;
            }

            // 标题
            const h = line.match(/^(#{1,6})\s+(.*)$/);
            if (h) {
                closeList(); closeQuote();
                const level = h[1].length;
                out.push('<h' + level + ' class="lp-md-h">' + h[2] + '</h' + level + '>');
                continue;
            }

            // 引用
            const q = line.match(/^>\s?(.*)$/);
            if (q) {
                closeList();
                if (!inQuote) { out.push('<blockquote class="lp-md-quote">'); inQuote = true; }
                out.push(q[1] + '<br>');
                continue;
            } else {
                closeQuote();
            }

            // 无序列表
            const ul = line.match(/^\s*[-*]\s+(.*)$/);
            if (ul) {
                if (listType !== 'ul') { closeList(); out.push('<ul class="lp-md-list">'); listType = 'ul'; }
                out.push('<li>' + ul[1] + '</li>');
                continue;
            }
            // 有序列表
            const ol = line.match(/^\s*\d+\.\s+(.*)$/);
            if (ol) {
                if (listType !== 'ol') { closeList(); out.push('<ol class="lp-md-list">'); listType = 'ol'; }
                out.push('<li>' + ol[1] + '</li>');
                continue;
            }
            closeList();

            // 空行 -> 段落分隔
            if (line.trim() === '') {
                out.push('');
                continue;
            }
            out.push(line + '<br>');
        }
        closeList(); closeQuote();

        // 合并连续段落，去掉过多 <br>
        let html = out.join('\n')
            .replace(/(?:<br>\s*\n?){2,}/g, '<br>')
            .replace(/<br>\s*(<\/(?:li|ul|ol|blockquote|h[1-6]|table)>)/g, '$1')
            .replace(/(<(?:ul|ol|blockquote|h[1-6])[^>]*>)\s*<br>/g, '$1')
            // 表格容器前后的多余 <br> 一并清掉，避免表格上下出现空行
            .replace(/<br>\s*(<div class="lp-md-table-wrap">)/g, '$1')
            .replace(/(<\/table><\/div>)\s*<br>/g, '$1');

        // 7) 把代码占位符还原
        html = html.replace(/\u0000CB(\d+)\u0000/g, (_, idx) => codeBlocks[Number(idx)] || '');
        return html;
    }

    /* ------- 启动 ------- */
    connect();
})();
