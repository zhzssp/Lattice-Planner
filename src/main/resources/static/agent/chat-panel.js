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
            li.textContent = text || '';
        stream.appendChild(li); scrollEnd();
    }

    function addToolCard(cid, tool, args) {
        const li = document.createElement('li');
        li.className = 'lp-tool'; li.dataset.cid = cid;


        const pa = document.createElement('pre');
        pa.className = 'lp-tool-args';
        pa.textContent = safeStringify(args);

        const pr = document.createElement('pre');
        pr.className = 'lp-tool-res';
        pr.textContent = '运行中…';

        stream.appendChild(li); scrollEnd();
    }

    function fillToolResult(cid, result) {
        const li = stream.querySelector('li[data-cid="' + cid + '"]');
        if (!li) return;
        const pre = li.querySelector('.lp-tool-res');
        if (pre) pre.textContent = truncate(result, 1500);
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

    /* ------- 启动 ------- */
    connect();
})();
