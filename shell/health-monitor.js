// health-monitor.js
'use strict';
const http = require('http');
const { EventEmitter } = require('events');

function defaultFetcher(port) {
  return new Promise((resolve, reject) => {
    const req = http.get(`http://127.0.0.1:${port}/api/health`, (res) => {
      res.resume();
      resolve(res.statusCode);
    });
    req.on('error', reject);
    req.setTimeout(2000, () => { req.destroy(); reject(new Error('timeout')); });
  });
}

class HealthMonitor extends EventEmitter {
  constructor({ port, intervalMs = 5000, fetcher } = {}) {
    super();
    this._port = port;
    this._intervalMs = intervalMs;
    this._fetcher = fetcher || (() => defaultFetcher(port));
    this._timer = null;
    this._wasDown = false;
  }

  start() {
    this._check();
  }

  stop() {
    if (this._timer) {
      clearTimeout(this._timer);
      this._timer = null;
    }
  }

  async _check() {
    try {
      await this._fetcher(this._port);
      if (this._wasDown) {
        this._wasDown = false;
        this.emit('recovered');
      }
    } catch {
      if (!this._wasDown) {
        this._wasDown = true;
        this.emit('down');
      }
    }
    this._timer = setTimeout(() => this._check(), this._intervalMs);
  }
}

module.exports = { HealthMonitor };
