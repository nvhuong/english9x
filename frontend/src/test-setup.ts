import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { resetI18n } from './i18n';

// jsdom lacks these; Radix (Select, Dialog) calls them.
if (typeof Element !== 'undefined') {
  const proto = Element.prototype as unknown as Record<string, unknown>;
  proto.hasPointerCapture ??= () => false;
  proto.setPointerCapture ??= () => {};
  proto.releasePointerCapture ??= () => {};
  proto.scrollIntoView ??= () => {};
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

beforeEach(async () => {
  if (typeof window === 'undefined') return; // node-environment test files
  try {
    localStorage.clear();
  } catch {
    /* ignore */
  }
  await resetI18n('vi');
});
afterEach(() => cleanup());
