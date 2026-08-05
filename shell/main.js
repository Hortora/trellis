// main.js
'use strict';
const { app, BrowserWindow, ipcMain, Menu } = require('electron');
const path = require('path');
const { JavaServer, findFreePort } = require('./java-server');
const { WindowManager } = require('./window-manager');
const { LayoutStore } = require('./layout-store');
const { HealthMonitor } = require('./health-monitor');

const server = new JavaServer({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath });
let wm = null;
const layoutStore = new LayoutStore();
let currentWorkspacePath = null;
const windowLayouts = new Map();
let saveInhibited = false;
let deferredSaves = [];

const webglBudget = { max: 16, active: new Map(), pendingQueue: [] };

function showErrorWindow(message) {
  const escape = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const win = new BrowserWindow({ width: 700, height: 500, show: false });
  const logs = escape(server.getLogs().join('\n'));
  const html = `<!DOCTYPE html><html><body style="font-family:monospace;padding:20px;background:#1a1a1a;color:#eee">
    <h2 style="color:#f87171">Trellis failed to start</h2>
    <p>${escape(message)}</p>
    <pre style="overflow:auto;background:#111;padding:10px;max-height:350px">${logs}</pre>
    </body></html>`;
  win.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
  win.show();
}

function registerIpcHandlers() {
  ipcMain.handle('app:version', () => app.getVersion());

  ipcMain.handle('dialog:openFolder', async () => {
    const { dialog } = require('electron');
    const result = await dialog.showOpenDialog({ properties: ['openDirectory'] });
    if (result.canceled || result.filePaths.length === 0) return null;
    return result.filePaths[0];
  });

  ipcMain.handle('window:create', async (_event, route, opts) => {
    const win = await wm.createWindow(route, opts);
    return win.id;
  });

  ipcMain.handle('panel:detach', async (_event, panelId, opts) => {
    const win = await wm.detachPanel(panelId, opts);
    return win.id;
  });

  ipcMain.handle('panel:attach', (_event, panelId, targetWindowId) => {
    wm.attachPanel(panelId, targetWindowId);
  });

  ipcMain.handle('layout:window-save', (_event, shellLayout) => {
    const win = BrowserWindow.fromWebContents(_event.sender);
    if (!win) return;
    windowLayouts.set(win.id, shellLayout);
    if (saveInhibited) {
      deferredSaves.push(win.id);
      return;
    }
    if (currentWorkspacePath) {
      const composite = { windows: [...windowLayouts.values()] };
      layoutStore.saveLayout(currentWorkspacePath, composite);
      layoutStore.saveLastWorkspacePath(currentWorkspacePath);
    }
  });

  ipcMain.handle('layout:loadLayout', (_event, workspacePath) => {
    return layoutStore.loadLayout(workspacePath);
  });

  ipcMain.handle('layout:loadGroups', (_event, workspacePath) => {
    return layoutStore.loadGroups(workspacePath);
  });

  ipcMain.handle('layout:saveGroups', (_event, workspacePath, groups) => {
    return layoutStore.saveGroups(workspacePath, groups);
  });

  ipcMain.handle('layout:loadKeymap', (_event, workspacePath) => {
    return layoutStore.loadKeymap(workspacePath);
  });

  ipcMain.handle('layout:saveKeymap', (_event, workspacePath, keymap) => {
    return layoutStore.saveKeymap(workspacePath, keymap);
  });

  ipcMain.handle('layout:lastWorkspacePath', () => {
    return layoutStore.loadLastWorkspacePath();
  });

  ipcMain.handle('layout:setWorkspacePath', (_event, workspacePath) => {
    currentWorkspacePath = workspacePath;
    layoutStore.saveLastWorkspacePath(workspacePath);
  });

  ipcMain.handle('layout:inhibitSave', () => {
    saveInhibited = true;
  });

  ipcMain.handle('layout:releaseSave', () => {
    saveInhibited = false;
    if (deferredSaves.length > 0 && currentWorkspacePath) {
      deferredSaves = [];
      const composite = { windows: [...windowLayouts.values()] };
      layoutStore.saveLayout(currentWorkspacePath, composite);
    }
  });

  ipcMain.handle('window:list', () => {
    return wm.getWindows().map(w => ({
      id: w.id,
      bounds: w.getBounds(),
    }));
  });

  ipcMain.handle('webgl:acquire', (_event, terminalName) => {
    const win = BrowserWindow.fromWebContents(_event.sender);
    if (!win) return { granted: false };
    if (webglBudget.active.size < webglBudget.max) {
      webglBudget.active.set(terminalName, { windowId: win.id, lastFocusedAt: Date.now() });
      return { granted: true };
    }
    webglBudget.pendingQueue.push({ windowId: win.id, terminalName, lastFocusedAt: Date.now() });
    return { granted: false };
  });

  ipcMain.handle('webgl:release', (_event, terminalName) => {
    webglBudget.active.delete(terminalName);
    if (webglBudget.pendingQueue.length > 0) {
      webglBudget.pendingQueue.sort((a, b) => b.lastFocusedAt - a.lastFocusedAt);
      const next = webglBudget.pendingQueue.shift();
      const targetWin = wm ? wm.getWindowById(next.windowId) : null;
      if (targetWin && !targetWin.isDestroyed()) {
        webglBudget.active.set(next.terminalName, { windowId: next.windowId, lastFocusedAt: next.lastFocusedAt });
        targetWin.webContents.send('webgl:grant', next.terminalName);
      }
    }
  });

  ipcMain.handle('window:next', () => {
    const windows = wm ? wm.getWindows().filter(w => !w.isDestroyed()) : [];
    if (windows.length < 2) return;
    const focused = BrowserWindow.getFocusedWindow();
    const idx = focused ? windows.findIndex(w => w.id === focused.id) : -1;
    const next = windows[(idx + 1) % windows.length];
    next.focus();
  });

  ipcMain.handle('window:prev', () => {
    const windows = wm ? wm.getWindows().filter(w => !w.isDestroyed()) : [];
    if (windows.length < 2) return;
    const focused = BrowserWindow.getFocusedWindow();
    const idx = focused ? windows.findIndex(w => w.id === focused.id) : -1;
    const prev = windows[(idx - 1 + windows.length) % windows.length];
    prev.focus();
  });
}

app.whenReady().then(async () => {
  server.on('fatal', () => showErrorWindow('The Trellis sidecar crashed and could not restart.'));
  try {
    const port = await findFreePort();
    global.__TRELLIS_PORT__ = port;

    wm = new WindowManager({
      port,
      preloadPath: path.join(__dirname, 'preload.js'),
      onWindowClosed: (winId) => {
        windowLayouts.delete(winId);
        if (currentWorkspacePath && !saveInhibited) {
          const composite = { windows: [...windowLayouts.values()] };
          layoutStore.saveLayout(currentWorkspacePath, composite);
        }
        for (const [name, entry] of webglBudget.active) {
          if (entry.windowId === winId) webglBudget.active.delete(name);
        }
        webglBudget.pendingQueue = webglBudget.pendingQueue.filter(e => e.windowId !== winId);
      },
    });

    registerIpcHandlers();

    const menuTemplate = [
      {
        label: app.name,
        submenu: [
          { role: 'about' },
          { type: 'separator' },
          { role: 'quit' },
        ],
      },
      {
        label: 'File',
        submenu: [
          { label: 'New Frame', accelerator: 'CmdOrCtrl+N', click: () => { const w = BrowserWindow.getFocusedWindow(); if (w) w.webContents.send('shortcut:new-frame'); } },
          { label: 'New Tab', accelerator: 'CmdOrCtrl+T', click: () => { const w = BrowserWindow.getFocusedWindow(); if (w) w.webContents.send('shortcut:new-tab'); } },
          { type: 'separator' },
          { label: 'Close Tab', accelerator: 'CmdOrCtrl+W', click: () => { const w = BrowserWindow.getFocusedWindow(); if (w) w.webContents.send('shortcut:close-tab'); } },
        ],
      },
      { label: 'Edit', submenu: [{ role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }] },
      { label: 'Window', submenu: [{ role: 'minimize' }, { role: 'zoom' }] },
    ];
    Menu.setApplicationMenu(Menu.buildFromTemplate(menuTemplate));

    await server.spawnServer(port);

    const healthMonitor = new HealthMonitor({ port });
    healthMonitor.on('recovered', () => {
      for (const win of wm.getWindows()) {
        if (!win.isDestroyed()) win.reload();
      }
    });
    healthMonitor.start();

    const lastPath = await layoutStore.loadLastWorkspacePath();
    if (lastPath) currentWorkspacePath = lastPath;
    await wm.createWindow('/');
  } catch (err) {
    showErrorWindow(err.message);
  }
});

app.on('before-quit', async (event) => {
  event.preventDefault();

  const windows = wm ? wm.getWindows().filter(w => !w.isDestroyed()) : [];
  if (windows.length > 0 && currentWorkspacePath) {
    const flushPromises = windows.map(win => new Promise(resolve => {
      const handler = (_ev, shellLayout) => {
        const sender = BrowserWindow.fromWebContents(_ev.sender);
        if (sender && sender.id === win.id) {
          windowLayouts.set(win.id, shellLayout);
          ipcMain.removeHandler('layout:flush-response-' + win.id);
          resolve();
        }
      };
      ipcMain.handle('layout:flush-response-' + win.id, handler);
      win.webContents.send('layout:flush');
      setTimeout(() => {
        ipcMain.removeHandler('layout:flush-response-' + win.id);
        resolve();
      }, 2000);
    }));
    await Promise.all(flushPromises);
    const composite = { windows: [...windowLayouts.values()] };
    await layoutStore.saveLayout(currentWorkspacePath, composite);
  }

  if (wm) wm.closeAll();
  await server.killServer();
  app.exit(0);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
