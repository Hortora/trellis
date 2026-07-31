// window-manager.test.js
'use strict';

function makeMockWindow(id) {
  const win = {
    id,
    webContents: { id: id * 10 },
    loadURL: jest.fn().mockResolvedValue(undefined),
    show: jest.fn(),
    close: jest.fn(),
    isDestroyed: jest.fn(() => false),
    getBounds: jest.fn(() => ({ x: 100, y: 100, width: 1400, height: 900 })),
    setBounds: jest.fn(),
    on: jest.fn(),
    once: jest.fn(),
    removeAllListeners: jest.fn(),
  };
  return win;
}

let windowIdCounter = 0;
const mockBrowserWindow = jest.fn(() => makeMockWindow(++windowIdCounter));

jest.mock('electron', () => ({
  BrowserWindow: mockBrowserWindow,
  screen: {
    getAllDisplays: jest.fn(() => [
      { id: 1, bounds: { x: 0, y: 0, width: 1920, height: 1080 } },
      { id: 2, bounds: { x: 1920, y: 0, width: 2560, height: 1440 } },
    ]),
    getDisplayNearestPoint: jest.fn(() => ({
      id: 1, bounds: { x: 0, y: 0, width: 1920, height: 1080 },
    })),
  },
  ipcMain: { handle: jest.fn(), on: jest.fn(), removeHandler: jest.fn() },
}));

const { WindowManager } = require('../window-manager');

beforeEach(() => {
  windowIdCounter = 0;
  mockBrowserWindow.mockClear();
});

describe('WindowManager', () => {
  test('createWindow creates a BrowserWindow and tracks it', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    const win = await wm.createWindow('/dashboard');

    expect(mockBrowserWindow).toHaveBeenCalledTimes(1);
    expect(win.loadURL).toHaveBeenCalledWith('http://127.0.0.1:3000/#/dashboard');
    expect(win.show).toHaveBeenCalled();
    expect(wm.getWindows()).toHaveLength(1);
  });

  test('createWindow with opts passes size and position', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    await wm.createWindow('/dashboard', { width: 800, height: 600, x: 50, y: 50 });

    expect(mockBrowserWindow).toHaveBeenCalledWith(
      expect.objectContaining({ width: 800, height: 600, x: 50, y: 50 })
    );
  });

  test('closing a window removes it from tracking', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    await wm.createWindow('/dashboard');
    expect(wm.getWindows()).toHaveLength(1);

    const closedHandler = mockBrowserWindow.mock.results[0].value.on.mock.calls
      .find(([event]) => event === 'closed')[1];
    closedHandler();

    expect(wm.getWindows()).toHaveLength(0);
  });

  test('detachPanel creates a new window for the panel', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    await wm.createWindow('/dashboard');

    const newWin = await wm.detachPanel('terminal-1', { route: '/terminal/1' });

    expect(mockBrowserWindow).toHaveBeenCalledTimes(2);
    expect(newWin.loadURL).toHaveBeenCalledWith('http://127.0.0.1:3000/#/terminal/1');
    expect(wm.getWindows()).toHaveLength(2);
    expect(wm.getPanelWindow('terminal-1')).toBe(newWin);
  });

  test('detachPanel with bounds positions the new window', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    await wm.createWindow('/dashboard');

    await wm.detachPanel('terminal-1', {
      route: '/terminal/1',
      bounds: { x: 200, y: 200, width: 600, height: 400 },
    });

    expect(mockBrowserWindow).toHaveBeenLastCalledWith(
      expect.objectContaining({ x: 200, y: 200, width: 600, height: 400 })
    );
  });

  test('attachPanel removes panel tracking and closes the detached window', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    const mainWin = await wm.createWindow('/dashboard');
    const detachedWin = await wm.detachPanel('terminal-1', { route: '/terminal/1' });

    wm.attachPanel('terminal-1', mainWin.id);

    expect(wm.getPanelWindow('terminal-1')).toBeUndefined();
    expect(detachedWin.close).toHaveBeenCalled();
  });

  test('attachPanel to non-existent window throws', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    await wm.createWindow('/dashboard');
    await wm.detachPanel('terminal-1', { route: '/terminal/1' });

    expect(() => wm.attachPanel('terminal-1', 999)).toThrow('Window 999 not found');
  });

  test('detaching an already-detached panel throws', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    await wm.createWindow('/dashboard');
    await wm.detachPanel('terminal-1', { route: '/terminal/1' });

    await expect(wm.detachPanel('terminal-1', { route: '/terminal/1' }))
      .rejects.toThrow('Panel terminal-1 is already detached');
  });

  test('getLayout returns all window bounds and panel assignments', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    await wm.createWindow('/dashboard');
    await wm.detachPanel('terminal-1', { route: '/terminal/1' });

    const layout = wm.getLayout();
    expect(layout.windows).toHaveLength(2);
    expect(layout.panels).toEqual({ 'terminal-1': expect.any(Number) });
  });

  test('closeAll closes every tracked window', async () => {
    const wm = new WindowManager({ port: 3000, preloadPath: '/preload.js' });
    await wm.createWindow('/dashboard');
    await wm.createWindow('/settings');

    wm.closeAll();

    const wins = mockBrowserWindow.mock.results;
    expect(wins[0].value.close).toHaveBeenCalled();
    expect(wins[1].value.close).toHaveBeenCalled();
  });
});
