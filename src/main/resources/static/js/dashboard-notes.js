document.addEventListener('DOMContentLoaded', () => {

    const noteList = document.getElementById('noteList');
    // 笔记模式下方渲染 notesSection，任务模式下不运行
    if (!noteList) return;

    const modal = document.getElementById('noteModal');
    const openBtn = document.getElementById('openNoteModal');
    const closeBtn = document.getElementById('closeNoteModal');
    const saveBtn = document.getElementById('saveNoteBtn');
    const titleInput = document.getElementById('noteTitle');
    const contentInput = document.getElementById('noteContent');

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');

    function fetchNotes() {
        fetch('/note/list')
            .then(r => r.json())
            .then(data => {
                noteList.innerHTML = '';
                data.forEach(n => {
                    const li = document.createElement('li');
                    li.textContent = `${n.title || '(无标题)'} - ${n.content}`;
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
        const body = { title: titleInput.value, content: contentInput.value };
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
                contentInput.value = '';
                modal.style.display = 'none';
                fetchNotes();
            }
        });
    });

    fetchNotes();
});