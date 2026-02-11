// --------------------- Lattice-Planner 客户端：渲染进程与主进程通信（登录状态、任务截止通知等） ---------------------
const { contextBridge, ipcRenderer } = require('electron');

// 暴露给页面用的 API（如错误页的“重试”），仅暴露必要方法
contextBridge.exposeInMainWorld('latticePlanner', {
    reload: () => ipcRenderer.send('reload-app')
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
});
