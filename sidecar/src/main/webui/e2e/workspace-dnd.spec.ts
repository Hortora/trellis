import { test, expect, Page } from '@playwright/test';

const WS_URL = '/#workspace?root=/Users/mdproctor/claude/hortora/trellis';

async function ensureTerminals(page: Page) {
  for (const name of ['repo-alpha', 'repo-beta', 'repo-gamma']) {
    await page.request.post('/api/terminals', {
      data: { name, workingDir: '/tmp' },
    }).catch(() => {});
  }
}

async function createFrame(page: Page, tabs: string[]) {
  return page.evaluate((tabNames) => {
    const wb = document.querySelector('trellis-workbench')!;
    const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
    return wv.handleCommand('frame-create', {
      tabs: tabNames.map(n => ({ terminalName: n, type: 'repo' })),
    });
  }, tabs);
}

async function getFramePosition(page: Page, frameId: string) {
  return page.evaluate((fid) => {
    const wb = document.querySelector('trellis-workbench')!;
    const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
    const frame = wv._engine?.frames.get(fid);
    return frame ? frame.position : null;
  }, frameId);
}

async function getActiveTabIndex(page: Page, frameId: string) {
  return page.evaluate((fid) => {
    const wb = document.querySelector('trellis-workbench')!;
    const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
    const frame = wv._engine?.frames.get(fid);
    if (!frame) return 0;
    return Math.max(0, frame.tabs.findIndex((t: any) => t.key === frame.activeTabKey));
  }, frameId);
}

async function clearLayout(page: Page) {
  await page.request.put(
    '/api/layouts/workspace-frames?root=/Users/mdproctor/claude/hortora/trellis',
    { data: { windows: [] } },
  );
}

async function getFrameCount(page: Page) {
  return page.evaluate(() => {
    const wb = document.querySelector('trellis-workbench')!;
    const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
    return [...wv._engine.frames.values()].filter((f: any) => !f.hidden).length;
  });
}

test.describe('workspace DnD — real Dockview', () => {

  test.beforeEach(async ({ page }) => {
    await ensureTerminals(page);
    await clearLayout(page);
    await page.goto(WS_URL, { waitUntil: 'networkidle' });
    await page.waitForTimeout(500);
  });

  test('frame position persists across refresh', async ({ page }) => {
    const result = await createFrame(page, ['repo-alpha', 'repo-beta']);
    expect(result.ok).toBe(true);
    const frameId = result.frameId;
    await page.waitForTimeout(300);

    const origPos = await getFramePosition(page, frameId);
    expect(origPos).toBeTruthy();

    // Playwright auto-pierces open shadow DOM with plain CSS selectors
    const titlebar = page.locator('.dv-floating-titlebar').first();
    await expect(titlebar).toBeVisible({ timeout: 3000 });
    const box = await titlebar.boundingBox();
    expect(box).toBeTruthy();

    // Drag the frame to a new position
    await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
    await page.mouse.down();
    await page.mouse.move(box!.x + 150, box!.y + 100, { steps: 10 });
    await page.mouse.up();
    await page.waitForTimeout(1000);

    const movedPos = await getFramePosition(page, frameId);
    expect(movedPos!.x, 'frame must have moved').not.toBeCloseTo(origPos!.x, 0);

    // Refresh
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);

    // Find restored frame by terminal name
    const restored = await page.evaluate(() => {
      const wb = document.querySelector('trellis-workbench')!;
      const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
      for (const [fid, frame] of wv._engine.frames) {
        if (frame.tabs.some((t: any) => t.key === 'repo-alpha')) {
          return { frameId: fid, position: frame.position };
        }
      }
      return null;
    });

    expect(restored, 'frame must exist after refresh').toBeTruthy();
    expect(Math.abs(restored!.position.x - origPos!.x),
      'restored position must reflect the drag, not the original').toBeGreaterThan(50);
  });

  test('tab extraction creates new frame without breaking subsequent operations', async ({ page }) => {
    const result = await createFrame(page, ['repo-alpha', 'repo-beta', 'repo-gamma']);
    expect(result.ok).toBe(true);
    const frameId = result.frameId;
    await page.waitForTimeout(500);

    expect(await getFrameCount(page)).toBe(1);

    // Extract first tab via command API — removes from source, creates new frame
    const r1 = await page.evaluate((fid) => {
      const wb = document.querySelector('trellis-workbench')!;
      const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
      return wv.handleCommand('tab-remove', { frameId: fid, terminalName: 'repo-alpha' });
    }, frameId);
    expect(r1.ok).toBe(true);

    const c1 = await page.evaluate(() => {
      const wb = document.querySelector('trellis-workbench')!;
      const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
      return wv.handleCommand('frame-create', {
        tabs: [{ terminalName: 'repo-alpha', type: 'repo' }],
      });
    });
    expect(c1.ok).toBe(true);
    await page.waitForTimeout(300);

    const afterFirst = await getFrameCount(page);
    expect(afterFirst, 'extracting a tab must create a second frame').toBe(2);

    // Extract another tab from the original frame
    const r2 = await page.evaluate((fid) => {
      const wb = document.querySelector('trellis-workbench')!;
      const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
      return wv.handleCommand('tab-remove', { frameId: fid, terminalName: 'repo-beta' });
    }, frameId);
    expect(r2.ok).toBe(true);

    const c2 = await page.evaluate(() => {
      const wb = document.querySelector('trellis-workbench')!;
      const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
      return wv.handleCommand('frame-create', {
        tabs: [{ terminalName: 'repo-beta', type: 'repo' }],
      });
    });
    expect(c2.ok).toBe(true);
    await page.waitForTimeout(300);

    const afterSecond = await getFrameCount(page);
    expect(afterSecond, 'second extraction must create a third frame').toBe(3);
  });

  test('no same-frame drop indicators when dragging tab within frame', async ({ page }) => {
    const result = await createFrame(page, ['repo-alpha', 'repo-beta', 'repo-gamma']);
    expect(result.ok).toBe(true);
    await page.waitForTimeout(500);

    const tabs = page.locator('.dv-tab');
    await expect(tabs.first()).toBeVisible({ timeout: 3000 });

    const firstBox = await tabs.first().boundingBox();
    expect(firstBox).toBeTruthy();

    // Start drag and hold over second tab position
    await page.mouse.move(firstBox!.x + 10, firstBox!.y + 10);
    await page.mouse.down();
    await page.mouse.move(firstBox!.x + firstBox!.width + 20, firstBox!.y + 5, { steps: 5 });
    await page.waitForTimeout(200);

    // Count visible drop zones — check inside all shadow roots
    const visibleZones = await page.evaluate(() => {
      let count = 0;
      function scanRoot(root: Document | ShadowRoot) {
        root.querySelectorAll('.dv-drop-target-dropzone').forEach(el => {
          const style = window.getComputedStyle(el);
          if (style.display !== 'none' && style.visibility !== 'hidden') count++;
        });
        root.querySelectorAll('*').forEach(el => {
          if ((el as any).shadowRoot) scanRoot((el as any).shadowRoot);
        });
      }
      scanRoot(document);
      return count;
    });

    await page.mouse.up();

    expect(visibleZones, 'same-frame drop zones must be blocked').toBe(0);
  });

  test('active tab persists across refresh', async ({ page }) => {
    const result = await createFrame(page, ['repo-alpha', 'repo-beta', 'repo-gamma']);
    expect(result.ok).toBe(true);
    const frameId = result.frameId;
    await page.waitForTimeout(500);

    const tabs = page.locator('.dv-tab');
    await expect(tabs.first()).toBeVisible({ timeout: 3000 });
    await tabs.first().click();
    await page.waitForTimeout(1000);

    const activeIdx = await getActiveTabIndex(page, frameId);
    expect(activeIdx, 'clicking first tab must update active index').toBe(0);

    // Refresh
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);

    const restored = await page.evaluate(() => {
      const wb = document.querySelector('trellis-workbench')!;
      const wv = wb.shadowRoot!.querySelector('trellis-workspace-view')! as any;
      for (const [fid, frame] of wv._engine.frames) {
        if (frame.tabs.some((t: any) => t.key === 'repo-alpha')) {
          const idx = Math.max(0, frame.tabs.findIndex((t: any) => t.key === frame.activeTabKey));
          return { activeTab: idx };
        }
      }
      return null;
    });

    expect(restored).toBeTruthy();
    expect(restored!.activeTab, 'active tab must persist across refresh').toBe(0);
  });
});
