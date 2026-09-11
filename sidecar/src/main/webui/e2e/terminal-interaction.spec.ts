import { test, expect } from '@playwright/test';
import { focusTerminal, sendArrowDown, sendEnter, isTerminalConnected } from './helpers/terminal';

const ROOT = '/Users/mdproctor/claude/public/hortora';
const BASE = 'http://localhost:9777';

test.describe('terminal keyboard interaction', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto(`${BASE}/#?root=${ROOT}`);
    await page.waitForTimeout(5000);
  });

  test('dashboard loads with repos', async ({ page }) => {
    const heading = page.locator('text=hortora');
    await expect(heading.first()).toBeVisible();
    await expect(page.locator('text=Repos')).toBeVisible();
  });

  test('repo modal shows terminal after creating one', async ({ page }) => {
    await page.locator('text=trellis').first().click();
    await page.waitForTimeout(2000);

    const terminalBtn = page.getByRole('button', { name: 'Terminal' });
    if (await terminalBtn.isVisible()) {
      await terminalBtn.click();
      await page.waitForTimeout(3000);
    }

    const connected = await isTerminalConnected(page);
    expect(connected).toBe(true);
  });

  test('terminal accepts keyboard input via shadow DOM dispatch', async ({ page }) => {
    await page.locator('text=trellis').first().click();
    await page.waitForTimeout(2000);

    const terminalBtn = page.getByRole('button', { name: 'Terminal' });
    if (await terminalBtn.isVisible()) {
      await terminalBtn.click();
      await page.waitForTimeout(3000);
    }

    const focused = await focusTerminal(page);
    expect(focused).toBe(true);

    const sent = await sendArrowDown(page);
    expect(sent).toBe(true);
  });
});
