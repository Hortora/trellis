import { test, expect, Page } from '@playwright/test';

const ROOT = '/Users/mdproctor/claude/public/hortora';
const BASE = 'http://localhost:9777';

async function waitForDashboard(page: Page) {
  await page.goto(`${BASE}/#?root=${ROOT}`);
  await expect(page.locator('text=Repos')).toBeVisible({ timeout: 30_000 });
}

async function findInShadow(page: Page, tag: string): Promise<Record<string, unknown> | null> {
  return page.evaluate((t) => {
    function find(root: Document | ShadowRoot, tag: string): Element | null {
      const direct = root.querySelector(tag);
      if (direct) return direct;
      for (const child of root.querySelectorAll('*')) {
        if (child.shadowRoot) {
          const found = find(child.shadowRoot, tag);
          if (found) return found;
        }
      }
      return null;
    }
    const el = find(document, t) as any;
    if (!el) return null;
    return {
      loading: el._loading ?? null,
      error: el._error ?? null,
      repoName: el.repoName ?? null,
      slotNumber: el.slotNumber ?? null,
      navVersion: el._navigationVersion ?? null,
    };
  }, tag);
}

test.describe('modal navigation robustness', () => {

  test.beforeEach(async ({ page }) => {
    await waitForDashboard(page);
  });

  test('repo modal loads content — never stuck on Loading', async ({ page }) => {
    const repoCards = page.locator('text=trellis');
    await repoCards.first().click();

    // Must resolve from "Loading..." within 10 seconds
    await expect(
      page.getByRole('heading', { name: 'Path' })
    ).toBeVisible({ timeout: 10_000 });

    // Verify component state — _loading must be false
    const state = await findInShadow(page, 'trellis-repo-detail');
    expect(state).not.toBeNull();
    expect(state!.loading).toBe(false);
    expect(state!.repoName).toBe('trellis');
  });

  test('repo modal forward→back navigation shows correct content', async ({ page }) => {
    // Open first repo
    const firstCard = page.locator('.card').first();
    await firstCard.click();
    await expect(page.locator('text=PATH')).toBeVisible({ timeout: 10_000 });

    // Record which repo we're on
    const state1 = await findInShadow(page, 'trellis-repo-detail');
    const firstRepo = state1!.repoName as string;

    // Navigate forward
    await page.locator('button[title="Next"]').click();
    await expect(page.locator('text=PATH')).toBeVisible({ timeout: 10_000 });

    const state2 = await findInShadow(page, 'trellis-repo-detail');
    expect(state2!.repoName).not.toBe(firstRepo);
    expect(state2!.loading).toBe(false);

    // Navigate back
    await page.locator('button[title="Previous"]').click();
    await expect(page.locator('text=PATH')).toBeVisible({ timeout: 10_000 });

    const state3 = await findInShadow(page, 'trellis-repo-detail');
    expect(state3!.repoName).toBe(firstRepo);
    expect(state3!.loading).toBe(false);
  });

  test('repo modal rapid navigation does not show stale content', async ({ page }) => {
    const firstCard = page.locator('.card').first();
    await firstCard.click();
    await expect(page.locator('text=PATH')).toBeVisible({ timeout: 10_000 });

    // Rapid clicks: forward, forward, forward
    const nextBtn = page.locator('button[title="Next"]');
    await nextBtn.click();
    await nextBtn.click();
    await nextBtn.click();

    // Wait for final state to settle
    await page.waitForTimeout(3000);

    const state = await findInShadow(page, 'trellis-repo-detail');
    expect(state!.loading).toBe(false);

    // The nav position should show the 4th repo (1 + 3 forward)
    const navPos = await page.locator('.nav-pos').textContent();
    expect(navPos).toContain('4');
  });

  test('repo modal close and reopen works', async ({ page }) => {
    // Open
    const firstCard = page.locator('.card').first();
    await firstCard.click();
    await expect(page.locator('text=PATH')).toBeVisible({ timeout: 10_000 });

    // Close
    await page.locator('button:has-text("✕")').click();
    await page.waitForTimeout(500);

    // Reopen same card
    await firstCard.click();
    await expect(page.locator('text=PATH')).toBeVisible({ timeout: 10_000 });

    const state = await findInShadow(page, 'trellis-repo-detail');
    expect(state!.loading).toBe(false);
  });

  test('_navigationVersion initializes to 0, not undefined', async ({ page }) => {
    const firstCard = page.locator('.card').first();
    await firstCard.click();
    await page.waitForTimeout(2000);

    const state = await findInShadow(page, 'trellis-repo-detail');
    expect(state).not.toBeNull();
    // Must be a number, not null/undefined/NaN
    expect(typeof state!.navVersion).toBe('number');
    expect(Number.isNaN(state!.navVersion)).toBe(false);
  });
});
