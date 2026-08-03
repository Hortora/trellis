// layout-store.js
'use strict';

class LayoutStore {
  constructor() {
    this._store = null;
    this._ready = import('electron-store').then(mod => {
      this._store = new mod.default({ name: 'trellis-layouts' });
    });
  }

  async _ensureReady() {
    await this._ready;
  }

  async save(workspacePath, layout) {
    if (!layout || !Array.isArray(layout.windows)) {
      throw new Error('Layout must have windows array');
    }
    await this._ensureReady();
    this._store.set(this._key(workspacePath), layout);
  }

  async load(workspacePath) {
    await this._ensureReady();
    return this._store.get(this._key(workspacePath)) || null;
  }

  async clear(workspacePath) {
    await this._ensureReady();
    this._store.delete(this._key(workspacePath));
  }

  _key(workspacePath) {
    return `layouts.${workspacePath}`;
  }
}

module.exports = { LayoutStore };
