// layout-store.js
'use strict';
const Store = require('electron-store');

class LayoutStore {
  constructor() {
    this._store = new Store({ name: 'trellis-layouts' });
  }

  save(workspacePath, layout) {
    if (!layout || !Array.isArray(layout.windows)) {
      throw new Error('Layout must have windows array');
    }
    this._store.set(this._key(workspacePath), layout);
  }

  load(workspacePath) {
    return this._store.get(this._key(workspacePath)) || null;
  }

  clear(workspacePath) {
    this._store.delete(this._key(workspacePath));
  }

  _key(workspacePath) {
    return `layouts.${workspacePath}`;
  }
}

module.exports = { LayoutStore };
