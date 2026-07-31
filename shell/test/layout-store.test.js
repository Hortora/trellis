// layout-store.test.js
'use strict';

const mockStore = {};
const mockGet = jest.fn((key) => mockStore[key]);
const mockSet = jest.fn((key, value) => { mockStore[key] = value; });
const mockDelete = jest.fn((key) => { delete mockStore[key]; });

jest.mock('electron-store', () => {
  return jest.fn(() => ({
    get: mockGet,
    set: mockSet,
    delete: mockDelete,
  }));
});

const { LayoutStore } = require('../layout-store');

beforeEach(() => {
  for (const key of Object.keys(mockStore)) delete mockStore[key];
  mockGet.mockClear();
  mockSet.mockClear();
  mockDelete.mockClear();
});

describe('LayoutStore', () => {
  test('save persists layout keyed by workspace path', () => {
    const store = new LayoutStore();
    const layout = {
      windows: [
        { id: 1, bounds: { x: 0, y: 0, width: 1400, height: 900 }, route: '/dashboard' },
      ],
      panels: { 'terminal-1': 1 },
      tabs: [{ windowId: 1, order: ['dashboard', 'terminal-1'] }],
    };

    store.save('/home/dev/project', layout);

    expect(mockSet).toHaveBeenCalledWith(
      'layouts./home/dev/project',
      layout
    );
  });

  test('load returns saved layout', () => {
    const store = new LayoutStore();
    const layout = {
      windows: [{ id: 1, bounds: { x: 0, y: 0, width: 1400, height: 900 }, route: '/dashboard' }],
      panels: {},
      tabs: [],
    };
    mockStore['layouts./home/dev/project'] = layout;

    const result = store.load('/home/dev/project');

    expect(result).toEqual(layout);
  });

  test('load returns null for unknown workspace', () => {
    const store = new LayoutStore();

    const result = store.load('/unknown/path');

    expect(result).toBeNull();
  });

  test('clear removes saved layout', () => {
    const store = new LayoutStore();
    mockStore['layouts./home/dev/project'] = { windows: [] };

    store.clear('/home/dev/project');

    expect(mockDelete).toHaveBeenCalledWith('layouts./home/dev/project');
  });

  test('save validates layout has required fields', () => {
    const store = new LayoutStore();

    expect(() => store.save('/path', {})).toThrow('Layout must have windows array');
    expect(() => store.save('/path', { windows: 'bad' })).toThrow('Layout must have windows array');
  });

  test('save with display info includes screen metadata', () => {
    const store = new LayoutStore();
    const layout = {
      windows: [
        {
          id: 1,
          bounds: { x: 0, y: 0, width: 1400, height: 900 },
          route: '/dashboard',
          displayId: 1,
        },
      ],
      panels: {},
      tabs: [],
    };

    store.save('/home/dev/project', layout);

    const saved = mockSet.mock.calls[0][1];
    expect(saved.windows[0].displayId).toBe(1);
  });

  test('multiple workspaces stored independently', () => {
    const store = new LayoutStore();
    const layout1 = { windows: [{ id: 1 }], panels: {}, tabs: [] };
    const layout2 = { windows: [{ id: 2 }], panels: {}, tabs: [] };

    store.save('/project-a', layout1);
    store.save('/project-b', layout2);

    expect(mockSet).toHaveBeenCalledWith('layouts./project-a', layout1);
    expect(mockSet).toHaveBeenCalledWith('layouts./project-b', layout2);
  });
});
