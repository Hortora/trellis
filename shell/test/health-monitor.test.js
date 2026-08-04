// health-monitor.test.js
'use strict';

const { HealthMonitor } = require('../health-monitor');

describe('HealthMonitor', () => {
  let monitor;

  afterEach(() => {
    if (monitor) monitor.stop();
  });

  test('emits "down" when health check fails', async () => {
    const fetcher = jest.fn().mockRejectedValue(new Error('ECONNREFUSED'));
    monitor = new HealthMonitor({ port: 3000, intervalMs: 50, fetcher });

    const down = new Promise(resolve => monitor.on('down', resolve));
    monitor.start();
    await down;

    expect(fetcher).toHaveBeenCalled();
  });

  test('emits "recovered" when health returns after being down', async () => {
    let callCount = 0;
    const fetcher = jest.fn().mockImplementation(() => {
      callCount++;
      if (callCount <= 2) return Promise.reject(new Error('ECONNREFUSED'));
      return Promise.resolve(200);
    });

    monitor = new HealthMonitor({ port: 3000, intervalMs: 50, fetcher });

    const recovered = new Promise(resolve => monitor.on('recovered', resolve));
    monitor.start();
    await recovered;

    expect(callCount).toBeGreaterThanOrEqual(3);
  });

  test('does not emit "down" when health check succeeds', async () => {
    const fetcher = jest.fn().mockResolvedValue(200);
    monitor = new HealthMonitor({ port: 3000, intervalMs: 50, fetcher });

    const downSpy = jest.fn();
    monitor.on('down', downSpy);
    monitor.start();

    await new Promise(resolve => setTimeout(resolve, 200));

    expect(downSpy).not.toHaveBeenCalled();
  });

  test('does not emit duplicate "down" events', async () => {
    const fetcher = jest.fn().mockRejectedValue(new Error('ECONNREFUSED'));
    monitor = new HealthMonitor({ port: 3000, intervalMs: 50, fetcher });

    const downSpy = jest.fn();
    monitor.on('down', downSpy);
    monitor.start();

    await new Promise(resolve => setTimeout(resolve, 250));

    expect(downSpy).toHaveBeenCalledTimes(1);
  });

  test('stop prevents further checks', async () => {
    const fetcher = jest.fn().mockResolvedValue(200);
    monitor = new HealthMonitor({ port: 3000, intervalMs: 50, fetcher });

    monitor.start();
    await new Promise(resolve => setTimeout(resolve, 100));
    const countBeforeStop = fetcher.mock.calls.length;
    monitor.stop();

    await new Promise(resolve => setTimeout(resolve, 150));
    expect(fetcher.mock.calls.length).toBe(countBeforeStop);
  });
});
