import { beforeEach, describe, expect, it, vi } from 'vitest';
import { safeNext } from './problems';
import { forgetReturn, peekReturn, rememberReturn, takeReturn } from './returnTo';

const KEY = 'e9x.auth.returnTo';
beforeEach(() => {
  localStorage.clear();
  vi.useRealTimers();
});

describe('safeNext', () => {
  it.each(['//evil.example/x', '/\\evil.example', 'https://evil.example', 'javascript:alert(1)', '/\t/evil.example', '/\n/evil.example'])(
    'refuses %j',
    (bad) => expect(safeNext(`?next=${encodeURIComponent(bad)}`)).toBe('/'),
  );
  it('keeps a same-site path with its query', () => {
    expect(safeNext(`?next=${encodeURIComponent('/accept-invitation?token=abc')}`)).toBe('/accept-invitation?token=abc');
  });
});

describe('the way back after a detour through registration, the mailbox or Google', () => {
  it('survives until taken, and is taken once', () => {
    rememberReturn('/accept-invitation?token=abc');
    expect(peekReturn()).toBe('/accept-invitation?token=abc');
    expect(takeReturn()).toBe('/accept-invitation?token=abc');
    expect(takeReturn()).toBe('/');
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('never stores or returns an off-site destination, even one written straight into storage', () => {
    rememberReturn('//evil.example/x');
    expect(localStorage.getItem(KEY)).toBeNull();
    localStorage.setItem(KEY, JSON.stringify({ path: 'https://evil.example', at: Date.now() }));
    expect(peekReturn()).toBeNull();
    expect(takeReturn('/home')).toBe('/home');
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('the home page is not worth remembering', () => {
    rememberReturn('/');
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('expires after a day, the life of the verification link', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-07T00:00:00Z'));
    rememberReturn('/accept-invitation?token=abc');
    vi.setSystemTime(new Date('2026-11-07T23:59:00Z'));
    expect(peekReturn()).not.toBeNull();
    vi.setSystemTime(new Date('2026-11-08T00:01:00Z'));
    expect(peekReturn()).toBeNull();
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('garbage in storage is ignored', () => {
    localStorage.setItem(KEY, 'not json');
    expect(peekReturn()).toBeNull();
    forgetReturn();
    expect(localStorage.getItem(KEY)).toBeNull();
  });
});
