import { describe, it, expect, beforeEach, afterEach } from 'vitest';

async function createElement(props: Record<string, unknown> = {}) {
  await import('./terminal-pair-view.js');
  const el = document.createElement('trellis-terminal-pair-view') as any;
  Object.assign(el, props);
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const REPL = { name: 'REPL', sessionName: 'repl-engine' };
const AGENT = { name: 'Agent', sessionName: 'repo-engine', agentState: 'RUNNING', memoryMb: 312 };

describe('trellis-terminal-pair-view', () => {

  let el: any;
  afterEach(() => { el?.remove(); });

  describe('split mode (default)', () => {

    it('renders two terminal elements when both entries provided', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT });
      const terminals = el.shadowRoot!.querySelectorAll('pages-component-terminal');
      expect(terminals.length).toBe(2);
    });

    it('renders a split container with two panes', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT });
      const panes = el.shadowRoot!.querySelectorAll('.split-pane');
      expect(panes.length).toBe(2);
    });

    it('labels each pane with the entry name', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT });
      const labels = el.shadowRoot!.querySelectorAll('.pane-label');
      expect(labels[0].textContent).toContain('REPL');
      expect(labels[1].textContent).toContain('Agent');
    });

    it('shows agent status badge on secondary pane', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT });
      const badges = el.shadowRoot!.querySelectorAll('agent-status-badge');
      expect(badges.length).toBe(1);
    });
  });

  describe('single entry fallback', () => {

    it('renders one terminal when only primary provided', async () => {
      el = await createElement({ primary: REPL });
      const terminals = el.shadowRoot!.querySelectorAll('pages-component-terminal');
      expect(terminals.length).toBe(1);
    });

    it('hides mode toggle when only one entry', async () => {
      el = await createElement({ primary: REPL });
      const toggle = el.shadowRoot!.querySelector('.mode-toggle');
      expect(toggle).toBeNull();
    });
  });

  describe('tab mode', () => {

    it('renders only one terminal in tab mode', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT, mode: 'tab' });
      const terminals = el.shadowRoot!.querySelectorAll('pages-component-terminal');
      expect(terminals.length).toBe(1);
    });

    it('renders tab bar with two tabs', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT, mode: 'tab' });
      const tabs = el.shadowRoot!.querySelectorAll('.tab-bar .tab');
      expect(tabs.length).toBe(2);
      expect(tabs[0].textContent).toContain('REPL');
      expect(tabs[1].textContent).toContain('Agent');
    });

    it('first tab is active by default', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT, mode: 'tab' });
      const tabs = el.shadowRoot!.querySelectorAll('.tab-bar .tab');
      expect(tabs[0].classList.contains('active')).toBe(true);
      expect(tabs[1].classList.contains('active')).toBe(false);
    });

    it('clicking second tab switches active terminal', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT, mode: 'tab' });
      const tabs = el.shadowRoot!.querySelectorAll('.tab-bar .tab');
      (tabs[1] as HTMLElement).click();
      await el.updateComplete;
      expect(tabs[1].classList.contains('active')).toBe(true);
    });
  });

  describe('mode toggle', () => {

    it('renders mode toggle button when both entries provided', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT });
      const toggle = el.shadowRoot!.querySelector('.mode-toggle');
      expect(toggle).not.toBeNull();
    });

    it('clicking toggle switches from split to tab', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT });
      const toggle = el.shadowRoot!.querySelector('.mode-toggle') as HTMLElement;
      toggle.click();
      await el.updateComplete;
      const terminals = el.shadowRoot!.querySelectorAll('pages-component-terminal');
      expect(terminals.length).toBe(1);
      const tabs = el.shadowRoot!.querySelectorAll('.tab-bar .tab');
      expect(tabs.length).toBe(2);
    });

    it('clicking toggle twice returns to split', async () => {
      el = await createElement({ primary: REPL, secondary: AGENT });
      const toggle = el.shadowRoot!.querySelector('.mode-toggle') as HTMLElement;
      toggle.click();
      await el.updateComplete;
      toggle.click();
      await el.updateComplete;
      const terminals = el.shadowRoot!.querySelectorAll('pages-component-terminal');
      expect(terminals.length).toBe(2);
    });
  });
});
