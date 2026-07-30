// main.js
'use strict';
const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const { JavaServer, findFreePort } = require('./java-server');

let mainWindow = null;
const server = new JavaServer({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath });

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

async function createMainWindow(port) {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  await mainWindow.loadURL(`http://127.0.0.1:${port}/`);
  mainWindow.show();
}

app.whenReady().then(async () => {
  server.on('fatal', () => showErrorWindow('The Trellis sidecar crashed and could not restart.'));
  try {
    const port = await findFreePort();
    global.__TRELLIS_PORT__ = port;
    await server.spawnServer(port);
    await createMainWindow(port);
  } catch (err) {
    showErrorWindow(err.message);
  }
});

app.on('before-quit', async (event) => {
  event.preventDefault();
  await server.killServer();
  app.exit(0);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

ipcMain.handle('app:version', () => app.getVersion());
