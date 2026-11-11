import { afterEach, describe, expect, it, vi } from 'vitest';
import { parseDuration } from './examApi';

describe('parseDuration', () => {
  it('reads the server duration texts and does not guess at bare numbers', () => {
    expect(parseDuration('5s')).toBe(5000);
    expect(parseDuration('500ms')).toBe(500);
    expect(parseDuration('1m')).toBe(60000);
    expect(parseDuration(500)).toBeNull();
    expect(parseDuration('soon')).toBeNull();
    expect(parseDuration('0s')).toBeNull();
  });
});

describe('autosaveIntervalMs', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it('reads examAutosaveIntervalSeconds from /api/config/public as integer seconds', async () => {
    vi.resetModules();
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ examAutosaveIntervalSeconds: 8, learningHeartbeatIntervalSeconds: 30 }) })));
    const { autosaveIntervalMs } = await import('./examApi');
    expect(await autosaveIntervalMs()).toBe(8000);
  });

  it('falls back to 5 s when the server does not answer', async () => {
    vi.resetModules();
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, json: async () => ({}) })));
    const { autosaveIntervalMs } = await import('./examApi');
    expect(await autosaveIntervalMs()).toBe(5000);
  });

  it('falls back to 5 s when the field is missing or not positive', async () => {
    vi.resetModules();
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ examAutosaveIntervalSeconds: 0 }) })));
    const { autosaveIntervalMs } = await import('./examApi');
    expect(await autosaveIntervalMs()).toBe(5000);
  });
});
