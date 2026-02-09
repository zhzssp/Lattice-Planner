document.addEventListener('DOMContentLoaded', () => {
    const goalsSection = document.getElementById('goalsSection');
    if (!goalsSection) return;

    const modal = document.getElementById('goalModal');
    const openBtn = document.getElementById('openGoalModal');
    const closeBtn = document.getElementById('closeGoalModal');
    const saveBtn = document.getElementById('saveGoalBtn');
    const goalNameInput = document.getElementById('goalName');
    const goalTypeSelect = document.getElementById('goalType');
    const goalList = document.getElementById('goalList');

    // 删除确认弹窗
    const deleteModal = document.getElementById('goalDeleteModal');
    const keepTasksBtn = document.getElementById('keepTasksBtn');
    const deleteTasksBtn = document.getElementById('deleteTasksBtn');
    const cancelDeleteBtn = document.getElementById('cancelDeleteGoalBtn');
    let currentDeleteGoalId = null;

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');

    function archiveGoal(goalId) {
        const body = new URLSearchParams({ _csrf: csrfToken });
        fetch(`/goal/archive/${goalId}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                [csrfHeader]: csrfToken
            },
            body: body
        }).then(r => {
            if (r.ok) location.reload();
        });
    }

    function deleteGoal(goalId) {
        currentDeleteGoalId = goalId;
        if (deleteModal) {
            deleteModal.style.display = 'flex';
        }
    }

    openBtn?.addEventListener('click', () => {
        goalNameInput.value = '';
        goalTypeSelect.value = '';
        modal.style.display = 'flex';
    });

    closeBtn?.addEventListener('click', () => {
        modal.style.display = 'none';
    });

    saveBtn?.addEventListener('click', () => {
        const name = goalNameInput.value?.trim();
        if (!name) return;
        const goalType = goalTypeSelect.value || '';
        const formData = new URLSearchParams();
        formData.append('name', name);
        if (goalType) formData.append('goalType', goalType);
        formData.append('_csrf', csrfToken);

        fetch('/goal/add', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                [csrfHeader]: csrfToken
            },
            body: formData
        }).then(r => {
            if (r.ok) {
                modal.style.display = 'none';
                location.reload();
            }
        });
    });

    goalList?.querySelectorAll('.goal-archive-btn').forEach(btn => {
        btn.addEventListener('click', () => archiveGoal(btn.dataset.goalId));
    });

    goalList?.querySelectorAll('.goal-delete-btn').forEach(btn => {
        btn.addEventListener('click', () => deleteGoal(btn.dataset.goalId));
    });

    // 删除确认弹窗事件绑定
    keepTasksBtn?.addEventListener('click', () => {
        if (!currentDeleteGoalId) return;
        const body = new URLSearchParams({ _csrf: csrfToken, mode: 'keepTasks' });
        fetch(`/goal/delete/${currentDeleteGoalId}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                [csrfHeader]: csrfToken
            },
            body
        }).then(r => {
            if (r.ok) location.reload();
        });
    });

    deleteTasksBtn?.addEventListener('click', () => {
        if (!currentDeleteGoalId) return;
        const body = new URLSearchParams({ _csrf: csrfToken, mode: 'deleteTasks' });
        fetch(`/goal/delete/${currentDeleteGoalId}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                [csrfHeader]: csrfToken
            },
            body
        }).then(r => {
            if (r.ok) location.reload();
        });
    });

    const closeDeleteModal = () => {
        if (deleteModal) deleteModal.style.display = 'none';
        currentDeleteGoalId = null;
    };

    cancelDeleteBtn?.addEventListener('click', closeDeleteModal);
    // 点击遮罩关闭
    deleteModal?.addEventListener('click', (e) => {
        if (e.target === deleteModal) closeDeleteModal();
    });
});
