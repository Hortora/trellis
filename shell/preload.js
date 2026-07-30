// preload.js
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('trellis', {
  getVersion: () => ipcRenderer.invoke('app:version'),
});
