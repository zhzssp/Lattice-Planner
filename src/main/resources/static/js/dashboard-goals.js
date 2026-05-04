document.addEventListener('DOMContentLoaded', () => {
    const goalsSection = document.getElementById('goalsSection');
    if (!goalsSection) return;

    // 规划模式侧边栏快速定位
    const sidebarLinks = document.querySelectorAll('.plan-sidebar-link');
    const smoothScrollTo = (targetId) => {
        const el = document.getElementById(targetId);
        if (!el) return;
        const top = el.getBoundingClientRect().top + window.scrollY - 16;
        window.scrollTo({ top, behavior: 'smooth' });
    };
    sidebarLinks.forEach(btn => {
        btn.addEventListener('click', () => {
            const targetId = btn.dataset.scrollTarget;
            smoothScrollTo(targetId);
        });
    });

    const modal = document.getElementById('goalModal');
    const openBtn = document.getElementById('openGoalModal');
    const closeBtn = document.getElementById('closeGoalModal');
    const saveBtn = document.getElementById('saveGoalBtn');
    const goalNameInput = document.getElementById('goalName');
    const goalTypeSelect = document.getElementById('goalType');
    const goalList = document.getElementById('goalList');

    // AI 规划弹窗
    const agentPlanModal = document.getElementById('agentPlanModal');
    const openAgentPlanModalBtn = document.getElementById('openAgentPlanModal');
    const closeAgentPlanModalBtn = document.getElementById('closeAgentPlanModal');
    const runAgentPlanBtn = document.getElementById('runAgentPlanBtn');
    const applyAgentPlanBtn = document.getElementById('applyAgentPlanBtn');
    const agentGoalStatementInput = document.getElementById('agentGoalStatement');
    const agentConstraintsInput = document.getElementById('agentConstraints');
    const agentPlanStatus = document.getElementById('agentPlanStatus');
    const agentPlanPreview = document.getElementById('agentPlanPreview');

    // 删除确认弹窗
    const deleteModal = document.getElementById('goalDeleteModal');
    const keepTasksBtn = document.getElementById('keepTasksBtn');
    const deleteTasksBtn = document.getElementById('deleteTasksBtn');
    const cancelDeleteBtn = document.getElementById('cancelDeleteGoalBtn');
    let currentDeleteGoalId = null;

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    let latestDraftPlan = null;

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

    openAgentPlanModalBtn?.addEventListener('click', () => {
        if (!agentPlanModal) return;
        latestDraftPlan = null;
        if (agentGoalStatementInput) agentGoalStatementInput.value = '';
        if (agentConstraintsInput) agentConstraintsInput.value = '';
        if (agentPlanStatus) agentPlanStatus.textContent = '';
        if (agentPlanPreview) agentPlanPreview.innerHTML = '';
        latestDraftPlan = null;
        agentPlanModal.style.display = 'flex';
    });

    closeAgentPlanModalBtn?.addEventListener('click', () => {
        if (agentPlanModal) agentPlanModal.style.display = 'none';
    });

    runAgentPlanBtn?.addEventListener('click', async () => {
        const goalStatement = agentGoalStatementInput?.value?.trim();
        if (!goalStatement) {
            if (agentPlanStatus) agentPlanStatus.textContent = '请先输入目标描述。';
            return;
        }
        const constraints = (agentConstraintsInput?.value || '')
            .split('\n')
            .map(s => s.trim())
            .filter(Boolean);

        latestDraftPlan = null;
        if (agentPlanStatus) agentPlanStatus.textContent = '正在生成，请稍候...';
        if (agentPlanPreview) agentPlanPreview.innerHTML = '';

        try {
            const headers = {
                'Content-Type': 'application/json',
                [csrfHeader]: csrfToken
            };
            const resp = await fetch('/api/agent/planning/draft', {
                method: 'POST',
                headers,
                body: JSON.stringify({ goalStatement, constraints })
            });

            if (!resp.ok) {
                const text = await resp.text();
                throw new Error(`请求失败: ${resp.status} ${text || ''}`);
            }

            const data = await resp.json();

            if (Array.isArray(data.clarifyQuestions) && data.clarifyQuestions.length > 0) {
                latestDraftPlan = null;
                if (agentPlanStatus) agentPlanStatus.textContent = '当前目标信息不足，请补充后重新生成。';
                renderClarifyQuestions(data.clarifyQuestions);
            } else {
                latestDraftPlan = data;
                if (agentPlanStatus) agentPlanStatus.textContent = '已生成可编辑草案。请检查并修改不满意的部分，然后点击“确认创建目标与任务”。';
                renderEditablePlan(data);
            }

            console.log('Agent plan response:', data);
        } catch (e) {
            if (agentPlanStatus) {
                agentPlanStatus.textContent = `生成失败：${e?.message || '未知错误'}`;
            }
        }
    });

    applyAgentPlanBtn?.addEventListener('click', async () => {
        if (!latestDraftPlan) {
            if (agentPlanStatus) agentPlanStatus.textContent = '请先点击“生成规划草案”，并确保返回了可落地的任务树。';
            return;
        }

        if (Array.isArray(latestDraftPlan.clarifyQuestions) && latestDraftPlan.clarifyQuestions.length > 0) {
            if (agentPlanStatus) agentPlanStatus.textContent = '当前仍需补充信息，暂不可创建。请根据提示补充后重新生成。';
            return;
        }

        const editedPlan = collectEditedPlan();
        if (!editedPlan || !Array.isArray(editedPlan.tasks) || editedPlan.tasks.length === 0) {
            if (agentPlanStatus) agentPlanStatus.textContent = '请至少保留一个有效任务。';
            return;
        }

        if (agentPlanStatus) agentPlanStatus.textContent = '正在创建目标与任务...';

        try {
            const headers = {
                'Content-Type': 'application/json',
                [csrfHeader]: csrfToken
            };
            const resp = await fetch('/api/agent/planning/apply', {
                method: 'POST',
                headers,
                body: JSON.stringify({ plan: editedPlan })
            });

            if (!resp.ok) {
                const text = await resp.text();
                throw new Error(`创建失败: ${resp.status} ${text || ''}`);
            }

            const data = await resp.json();
            if (agentPlanStatus) {
                agentPlanStatus.textContent = `创建成功：goalId=${data.goalId}，新增任务 ${data.createdTaskCount} 条。页面即将刷新。`;
            }
            setTimeout(() => location.reload(), 600);
        } catch (e) {
            if (agentPlanStatus) {
                agentPlanStatus.textContent = `创建失败：${e?.message || '未知错误'}`;
            }
        }
    });

    function createTextInput(value, className, placeholder = '') {
        const input = document.createElement('input');
        input.type = 'text';
        input.className = className;
        input.placeholder = placeholder;
        input.value = value || '';
        return input;
    }

    function createTextarea(value, className, placeholder = '') {
        const textarea = document.createElement('textarea');
        textarea.className = className;
        textarea.placeholder = placeholder;
        textarea.value = value || '';
        return textarea;
    }

    function renderClarifyQuestions(questions) {
        if (!agentPlanPreview) return;
        agentPlanPreview.innerHTML = '';
        const box = document.createElement('div');
        box.className = 'agent-preview-card';
        const title = document.createElement('h4');
        title.textContent = '需要补充的信息';
        box.appendChild(title);
        const list = document.createElement('ol');
        questions.forEach(q => {
            const li = document.createElement('li');
            li.textContent = q;
            list.appendChild(li);
        });
        box.appendChild(list);
        agentPlanPreview.appendChild(box);
    }

    function renderEditablePlan(plan) {
        if (!agentPlanPreview) return;
        agentPlanPreview.innerHTML = '';

        const goalCard = document.createElement('div');
        goalCard.className = 'agent-preview-card';
        goalCard.innerHTML = '<h4>目标</h4>';
        goalCard.appendChild(createTextarea(plan.goalStatement, 'agent-edit-goal', '目标名称/描述'));
        agentPlanPreview.appendChild(goalCard);

        const assumptionsCard = document.createElement('div');
        assumptionsCard.className = 'agent-preview-card';
        assumptionsCard.innerHTML = '<h4>假设条件</h4>';
        assumptionsCard.appendChild(createTextarea((plan.assumptions || []).join('\n'), 'agent-edit-assumptions', '每行一条假设'));
        agentPlanPreview.appendChild(assumptionsCard);

        const milestonesCard = document.createElement('div');
        milestonesCard.className = 'agent-preview-card';
        milestonesCard.innerHTML = '<h4>里程碑</h4>';
        (plan.milestones || []).forEach(m => {
            const item = document.createElement('div');
            item.className = 'agent-milestone-item';
            item.dataset.id = m.id || '';
            item.appendChild(createTextInput(m.name, 'agent-edit-milestone-name', '里程碑名称'));
            item.appendChild(createTextInput(m.dueDate, 'agent-edit-milestone-due', '截止日期 YYYY-MM-DD'));
            item.appendChild(createTextInput((m.taskIds || []).join(', '), 'agent-edit-milestone-taskids', '关联任务ID，用逗号分隔'));
            milestonesCard.appendChild(item);
        });
        agentPlanPreview.appendChild(milestonesCard);

        const tasksCard = document.createElement('div');
        tasksCard.className = 'agent-preview-card';
        tasksCard.innerHTML = '<h4>任务拆解（可编辑；取消勾选则不创建）</h4>';
        (plan.tasks || []).forEach(t => {
            const item = document.createElement('div');
            item.className = 'agent-task-item';
            item.dataset.id = t.id || '';

            const header = document.createElement('div');
            header.className = 'agent-task-header';
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'agent-task-enabled';
            checkbox.checked = true;
            const idLabel = document.createElement('span');
            idLabel.textContent = t.id || 'TASK';
            header.appendChild(checkbox);
            header.appendChild(idLabel);
            item.appendChild(header);

            item.appendChild(createTextInput(t.title, 'agent-edit-task-title', '任务标题'));
            item.appendChild(createTextarea(t.description, 'agent-edit-task-desc', '任务描述'));
            item.appendChild(createTextInput(t.priority, 'agent-edit-task-priority', '优先级 P0/P1/P2'));
            item.appendChild(createTextInput(t.estimateHours, 'agent-edit-task-hours', '预估小时数'));
            item.appendChild(createTextInput(t.parentId, 'agent-edit-task-parent', '父任务ID，可为空'));
            item.appendChild(createTextInput((t.dependsOn || []).join(', '), 'agent-edit-task-depends', '依赖任务ID，用逗号分隔'));
            item.appendChild(createTextarea((t.acceptanceCriteria || []).join('\n'), 'agent-edit-task-criteria', '验收标准，每行一条'));
            tasksCard.appendChild(item);
        });
        agentPlanPreview.appendChild(tasksCard);

        const risksCard = document.createElement('div');
        risksCard.className = 'agent-preview-card';
        risksCard.innerHTML = '<h4>风险提示</h4>';
        risksCard.appendChild(createTextarea((plan.risks || []).join('\n'), 'agent-edit-risks', '每行一条风险'));
        agentPlanPreview.appendChild(risksCard);
    }

    function splitLines(value) {
        return (value || '').split('\n').map(s => s.trim()).filter(Boolean);
    }

    function splitCsv(value) {
        return (value || '').split(',').map(s => s.trim()).filter(Boolean);
    }

    function collectEditedPlan() {
        if (!agentPlanPreview || !latestDraftPlan) return latestDraftPlan;

        const goalStatement = agentPlanPreview.querySelector('.agent-edit-goal')?.value?.trim() || latestDraftPlan.goalStatement;
        const assumptions = splitLines(agentPlanPreview.querySelector('.agent-edit-assumptions')?.value || '');
        const risks = splitLines(agentPlanPreview.querySelector('.agent-edit-risks')?.value || '');

        const milestones = Array.from(agentPlanPreview.querySelectorAll('.agent-milestone-item')).map(item => ({
            id: item.dataset.id || '',
            name: item.querySelector('.agent-edit-milestone-name')?.value?.trim() || '',
            dueDate: item.querySelector('.agent-edit-milestone-due')?.value?.trim() || '',
            taskIds: splitCsv(item.querySelector('.agent-edit-milestone-taskids')?.value || '')
        })).filter(m => m.name);

        const tasks = Array.from(agentPlanPreview.querySelectorAll('.agent-task-item')).map(item => {
            const enabled = item.querySelector('.agent-task-enabled')?.checked;
            if (!enabled) return null;
            const title = item.querySelector('.agent-edit-task-title')?.value?.trim() || '';
            if (!title) return null;
            const hoursValue = item.querySelector('.agent-edit-task-hours')?.value;
            const estimateHours = Number.parseInt(hoursValue, 10);
            return {
                id: item.dataset.id || '',
                title,
                description: item.querySelector('.agent-edit-task-desc')?.value?.trim() || '',
                parentId: item.querySelector('.agent-edit-task-parent')?.value?.trim() || null,
                dependsOn: splitCsv(item.querySelector('.agent-edit-task-depends')?.value || ''),
                priority: item.querySelector('.agent-edit-task-priority')?.value?.trim() || 'P1',
                estimateHours: Number.isFinite(estimateHours) && estimateHours > 0 ? estimateHours : null,
                acceptanceCriteria: splitLines(item.querySelector('.agent-edit-task-criteria')?.value || '')
            };
        }).filter(Boolean);

        return {
            ...latestDraftPlan,
            goalStatement,
            assumptions,
            milestones,
            tasks,
            risks,
            clarifyQuestions: []
        };
    }

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
