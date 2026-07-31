// preload.js
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('trellis', {
  getVersion: () => ipcRenderer.invoke('app:version'),

  createWindow: (route, opts) => ipcRenderer.invoke('window:create', route, opts),
  detachPanel: (panelId, opts) => ipcRenderer.invoke('panel:detach', panelId, opts),
  attachPanel: (panelId, targetWindowId) => ipcRenderer.invoke('panel:attach', panelId, targetWindowId),
  saveLayout: (workspacePath) => ipcRenderer.invoke('layout:save', workspacePath),
  loadLayout: (workspacePath) => ipcRenderer.invoke('layout:load', workspacePath),
  listWindows: () => ipcRenderer.invoke('window:list'),
});
