document.addEventListener('DOMContentLoaded', function () {
    const contextMenu = document.getElementById('contextMenu');
    const memoItems = document.querySelectorAll('.memo-item');
    let currentMemoId = null;

    // 为每个memo项添加右键事件监听器
    memoItems.forEach(item => {
        item.addEventListener('contextmenu', function (e) {
            e.preventDefault();
            currentMemoId = this.getAttribute('data-memo-id');

            // 显示右键菜单
            contextMenu.style.display = 'block';
            contextMenu.style.left = e.pageX + 'px';
            contextMenu.style.top = e.pageY + 'px';
        });
    });

    // 完成按钮事件
    document.getElementById('completeBtn').addEventListener('click', function () {
        if (currentMemoId) {
            updateTaskStatus(currentMemoId, 'complete');
        }
        hideContextMenu();
    });

    // 搁置按钮事件
    document.getElementById('shelveBtn').addEventListener('click', function () {
        if (currentMemoId) {
            updateTaskStatus(currentMemoId, 'shelve');
        }
        hideContextMenu();
    });

    // 删除按钮事件
    document.getElementById('deleteBtn').addEventListener('click', function () {
        if (currentMemoId) {
            deleteMemo(currentMemoId);
        }
        hideContextMenu();
    });

    // 取消按钮事件
    document.getElementById('cancelBtn').addEventListener('click', function () {
        hideContextMenu();
    });

    // 点击其他地方隐藏菜单
    document.addEventListener('click', function (e) {
        if (!contextMenu.contains(e.target)) {
            hideContextMenu();
        }
    });

    // 隐藏右键菜单
    function hideContextMenu() {
        contextMenu.style.display = 'none';
        currentMemoId = null;
    }

    // 更新任务状态（完成/搁置）
    function updateTaskStatus(taskId, action) {
        const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') ||
            document.querySelector('input[name="_csrf"]')?.value;
        const url = action === 'complete' ? `/memo/complete/${taskId}` : `/memo/shelve/${taskId}`;

        fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': csrfToken
            }
        })
            .then(response => response.ok ? response.text() : Promise.reject(new Error(response.statusText)))
            .then(data => {
                if (data === 'success') {
                    location.reload();
                } else {
                    alert('操作失败: ' + data);
                }
            })
            .catch(err => {
                console.error('Error:', err);
                alert('操作失败: ' + err.message);
            });
    }

    // 删除任务
    function deleteMemo(memoId) {
        // 获取CSRF token
        const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') ||
            document.querySelector('input[name="_csrf"]')?.value;

        fetch(`/memo/delete/${memoId}`, {
            method: 'DELETE',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': csrfToken
            }
        })
            .then(response => {
                if (response.ok) {
                    return response.text();
                } else {
                    throw new Error('HTTP ' + response.status + ': ' + response.statusText);
                }
            })
            .then(data => {
                if (data === 'success') {
                    // 删除成功后重新加载页面
                    location.reload();
                } else {
                    alert('删除失败: ' + data);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('删除失败: ' + error.message);
            });
    }

    // 目标与任务树：右上角按键控制显示/隐藏（默认隐藏），首次显示时拉取数据并绘制节点与边
    const toggleGoalTreeBtn = document.getElementById('toggleGoalTreeBtn');
    const goalTaskTreeSection = document.getElementById('goalTaskTreeSection');
    if (toggleGoalTreeBtn && goalTaskTreeSection) {
        toggleGoalTreeBtn.addEventListener('click', function () {
            const hidden = goalTaskTreeSection.style.display === 'none';
            goalTaskTreeSection.style.display = hidden ? 'block' : 'none';
            goalTaskTreeSection.setAttribute('aria-hidden', hidden ? 'false' : 'true');
            if (hidden && typeof window.initGoalTreeViz === 'function') {
                window.initGoalTreeViz();
            }
        });
    }
});