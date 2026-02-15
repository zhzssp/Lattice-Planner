/**
 * 目标与任务树状图：使用 D3 绘制节点与边，直观展示关联关系。
 * 在展示区块首次显示时拉取 /api/goal-task-tree 并渲染。
 */
(function () {
    var rendered = false;
    var svgWidth = 900;
    var svgHeight = 420;

    function getCsrfHeaders() {
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (!token || !header) return {};
        var h = {};
        h[header.getAttribute('content')] = token.getAttribute('content');
        return h;
    }

    function drawTree(data) {
        var container = document.getElementById('goalTreeVizContainer');
        var emptyMsg = document.getElementById('goalTreeEmptyMsg');
        var svgEl = document.getElementById('goalTreeSvg');
        if (!container || !svgEl) return;

        if (!data || !data.children || data.children.length === 0) {
            emptyMsg.style.display = 'block';
            svgEl.style.display = 'none';
            return;
        }
        emptyMsg.style.display = 'none';
        svgEl.style.display = 'block';

        // 清空旧图
        svgEl.innerHTML = '';
        var svg = d3.select(svgEl)
            .attr('width', '100%')
            .attr('height', svgHeight)
            .attr('viewBox', [0, 0, svgWidth, svgHeight]);

        var g = svg.append('g');

        var root = d3.hierarchy(data);
        var treeLayout = d3.tree().size([svgWidth - 80, svgHeight - 60]);
        treeLayout(root);

        // 整体留边距（d3.tree 中 x=水平, y=深度）
        var marginLeft = 50;
        var marginTop = 36;
        root.each(function (d) {
            d.x += marginLeft;
            d.y += marginTop;
        });

        // 边：从父到子（自上而下：x 水平，y 垂直）
        var linkGen = d3.linkVertical()
            .x(function (d) { return d.x; })
            .y(function (d) { return d.y; });

        g.selectAll('.goal-tree-link')
            .data(root.links())
            .enter()
            .append('path')
            .attr('class', 'goal-tree-link')
            .attr('d', linkGen)
            .attr('fill', 'none')
            .attr('stroke', '#c0c8d4')
            .attr('stroke-width', 1.5);

        // 节点组（圆 + 文字）
        var node = g.selectAll('.goal-tree-node')
            .data(root.descendants())
            .enter()
            .append('g')
            .attr('class', function (d) {
                return 'goal-tree-node goal-tree-node-' + (d.data.type || '');
            })
            .attr('transform', function (d) { return 'translate(' + d.x + ',' + d.y + ')'; });

        // 根节点不画或画小点
        node.filter(function (d) { return d.data.type === 'root'; })
            .append('circle')
            .attr('r', 0)
            .attr('fill', 'transparent');

        node.filter(function (d) { return d.data.type !== 'root'; })
            .append('circle')
            .attr('r', function (d) { return d.data.type === 'goal' ? 10 : 6; })
            .attr('fill', function (d) { return d.data.type === 'goal' ? '#4a90e2' : '#5a9c5a'; })
            .attr('stroke', '#fff')
            .attr('stroke-width', 2);

        // 标签
        node.filter(function (d) { return d.data.type !== 'root'; })
            .append('text')
            .attr('dy', function (d) { return d.data.type === 'goal' ? -14 : -10; })
            .attr('text-anchor', 'middle')
            .attr('class', 'goal-tree-label')
            .text(function (d) {
                var name = d.data.name || '';
                if (d.data.deadline) name += ' (' + d.data.deadline + ')';
                return name.length > 24 ? name.slice(0, 22) + '…' : name;
            });
    }

    window.initGoalTreeViz = function () {
        if (rendered) return;
        rendered = true;

        fetch('/api/goal-task-tree', { headers: getCsrfHeaders(), credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(drawTree)
            .catch(function (err) {
                console.error('Goal tree load failed:', err);
                var emptyMsg = document.getElementById('goalTreeEmptyMsg');
                var svgEl = document.getElementById('goalTreeSvg');
                if (emptyMsg) {
                    emptyMsg.textContent = '加载失败，请刷新重试。';
                    emptyMsg.style.display = 'block';
                }
                if (svgEl) svgEl.style.display = 'none';
            });
    };
})();
