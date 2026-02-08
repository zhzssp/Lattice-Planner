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
});
