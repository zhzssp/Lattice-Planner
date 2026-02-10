document.addEventListener('DOMContentLoaded', function () {
    const chartCanvas = document.getElementById('scoreChart');
    if (!chartCanvas) {
        return; // 仅在规划模式下存在
    }

    const startInput = document.getElementById('scoreStart');
    const endInput = document.getElementById('scoreEnd');
    const refreshBtn = document.getElementById('scoreRefreshBtn');
    const summaryBtn = document.getElementById('scoreSummaryBtn');
    const summaryBox = document.getElementById('scoreSummary');

    let scoreChart = null;

    function formatDate(d) {
        const y = d.getFullYear();
        const m = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        return `${y}-${m}-${day}`;
    }

    // 默认展示近 14 天
    const today = new Date();
    const start = new Date();
    start.setDate(today.getDate() - 13);

    if (!startInput.value) {
        startInput.value = formatDate(start);
    }
    if (!endInput.value) {
        endInput.value = formatDate(today);
    }

    function getCsrfToken() {
        const meta = document.querySelector('meta[name=\"_csrf\"]');
        if (meta) return meta.getAttribute('content');
        const input = document.querySelector('input[name=\"_csrf\"]');
        return input ? input.value : null;
    }

    function loadScores() {
        const startVal = startInput.value;
        const endVal = endInput.value;
        if (!startVal || !endVal) return;

        const csrfToken = getCsrfToken();

        fetch(`/insight/score?start=${startVal}&end=${endVal}`, {
            method: 'GET',
            headers: csrfToken ? { 'X-CSRF-TOKEN': csrfToken } : {}
        })
            .then(resp => resp.ok ? resp.json() : Promise.reject(new Error(resp.statusText)))
            .then(data => {
                const labels = data.map(d => d.date);
                const scores = data.map(d => d.totalScore);

                if (scoreChart) {
                    scoreChart.destroy();
                }

                scoreChart = new Chart(chartCanvas.getContext('2d'), {
                    type: 'line',
                    data: {
                        labels: labels,
                        datasets: [{
                            label: '每日规划得分',
                            data: scores,
                            fill: false,
                            borderColor: '#4a90e2',
                            tension: 0.2,
                            pointRadius: 3
                        }]
                    },
                    options: {
                        responsive: true,
                        scales: {
                            y: {
                                beginAtZero: true,
                                max: 100
                            }
                        }
                    }
                });
            })
            .catch(err => {
                console.error('加载得分数据失败', err);
            });
    }

    function loadSummary() {
        const startVal = startInput.value;
        const endVal = endInput.value;
        if (!startVal || !endVal) return;

        const csrfToken = getCsrfToken();

        if (summaryBox) {
            summaryBox.textContent = 'AI 正在分析这一段时间的规划完成情况...';
        }

        fetch(`/insight/score/summary?start=${startVal}&end=${endVal}`, {
            method: 'GET',
            headers: csrfToken ? { 'X-CSRF-TOKEN': csrfToken } : {}
        })
            .then(resp => resp.ok ? resp.json() : Promise.reject(new Error(resp.statusText)))
            .then(data => {
                if (summaryBox) {
                    summaryBox.textContent = data && data.summary
                        ? data.summary
                        : '没有获得有效的 AI 总结。';
                }
            })
            .catch(err => {
                console.error('加载 AI 总结失败', err);
                if (summaryBox) {
                    summaryBox.textContent = 'AI 总结暂时不可用，请稍后重试。';
                }
            });
    }

    if (refreshBtn) {
        refreshBtn.addEventListener('click', function () {
            loadScores();
        });
    }

    if (summaryBtn) {
        summaryBtn.addEventListener('click', function () {
            loadSummary();
        });
    }

    loadScores();
});

