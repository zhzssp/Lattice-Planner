/**
 * Lattice-Agent 分栏引导脚本（split-v11）。
 * 必须在 chat-panel.js 之前加载。即使宿主页仍是旧浮层 HTML / 旧 CSS，
 * 这里也会用行内 !important 把面板钉回文档流，并补上左右分栏壳。
 *
 * 控制台指纹：看到 [LP-Agent][split-v11] 才说明浏览器执行的是当前源码。
 * 手动复查：window.__lpAgentDumpLayout('manual')
 */
(function (global) {
    'use strict';

    var VERSION = 'split-v11';
    var HOST_CARD_SEL = [
        '.lp-page-main .container',
        '.lp-page-main .note-page',
        '.lp-page-main .memo-form-container',
        '.lp-page-main .mcp-container',
        'body > .container'
    ].join(',');

    global.LP_AGENT_LAYOUT = VERSION;

    function $(sel, root) {
        return (root || document).querySelector(sel);
    }

    function pinPanel(panel) {
        if (!panel) return;
        panel.style.setProperty('position', 'relative', 'important');
        panel.style.setProperty('right', 'auto', 'important');
        panel.style.setProperty('top', 'auto', 'important');
        panel.style.setProperty('left', 'auto', 'important');
        panel.style.setProperty('bottom', 'auto', 'important');
        panel.style.setProperty('inset', 'auto', 'important');
        panel.style.setProperty('width', '100%', 'important');
        panel.style.setProperty('max-width', 'none', 'important');
        panel.style.setProperty('height', '100%', 'important');
        panel.style.setProperty('z-index', '2', 'important');
        panel.style.setProperty('transform', 'none', 'important');
    }

    function ensurePageShell(panel) {
        var mount = $('.lp-agent-mount') || (panel && panel.parentElement);
        if (!mount) return;
        if (!mount.classList.contains('lp-agent-mount')) {
            mount.classList.add('lp-agent-mount');
        }
        var shell = $('.lp-page-shell');
        if (shell && shell.contains(mount)) return;

        if (!shell) {
            shell = document.createElement('div');
            shell.className = 'lp-page-shell';
        }
        var main = shell.querySelector('.lp-page-main');
        if (!main) {
            main = document.createElement('div');
            main.className = 'lp-page-main';
            shell.insertBefore(main, shell.firstChild);
        }
        if (!shell.parentElement) {
            document.body.insertBefore(
                shell,
                mount.parentElement === document.body ? mount : document.body.firstChild
            );
        }
        Array.from(document.body.children).forEach(function (el) {
            if (el === shell || el === mount) return;
            if (el.id === 'contextMenu' || el.id === 'lp-agent-layout-chip') return;
            if (el.classList && el.classList.contains('modal')) return;
            if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'LINK') return;
            main.appendChild(el);
        });
        if (mount.parentElement !== shell) shell.appendChild(mount);
    }

    function currentPanelWidth() {
        var raw = getComputedStyle(document.documentElement).getPropertyValue('--lp-agent-width').trim();
        return raw || '440px';
    }

    function shrinkHostCards(open) {
        document.querySelectorAll(HOST_CARD_SEL).forEach(function (el) {
            if (open) {
                el.style.setProperty('max-width', 'none', 'important');
                el.style.setProperty('width', 'auto', 'important');
                el.style.setProperty('box-sizing', 'border-box', 'important');
            } else {
                el.style.removeProperty('max-width');
                el.style.removeProperty('width');
                el.style.removeProperty('box-sizing');
            }
        });
    }

    function ensureChip() {
        var chip = document.getElementById('lp-agent-layout-chip');
        if (chip) {
            chip.textContent = VERSION;
            return chip;
        }
        if (!document.body) return null;
        chip = document.createElement('div');
        chip.id = 'lp-agent-layout-chip';
        chip.textContent = VERSION;
        chip.setAttribute('title', 'Lattice-Agent 布局指纹：看不到它说明页面不是当前源码');
        document.body.appendChild(chip);
        return chip;
    }

    function dumpLayout(tag) {
        var panel = document.getElementById('lp-agent-panel');
        var main = $('.lp-page-main') || $('.container');
        var mount = $('.lp-agent-mount');
        var shell = $('.lp-page-shell');
        var container = $('.lp-page-main .container') || $('.container');
        if (!panel) {
            console.warn('[LP-Agent][' + VERSION + '][' + tag + '] 没有 #lp-agent-panel');
            return null;
        }
        var pr = panel.getBoundingClientRect();
        var mr = main ? main.getBoundingClientRect() : null;
        var overlap = 0;
        if (mr) {
            overlap = Math.max(0, Math.min(mr.right, pr.right) - Math.max(mr.left, pr.left));
        }
        var info = {
            version: VERSION,
            tag: tag,
            href: location.href,
            htmlClass: document.documentElement.className,
            dataLayout: document.documentElement.getAttribute('data-lp-layout'),
            panelClass: panel.className,
            panelPosition: getComputedStyle(panel).position,
            panelRight: getComputedStyle(panel).right,
            hasShell: !!shell,
            hasInlineCss: !!document.getElementById('lp-agent-split-inline'),
            hasBootCss: !!document.getElementById('lp-agent-split-boot'),
            cssHref: (document.querySelector('link[href*="chat-panel"]') || {}).href,
            viewport: { w: window.innerWidth, h: window.innerHeight },
            main: mr && {
                left: Math.round(mr.left),
                width: Math.round(mr.width),
                right: Math.round(mr.right)
            },
            panel: {
                left: Math.round(pr.left),
                width: Math.round(pr.width),
                right: Math.round(pr.right)
            },
            mountW: mount ? Math.round(mount.getBoundingClientRect().width) : 0,
            containerMaxWidth: container ? getComputedStyle(container).maxWidth : null,
            overlapPx: Math.round(overlap),
            sideBySide: !!(mr && pr.width > 80 && pr.left >= mr.right - 2)
        };
        var ok = info.sideBySide || info.panel.width < 8;
        console.log(
            '%c[LP-Agent][' + VERSION + '][' + tag + ']',
            'background:' + (ok ? '#047857' : '#b45309') + ';color:#fff;padding:2px 8px;border-radius:3px',
            info
        );
        if (info.panel.width > 80 && info.overlapPx > 8) {
            console.warn(
                '[LP-Agent][' + VERSION + '] 面板仍在覆盖主栏 overlap=' + info.overlapPx +
                'px position=' + info.panelPosition + '。若完全看不到 split-v11，请彻底停掉旧的 Spring 进程后重新启动。'
            );
        }
        return info;
    }

    function forceLayout(open) {
        var panel = document.getElementById('lp-agent-panel');
        if (!panel) {
            console.warn('[LP-Agent][' + VERSION + '] forceLayout: 没有 #lp-agent-panel');
            return;
        }
        document.documentElement.setAttribute('data-lp-layout', VERSION);
        document.documentElement.classList.toggle('lp-agent-open', !!open);
        ensurePageShell(panel);
        pinPanel(panel);
        ensureChip();

        var mount = $('.lp-agent-mount') || panel.parentElement;
        var shell = $('.lp-page-shell');
        var main = $('.lp-page-main');
        var w = currentPanelWidth();

        if (shell) {
            shell.style.setProperty('display', 'flex', 'important');
            shell.style.setProperty('flex-direction', 'row', 'important');
            shell.style.setProperty('align-items', 'stretch', 'important');
            shell.style.setProperty('width', '100%', 'important');
            shell.style.setProperty('min-width', '0', 'important');
            if (open) {
                shell.style.setProperty('height', '100vh', 'important');
                shell.style.setProperty('overflow', 'hidden', 'important');
            } else {
                shell.style.removeProperty('height');
                shell.style.removeProperty('overflow');
            }
        }
        if (main) {
            main.style.setProperty('flex', '1 1 auto', 'important');
            main.style.setProperty('min-width', '0', 'important');
            main.style.setProperty('overflow', 'auto', 'important');
            if (open) {
                main.style.setProperty('max-width', 'calc(100% - ' + w + ')', 'important');
            } else {
                main.style.removeProperty('max-width');
            }
        }
        if (mount) {
            if (open) {
                mount.style.setProperty('flex', '0 0 ' + w, 'important');
                mount.style.setProperty('width', w, 'important');
                mount.style.setProperty('max-width', w, 'important');
            } else {
                mount.style.setProperty('flex', '0 0 0px', 'important');
                mount.style.setProperty('width', '0px', 'important');
                mount.style.setProperty('max-width', '0px', 'important');
            }
            mount.style.setProperty('min-width', '0', 'important');
            mount.style.setProperty('overflow', 'hidden', 'important');
            mount.style.setProperty('flex-shrink', '0', 'important');
        }

        shrinkHostCards(!!open);

        if (!shell && open) {
            document.body.style.setProperty('display', 'flex', 'important');
            document.body.style.setProperty('flex-direction', 'row', 'important');
            document.body.style.setProperty('align-items', 'stretch', 'important');
        }
        if (!open) {
            document.body.style.removeProperty('display');
            document.body.style.removeProperty('flex-direction');
            document.body.style.removeProperty('align-items');
        }

        dumpLayout(open ? 'force-open' : 'force-close');
    }

    function markHtml() {
        document.documentElement.setAttribute('data-lp-layout', VERSION);
    }

    markHtml();
    console.log(
        '%c[LP-Agent] layout ' + VERSION + ' boot',
        'background:#1d4ed8;color:#fff;padding:2px 8px;border-radius:3px',
        {
            href: location.href,
            hasPanel: !!document.getElementById('lp-agent-panel'),
            hasShell: !!$('.lp-page-shell'),
            hasInlineCss: !!document.getElementById('lp-agent-split-inline'),
            cssHref: (document.querySelector('link[href*="chat-panel"]') || {}).href
        }
    );

    function onReady() {
        markHtml();
        ensureChip();
        forceLayout(document.documentElement.classList.contains('lp-agent-open') ||
            !!($('#lp-agent-panel') && $('#lp-agent-panel').classList.contains('open')));
        dumpLayout('ready');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', onReady);
    } else {
        onReady();
    }

    document.addEventListener('click', function (e) {
        var t = e.target && e.target.closest ? e.target : null;
        if (!t || !t.closest) return;
        if (t.closest('#lp-agent-fab')) {
            console.log('[LP-Agent][' + VERSION + '] FAB click → 强制分栏');
            setTimeout(function () { forceLayout(true); }, 0);
        }
        if (t.closest('#lp-agent-close')) {
            console.log('[LP-Agent][' + VERSION + '] close click → 收起右栏');
            setTimeout(function () { forceLayout(false); }, 0);
        }
    }, true);

    global.__lpAgentForceLayout = forceLayout;
    global.__lpAgentDumpLayout = dumpLayout;
})(window);
