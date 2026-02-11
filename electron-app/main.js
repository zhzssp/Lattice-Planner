// ------------------------- Lattice-Planner 客户端主入口：与后端服务交互，支持 DDL 提醒与系统托盘 -------------------------
const { app, BrowserWindow, ipcMain, Tray, nativeImage, Menu, Notification } = require('electron');
const path = require('path');
const axios = require('axios');

// 后端地址：优先使用环境变量 ELECTRON_APP_BASE_URL，否则默认本地 8080（与 Spring Boot 一致）
const BASE_URL = process.env.ELECTRON_APP_BASE_URL || 'http://localhost:8080';
axios.defaults.withCredentials = true;
axios.defaults.baseURL = BASE_URL;

// 创建一个全局的cookie存储
let sessionCookies = '';

// 获取Electron窗口的cookie并设置到axios请求中
async function getCookiesFromWindow() {
    if (mainWindow) {
        try {
            const cookies = await mainWindow.webContents.session.cookies.get({});
            console.log('All cookies from window:', cookies);

            const relevantCookies = cookies
                .filter(cookie =>
                    (cookie.name === 'JSESSIONID' || cookie.name.includes('SESSION'))
                );

            console.log('Relevant cookies:', relevantCookies);

            const cookieString = relevantCookies
                .map(cookie => `${cookie.name}=${cookie.value}`)
                .join('; ');

            console.log('Cookie string:', cookieString);
            return cookieString;
        } catch (error) {
            console.error('Error getting cookies:', error);
            return '';
        }
    }
    return '';
}

let tray = null;
let mainWindow = null;
let intervalId1 = null; // 用于存储 setInterval 的 ID
let intervalId2 = null; // 用于存储 setInterval 的 ID

// 加载失败时显示的本地错误页（避免空白屏）
function getLoadErrorHtml() {
    return `
<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>Lattice-Planner</title></head>
<body style="font-family:sans-serif;padding:2em;text-align:center;background:#f5f5f5;">
  <h2>无法连接 Lattice-Planner 服务</h2>
  <p>请确认后端已启动（默认地址：<code>${BASE_URL}</code>）</p>
  <p>启动后端后，请关闭本窗口并从托盘再次打开，或重启客户端。</p>
  <button onclick="window.latticePlanner&&window.latticePlanner.reload()" style="padding:8px 16px;cursor:pointer;">重试</button>
</body></html>`;
}

// 供渲染进程“重试”按钮重新加载后端 URL
ipcMain.on('reload-app', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.loadURL(BASE_URL).catch(err => {
            console.error('Reload failed:', err);
            mainWindow.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(getLoadErrorHtml()));
        });
    }
});

// 创建窗口
function createWindow() {
    const win = new BrowserWindow({
        width: 1000,
        height: 800,
        webPreferences: {
            // 加载远程 URL 时必须关闭 nodeIntegration，否则新版 Electron 易出现空白屏
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.js'),
            partition: 'persist:main'
        }
    });

    mainWindow = win;

    // 加载失败时显示错误页，避免一片空白
    win.webContents.on('did-fail-load', (event, errorCode, errorDescription, validatedURL) => {
        if (validatedURL && validatedURL !== 'about:blank') {
            console.error('Load failed:', validatedURL, errorCode, errorDescription);
            win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(getLoadErrorHtml()));
        }
    });

    // 加载 Lattice-Planner Web 应用（与 BASE_URL 一致）
    win.loadURL(BASE_URL).catch(err => {
        console.error('loadURL error:', err);
        win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(getLoadErrorHtml()));
    });

    // 当窗口关闭时，将窗口隐藏到系统托盘 --> 只能在系统托盘关闭
    mainWindow.on('close', (event) => {
        event.preventDefault();
        mainWindow.hide();
    });
}

// 设置系统托盘
function createTray() {
    // 托盘图标路径
    const iconPath = path.join(__dirname, 'icon.ico');

    // 使用托盘图标
    tray = new Tray(nativeImage.createFromPath(iconPath));

    // 创建右键菜单
    const contextMenu = Menu.buildFromTemplate([
        {
            label: '打开 Lattice-Planner',
            click: () => {
                if (mainWindow) {
                    mainWindow.show(); // 显示窗口
                }
            }
        },
        {
            label: '退出',
            click: () => {
                mainWindow.destroy(); // 销毁窗口
                tray.destroy(); // 销毁托盘图标
                app.quit(); // 退出应用
            }
        }
    ]);

    // 右键点击托盘图标时，显示菜单
    tray.setContextMenu(contextMenu);

    // 单击托盘图标时，显示窗口
    tray.on('click', () => {
        if (mainWindow) {
            mainWindow.show();
        }
    });

    // 鼠标右键点击托盘图标时，显示菜单
    tray.on('right-click', () => {
        tray.popUpContextMenu(contextMenu);
    });
}

// 发送桌面通知（工作规划任务截止提醒）
function sendDeadlineNotification(taskTitle, deadline) {
    console.log(`Checking notification for task: ${taskTitle}, deadline: ${deadline}`);

    const now = new Date();
    const deadlineDate = new Date(deadline); // 接收 LocalDateTime 字符串或时间戳

    const msUntilDue = deadlineDate.getTime() - now.getTime();
    const oneDayMs = 24 * 60 * 60 * 1000;
    const threeDaysMs = 3 * oneDayMs;

    console.log(`Task: ${taskTitle}, ms until due: ${msUntilDue}`);
    let notification = null;

    if (msUntilDue <= 0) {
        console.log(`Sending overdue notification for task: ${taskTitle}`);
        notification = new Notification({
            title: '任务截止提醒: ' + taskTitle,
            body: '该任务已过截止时间，请尽快处理。'
        });
    } else if (msUntilDue <= oneDayMs && msUntilDue > 0) {
        console.log(`Sending 1-day notification for task: ${taskTitle}`);
        notification = new Notification({
            title: '任务截止提醒: ' + taskTitle,
            body: '该任务将在一天内截止，请留意。'
        });
    } else if (msUntilDue <= threeDaysMs && msUntilDue > oneDayMs) {
        console.log(`Sending 3-day notification for task: ${taskTitle}`);
        notification = new Notification({
            title: '任务截止提醒: ' + taskTitle,
            body: '该任务将在三天内截止，请提前安排。'
        });
    } else {
        console.log(`No notification needed for task: ${taskTitle}`);
    }

    if (notification) {
        mainWindow.webContents.send('notification', taskTitle);
        notification.show();
    }
}

// 检查DDL是否到期
function checkTasksDue() {
    console.log('checkTasksDue called');
    // Electron 主进程的 Notification 不需要浏览器风格的权限请求
    if (typeof Notification.isSupported === 'function' && !Notification.isSupported()) {
        mainWindow.webContents.send('grant', 'System notifications are not supported in this environment');
        return;
    }
    try {
        mainWindow.webContents.send('grant', 'Notification ready');
    } catch (e) {
        mainWindow.webContents.send('grant', e);
    }
    // 执行check并通知的具体业务逻辑
    performTaskCheck();
}

// 执行任务检查：仅对「未完成且设置了截止时间」的任务发送 DDL 提醒（与工作规划后端 Task 一致）
let notifiedTasks = new Set(); // 避免重复通知
async function performTaskCheck() {
    console.log('Checking tasks due...');

    try {
        const cookies = await getCookiesFromWindow();
        console.log('Using cookies for due-dates:', cookies);

        const response = await axios.get('/due-dates', {
            withCredentials: true,
            headers: cookies ? {
                'Cookie': cookies
            } : {}
        });

        const tasks = response.data || [];
        // 仅处理：有截止时间 且 状态为 PENDING（未完成/未搁置/未归档）
        const pendingWithDeadline = tasks.filter(t => {
            if (!t || t.deadline == null) return false;
            const status = (t.status || 'PENDING').toUpperCase();
            return status === 'PENDING';
        });
        console.log(`Found ${tasks.length} tasks, ${pendingWithDeadline.length} pending with deadline`);

        pendingWithDeadline.forEach(task => {
            const taskId = task.id;
            if (!notifiedTasks.has(taskId)) {
                console.log('Processing task, id = ', taskId);
                notifiedTasks.add(taskId);
                sendDeadlineNotification(task.title || '(无标题)', task.deadline);
            }
        });
    } catch (error) {
        console.error('Error fetching tasks:', error);
        console.error('Error details:', error.response?.data || error.message);
    }
}

async function getLoginState() {
    try {
        // 更新全局cookie存储
        sessionCookies = await getCookiesFromWindow();
        console.log('Using cookies:', sessionCookies);

        // 使用axios的默认配置，但确保cookie正确传递
        const response = await axios.get('/user-logged-in', {
            withCredentials: true,
            headers: sessionCookies ? {
                'Cookie': sessionCookies
            } : {}
        });

        console.log('Login state response:', response.data);
        return response.data;
    } catch (error) {
        console.error('Error fetching login state:', error);
        return false;
    }
}

// 先申请单实例锁
const gotTheLock = app.requestSingleInstanceLock();

if (!gotTheLock) {
    // 如果已有实例在运行，则退出当前新实例
    app.quit();
} else {
    // 监听第二次实例启动事件
    app.on('second-instance', (event, argv, workingDirectory) => {
        // 当用户再次打开应用时，让现有窗口显示出来
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
        }
    });
}

// 当 Electron 初始化完成后调用
app.whenReady().then(() => {
    // Windows 需要设置 AppUserModelID 才能显示系统通知
    try {
        app.setAppUserModelId('Lattice-Planner');
    } catch (e) {
        console.warn('Failed to set AppUserModelID:', e);
    }
    createTray();
    createWindow();

    // 定期检查登录状态和DDL
    const checkLoginAndDDL = () => {
        getLoginState().then(isLoggedIn => {
            console.log('Login state:', isLoggedIn);
            mainWindow.webContents.send('login-status', isLoggedIn);

            // 若用户登录成功，则允许检查DDL任务
            if (isLoggedIn) {
                checkTasksDue();
            }
        }).catch(error => {
            console.error('Error checking login state:', error);
        });
    };

    // 立即检查一次
    checkLoginAndDDL();

    // 每30秒检查一次登录状态和DDL
    intervalId1 = setInterval(checkLoginAndDDL, 60000);
    // 每天清理一次已通知任务集合
    intervalId2 = setInterval(() => notifiedTasks.clear(), 24 * 60 * 60 * 1000);

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) {
            createWindow();
        }
    });
});

// 退出应用时清理 setInterval
app.on('before-quit', () => {
    if (intervalId1) {
        clearInterval(intervalId1);
    }
    if (intervalId2) {
        clearInterval(intervalId2);
    }
});

// 捕获未处理的同步错误
process.on('uncaughtException', (error) => {
    console.error('Uncaught Exception:', error);
    app.quit();  // 终止进程
});

// 捕获未处理的 Promise 拒绝
process.on('unhandledRejection', (reason, promise) => {
    console.error('Unhandled Rejection at promise:', promise, 'reason:', reason);
    app.quit();  // 终止进程
});


