// --------------------- Lattice-Planner 客户端：渲染进程与主进程通信（登录状态、任务截止通知、Agent 本地桥） ---------------------
const { contextBridge, ipcRenderer } = require('electron');

// 暴露给页面用的 API（如错误页的"重试"），仅暴露必要方法
contextBridge.exposeInMainWorld('latticePlanner', {
    reload: () => ipcRenderer.send('reload-app'),
    // 主动式 Agent：订阅晨报/晚报推送，页面可据此在应用内展示完整报告 detail
    onDailyReport: (callback) => {
        const listener = (_event, report) => callback(report);
        ipcRenderer.on('daily-report', listener);
        // 返回取消订阅句柄，便于页面卸载时解绑
        return () => ipcRenderer.removeListener('daily-report', listener);
    }
});

// ★ Lattice-Agent 本地能力桥：仅在 Electron 客户端环境下可用
// 后端 JVM 不直接做磁盘 IO，所有本地操作必须经过此桥（main.js 内基于白名单实施二次校验）
contextBridge.exposeInMainWorld('lattice', {
    localBridge: {
        listDir:  (p) => ipcRenderer.invoke('local:list_dir',  { path: p }),
        readFile: (p) => ipcRenderer.invoke('local:read_file', { path: p }),
        readPdf:  (p) => ipcRenderer.invoke('local:read_pdf',  { path: p })
    }
});

// ★ Lattice-Agent 本地能力桥：仅在 Electron 客户端环境下可用
// 后端 JVM 不直接做磁盘 IO，所有本地操作必须经过此桥（main.js 内基于白名单实施二次校验）
contextBridge.exposeInMainWorld('lattice', {
    localBridge: {
        listDir:  (p) => ipcRenderer.invoke('local:list_dir',  { path: p }),
        readFile: (p) => ipcRenderer.invoke('local:read_file', { path: p }),
        readPdf:  (p) => ipcRenderer.invoke('local:read_pdf',  { path: p })
    }
});

window.addEventListener('DOMContentLoaded', () => {
    console.log('Lattice-Planner renderer loaded.');
    ipcRenderer.on('login-status', (event, isLoggedIn) => {
        console.log('Login status from main process:', isLoggedIn);
    });
    ipcRenderer.on('notification', (event, taskTitle) => {
        console.log('Deadline notification from main process:', taskTitle);
    });
    ipcRenderer.on('grant', (event, message) => {
        console.log('Notification permission:', message);
    });
    ipcRenderer.on('daily-report', (event, report) => {
        console.log('Daily report from main process:', report && report.type);
    });
});
