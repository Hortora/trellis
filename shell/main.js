// main.js
'use strict';
const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const { JavaServer, findFreePort } = require('./java-server');
const { WindowManager } = require('./window-manager');
const { LayoutStore } = require('./layout-store');

const server = new JavaServer({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath });
let wm = null;
const layoutStore = new LayoutStore();

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

  ipcMain.handle('layout:save', (_event, workspacePath) => {
    const layout = wm.getLayout();
    layoutStore.save(workspacePath, layout);
  });

  ipcMain.handle('layout:load', (_event, workspacePath) => {
    return layoutStore.load(workspacePath);
  });

  ipcMain.handle('window:list', () => {
    return wm.getWindows().map(w => ({
      id: w.id,
      bounds: w.getBounds(),
    }));
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
    });

    registerIpcHandlers();
    await server.spawnServer(port);
    await wm.createWindow('/');
  } catch (err) {
    showErrorWindow(err.message);
  }
});

app.on('before-quit', async (event) => {
  event.preventDefault();
  if (wm) wm.closeAll();
  await server.killServer();
  app.exit(0);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
