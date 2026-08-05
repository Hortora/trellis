// window-manager.js
'use strict';
const { BrowserWindow, screen } = require('electron');
const path = require('path');

class WindowManager {
  constructor({ port, preloadPath, onWindowClosed }) {
    this._port = port;
    this._preloadPath = preloadPath;
    this._windows = new Map();
    this._panels = new Map();
    this._onWindowClosed = onWindowClosed || (() => {});
  }

  async createWindow(route, opts = {}) {
    const win = new BrowserWindow({
      width: opts.width || 1400,
      height: opts.height || 900,
      x: opts.x,
      y: opts.y,
      show: false,
      webPreferences: {
        preload: this._preloadPath,
        contextIsolation: true,
        nodeIntegration: false,
      },
    });

    this._windows.set(win.id, win);
    win.on('closed', () => {
      this._windows.delete(win.id);
      for (const [panelId, winId] of this._panels) {
        if (winId === win.id) this._panels.delete(panelId);
      }
      this._onWindowClosed(win.id);
    });

    await win.loadURL(this._routeUrl(route));
    win.show();
    return win;
  }

  async detachPanel(panelId, { route, bounds } = {}) {
    if (this._panels.has(panelId)) {
      throw new Error(`Panel ${panelId} is already detached`);
    }

    const winOpts = bounds || {};
    const win = await this.createWindow(route, winOpts);
    this._panels.set(panelId, win.id);
    return win;
  }

  attachPanel(panelId, targetWindowId) {
    const targetWin = this._windows.get(targetWindowId);
    if (!targetWin) throw new Error(`Window ${targetWindowId} not found`);

    const detachedWinId = this._panels.get(panelId);
    this._panels.delete(panelId);

    if (detachedWinId) {
      const detachedWin = this._windows.get(detachedWinId);
      if (detachedWin && !detachedWin.isDestroyed()) {
        detachedWin.close();
      }
    }

    return targetWin;
  }

  getPanelWindow(panelId) {
    const winId = this._panels.get(panelId);
    if (!winId) return undefined;
    return this._windows.get(winId);
  }

  getWindows() {
    return [...this._windows.values()];
  }

  getWindowById(id) {
    return this._windows.get(id);
  }

  getLayout() {
    const windows = [];
    for (const win of this._windows.values()) {
      windows.push({
        id: win.id,
        bounds: win.getBounds(),
      });
    }
    const panels = {};
    for (const [panelId, winId] of this._panels) {
      panels[panelId] = winId;
    }
    return { windows, panels };
  }

  closeAll() {
    for (const win of this._windows.values()) {
      if (!win.isDestroyed()) win.close();
    }
  }

  _routeUrl(route) {
    return `http://127.0.0.1:${this._port}/#${route}`;
  }
}

module.exports = { WindowManager };
