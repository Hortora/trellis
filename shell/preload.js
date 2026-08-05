// preload.js
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('trellis', {
  getVersion: () => ipcRenderer.invoke('app:version'),
  openFolderDialog: () => ipcRenderer.invoke('dialog:openFolder'),

  createWindow: (route, opts) => ipcRenderer.invoke('window:create', route, opts),
  detachPanel: (panelId, opts) => ipcRenderer.invoke('panel:detach', panelId, opts),
  attachPanel: (panelId, targetWindowId) => ipcRenderer.invoke('panel:attach', panelId, targetWindowId),
  listWindows: () => ipcRenderer.invoke('window:list'),

  saveWindowLayout: (shellLayout) => ipcRenderer.invoke('layout:window-save', shellLayout),
  loadLayout: (workspacePath) => ipcRenderer.invoke('layout:loadLayout', workspacePath),
  loadGroups: (workspacePath) => ipcRenderer.invoke('layout:loadGroups', workspacePath),
  saveGroups: (workspacePath, groups) => ipcRenderer.invoke('layout:saveGroups', workspacePath, groups),
  loadKeymap: (workspacePath) => ipcRenderer.invoke('layout:loadKeymap', workspacePath),
  saveKeymap: (workspacePath, keymap) => ipcRenderer.invoke('layout:saveKeymap', workspacePath, keymap),
  getLastWorkspacePath: () => ipcRenderer.invoke('layout:lastWorkspacePath'),
  setWorkspacePath: (workspacePath) => ipcRenderer.invoke('layout:setWorkspacePath', workspacePath),
  inhibitSave: () => ipcRenderer.invoke('layout:inhibitSave'),
  releaseSave: () => ipcRenderer.invoke('layout:releaseSave'),

  onLayoutFlush: (callback) => ipcRenderer.on('layout:flush', callback),
  onFrameInit: (callback) => ipcRenderer.on('frame:init', callback),
  onFrameReceive: (callback) => ipcRenderer.on('frame:receive', callback),
  onShortcut: (name, callback) => ipcRenderer.on('shortcut:' + name, callback),

  webglAcquire: (terminalName) => ipcRenderer.invoke('webgl:acquire', terminalName),
  webglRelease: (terminalName) => ipcRenderer.invoke('webgl:release', terminalName),
  onWebglGrant: (callback) => ipcRenderer.on('webgl:grant', callback),
  onWebglDemote: (callback) => ipcRenderer.on('webgl:demote', callback),

  nextWindow: () => ipcRenderer.invoke('window:next'),
  prevWindow: () => ipcRenderer.invoke('window:prev'),
});
