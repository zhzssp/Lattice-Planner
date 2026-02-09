document.addEventListener('DOMContentLoaded', () => {

    const noteList = document.getElementById('noteList');
    // 笔记模式下方渲染 notesSection，任务模式下不运行
    if (!noteList) return;

    const modal = document.getElementById('noteModal');
    const openBtn = document.getElementById('openNoteModal');
    const closeBtn = document.getElementById('closeNoteModal');
    const saveBtn = document.getElementById('saveNoteBtn');
    const titleInput = document.getElementById('noteTitle');

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');

    // 类型切换
    let currentNoteType = 'SCRATCH';
    const typeButtons = document.querySelectorAll('.note-type-btn');

    typeButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            currentNoteType = btn.dataset.type;
            typeButtons.forEach(b => b.classList.toggle('active', b === btn));
            document.querySelectorAll('.note-panel').forEach(p => p.style.display = 'none');
            const panel = document.getElementById(`notePanel-${currentNoteType}`);
            if (panel) panel.style.display = 'block';
        });
    });

    function fetchNotes() {
        fetch('/note/list')
            .then(r => r.json())
            .then(data => {
                noteList.innerHTML = '';
                data.forEach(n => {
                    const li = document.createElement('li');
                    const typeLabel = n.type || 'SCRATCH';
                    li.textContent = `[${typeLabel}] ${n.title || '(无标题)'}`;
                    noteList.appendChild(li);
                });
            });
    }

    openBtn?.addEventListener('click', () => {
        modal.style.display = 'flex';
    });

    closeBtn?.addEventListener('click', () => {
        modal.style.display = 'none';
    });

    saveBtn?.addEventListener('click', () => {
        let finalTitle = titleInput.value.trim();
        let content = '';

        if (currentNoteType === 'SCRATCH') {
            const c = document.getElementById('scratchContent').value.trim();
            content = c;
            if (!finalTitle && c) finalTitle = c.slice(0, 20);
        } else if (currentNoteType === 'LEARNING') {
            const concept    = document.getElementById('learningConcept').value.trim();
            const definition = document.getElementById('learningDefinition').value.trim();
            const example    = document.getElementById('learningExample').value.trim();
            finalTitle = finalTitle || concept || '学习笔记';
            content =
                (concept    ? `# 概念\n${concept}\n\n` : '') +
                (definition ? `# 定义 / 要点\n${definition}\n\n` : '') +
                (example    ? `# 示例\n${example}\n` : '');
        } else if (currentNoteType === 'PROJECT') {
            const ctx    = document.getElementById('projectContext').value.trim();
            const todos  = document.getElementById('projectTodos').value.trim();
            const issues = document.getElementById('projectIssues').value.trim();
            finalTitle = finalTitle || '项目笔记';
            content =
                (ctx    ? `# 背景 / 决策\n${ctx}\n\n` : '') +
                (todos  ? `# TODO\n${todos}\n\n` : '') +
                (issues ? `# 问题 / 风险\n${issues}\n` : '');
        } else if (currentNoteType === 'RETROSPECTIVE') {
            const what    = document.getElementById('retroWhat').value.trim();
            const why     = document.getElementById('retroWhy').value.trim();
            const lessons = document.getElementById('retroLessons').value.trim();
            finalTitle = finalTitle || '复盘笔记';
            content =
                (what    ? `# 发生了什么\n${what}\n\n` : '') +
                (why     ? `# 原因分析\n${why}\n\n` : '') +
                (lessons ? `# 教训 / 改进\n${lessons}\n` : '');
        }

        const body = { title: finalTitle, content, type: currentNoteType };

        fetch('/note/add', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                [csrfHeader]: csrfToken
            },
            body: JSON.stringify(body)
        }).then(r => {
            if (r.ok) {
                titleInput.value = '';
                modal.style.display = 'none';
                fetchNotes();
            }
        });
    });

    fetchNotes();
});