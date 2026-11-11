import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SectionTimer } from './SectionTimer';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('SectionTimer', () => {
  it('usesServerOffset: counts to endsAt on the server clock, whatever this computer thinks the time is; the computer clock changing does nothing', () => {
    // the computer is an hour and a bit behind the server
    vi.setSystemTime(new Date('2026-11-10T08:00:00Z'));
    let perf = 1000;
    const perfNow = () => perf;
    render(<SectionTimer serverNow="2026-11-10T09:30:00Z" endsAt="2026-11-10T09:40:00Z" perfNow={perfNow} />);
    const timer = screen.getByRole('timer');
    expect(timer).toHaveTextContent('10:00');
    perf += 61_000;
    act(() => void vi.advanceTimersByTime(61_000));
    expect(timer).toHaveTextContent('08:59');
    // somebody sets the computer clock forward a day: the exam clock does not move
    vi.setSystemTime(new Date('2026-11-11T08:00:00Z'));
    act(() => void vi.advanceTimersByTime(250));
    expect(timer).toHaveTextContent('08:59');
  });

  it('recomputes when the tab becomes visible again and asks for a fresh server time', () => {
    let perf = 0;
    const onResync = vi.fn();
    render(<SectionTimer serverNow="2026-11-10T09:30:00Z" endsAt="2026-11-10T09:40:00Z" perfNow={() => perf} onResync={onResync} />);
    perf += 120_000; // the background tab's timers did not run, the monotonic clock did
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    expect(screen.getByRole('timer')).toHaveTextContent('08:00');
    expect(onResync).toHaveBeenCalledTimes(1);
  });

  it('warns at 5 minutes and 1 minute', () => {
    let perf = 0;
    const onWarn = vi.fn();
    render(<SectionTimer serverNow="2026-11-10T09:30:00Z" endsAt="2026-11-10T09:36:00Z" perfNow={() => perf} onWarn={onWarn} />);
    perf += 61_000;
    act(() => void vi.advanceTimersByTime(61_000));
    expect(onWarn).toHaveBeenCalledWith('warn5');
    perf += 240_000;
    act(() => void vi.advanceTimersByTime(240_000));
    expect(onWarn).toHaveBeenCalledWith('warn1');
  });
});
