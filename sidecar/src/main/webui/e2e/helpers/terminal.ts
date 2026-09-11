import { Page } from '@playwright/test';

/**
 * Walks nested shadow DOMs to find the first pages-component-terminal element
 * and its xterm textarea. Playwright's native keyboard APIs can't dispatch
 * through shadow DOM boundaries, so we dispatch directly on the textarea.
 */
async function findTerminalTextarea(page: Page): Promise<string | null> {
  return page.evaluate(() => {
    function findAll(root: Document | ShadowRoot, tag: string): Element[] {
      let results = Array.from(root.querySelectorAll(tag));
      for (const child of root.querySelectorAll('*')) {
        if (child.shadowRoot) {
          results = results.concat(findAll(child.shadowRoot, tag));
        }
      }
      return results;
    }
    const terminal = findAll(document, 'pages-component-terminal')[0] as any;
    if (!terminal) return null;
    const textarea = terminal.querySelector('textarea.xterm-helper-textarea');
    if (!textarea) return null;
    textarea.setAttribute('data-pw-terminal', 'true');
    return 'found';
  });
}

export async function focusTerminal(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    function findAll(root: Document | ShadowRoot, tag: string): Element[] {
      let results = Array.from(root.querySelectorAll(tag));
      for (const child of root.querySelectorAll('*')) {
        if (child.shadowRoot) {
          results = results.concat(findAll(child.shadowRoot, tag));
        }
      }
      return results;
    }
    const terminal = findAll(document, 'pages-component-terminal')[0] as any;
    if (!terminal) return false;
    const textarea = terminal.querySelector('textarea.xterm-helper-textarea') as HTMLTextAreaElement;
    if (!textarea) return false;
    textarea.focus();
    return true;
  });
}

export async function sendKey(page: Page, key: string, code?: string, keyCode?: number): Promise<boolean> {
  return page.evaluate(({ key, code, keyCode }) => {
    function findAll(root: Document | ShadowRoot, tag: string): Element[] {
      let results = Array.from(root.querySelectorAll(tag));
      for (const child of root.querySelectorAll('*')) {
        if (child.shadowRoot) {
          results = results.concat(findAll(child.shadowRoot, tag));
        }
      }
      return results;
    }
    const terminal = findAll(document, 'pages-component-terminal')[0] as any;
    if (!terminal) return false;
    const textarea = terminal.querySelector('textarea.xterm-helper-textarea');
    if (!textarea) return false;
    textarea.focus();
    textarea.dispatchEvent(new KeyboardEvent('keydown', {
      key, code: code || key, keyCode: keyCode || 0,
      bubbles: true, cancelable: true,
    }));
    return true;
  }, { key, code, keyCode });
}

export async function sendArrowDown(page: Page): Promise<boolean> {
  return sendKey(page, 'ArrowDown', 'ArrowDown', 40);
}

export async function sendArrowUp(page: Page): Promise<boolean> {
  return sendKey(page, 'ArrowUp', 'ArrowUp', 38);
}

export async function sendEnter(page: Page): Promise<boolean> {
  return sendKey(page, 'Enter', 'Enter', 13);
}

export async function sendEscape(page: Page): Promise<boolean> {
  return sendKey(page, 'Escape', 'Escape', 27);
}

export async function sendText(page: Page, text: string): Promise<boolean> {
  return page.evaluate((text) => {
    function findAll(root: Document | ShadowRoot, tag: string): Element[] {
      let results = Array.from(root.querySelectorAll(tag));
      for (const child of root.querySelectorAll('*')) {
        if (child.shadowRoot) {
          results = results.concat(findAll(child.shadowRoot, tag));
        }
      }
      return results;
    }
    const terminal = findAll(document, 'pages-component-terminal')[0] as any;
    if (!terminal?._ws) return false;
    terminal._ws.send(text);
    return true;
  }, text);
}

export async function isTerminalConnected(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    function findAll(root: Document | ShadowRoot, tag: string): Element[] {
      let results = Array.from(root.querySelectorAll(tag));
      for (const child of root.querySelectorAll('*')) {
        if (child.shadowRoot) {
          results = results.concat(findAll(child.shadowRoot, tag));
        }
      }
      return results;
    }
    const terminal = findAll(document, 'pages-component-terminal')[0] as any;
    return !!terminal?._terminal && !!terminal?._ws && terminal._ws.readyState === 1;
  });
}
