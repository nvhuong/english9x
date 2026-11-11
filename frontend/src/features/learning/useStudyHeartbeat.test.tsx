import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useStudyHeartbeat } from './useStudyHeartbeat';

let visibility: DocumentVisibilityState = 'visible';
let clock = 0;

beforeEach(() => {
  vi.useFakeTimers();
  clock = 0;
  visibility = 'visible';
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility });
});
afterEach(() => vi.useRealTimers());

function advance(ms: number) {
  act(() => {
    clock += ms;
    vi.advanceTimersByTime(ms);
  });
}

describe('useStudyHeartbeat', () => {
  it('sends every interval while the page is visible and used', () => {
    const send = vi.fn();
    renderHook(() => useStudyHeartbeat({ send, now: () => clock }));
    advance(30_000);
    advance(30_000);
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('sends nothing while the tab is hidden, and resumes when it is visible again', () => {
    const send = vi.fn();
    renderHook(() => useStudyHeartbeat({ send, now: () => clock }));
    visibility = 'hidden';
    advance(30_000);
    advance(30_000);
    expect(send).not.toHaveBeenCalled();
    visibility = 'visible';
    advance(30_000);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('stops two minutes after the last interaction and resumes on the next one', () => {
    const send = vi.fn();
    renderHook(() => useStudyHeartbeat({ send, now: () => clock }));
    advance(120_000); // 4 signals: the last interaction (opening the page) is exactly two minutes old
    expect(send).toHaveBeenCalledTimes(4);
    advance(30_000); // 2m30s without interaction
    advance(30_000);
    expect(send).toHaveBeenCalledTimes(4);
    act(() => {
      window.dispatchEvent(new Event('keydown'));
    });
    advance(30_000);
    expect(send).toHaveBeenCalledTimes(5);
  });

  it('a failed send does not stop the next one', async () => {
    const send = vi.fn().mockRejectedValue(new Error('offline'));
    renderHook(() => useStudyHeartbeat({ send, now: () => clock }));
    advance(30_000);
    advance(30_000);
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('sends nothing when disabled and cleans up on unmount', () => {
    const send = vi.fn();
    const off = renderHook(() => useStudyHeartbeat({ send, enabled: false, now: () => clock }));
    advance(60_000);
    expect(send).not.toHaveBeenCalled();
    off.unmount();
    const on = renderHook(() => useStudyHeartbeat({ send, now: () => clock }));
    on.unmount();
    advance(60_000);
    expect(send).not.toHaveBeenCalled();
  });
});
