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

  async saveGroups(workspacePath, groups) {
    await this._ensureReady();
    this._store.set(this._key(workspacePath, 'groups'), groups);
  }

  async loadGroups(workspacePath) {
    await this._ensureReady();
    return this._store.get(this._key(workspacePath, 'groups')) || null;
  }

  async saveLayout(workspacePath, layout) {
    await this._ensureReady();
    this._store.set(this._key(workspacePath, 'layout'), layout);
  }

  async loadLayout(workspacePath) {
    await this._ensureReady();
    return this._store.get(this._key(workspacePath, 'layout')) || null;
  }

  async saveKeymap(workspacePath, keymap) {
    await this._ensureReady();
    this._store.set(this._key(workspacePath, 'keymap'), keymap);
  }

  async loadKeymap(workspacePath) {
    await this._ensureReady();
    return this._store.get(this._key(workspacePath, 'keymap')) || null;
  }

  async saveLastWorkspacePath(workspacePath) {
    await this._ensureReady();
    this._store.set('lastWorkspacePath', workspacePath);
  }

  async loadLastWorkspacePath() {
    await this._ensureReady();
    return this._store.get('lastWorkspacePath') || null;
  }

  async clear(workspacePath) {
    await this._ensureReady();
    this._store.delete(this._key(workspacePath, 'groups'));
    this._store.delete(this._key(workspacePath, 'layout'));
    this._store.delete(this._key(workspacePath, 'keymap'));
  }

  _key(workspacePath, prefix) {
    return `${prefix}.${workspacePath}`;
  }
}

module.exports = { LayoutStore };
