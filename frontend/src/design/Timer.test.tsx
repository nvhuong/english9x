import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Timer, formatClock, timerLevel } from './Timer';

const T0 = 1_700_000_000_000;
beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
});
afterEach(() => vi.useRealTimers());

const tick = (ms: number) => act(() => void vi.advanceTimersByTime(ms));

describe('Timer helpers', () => {
  it('formats and classifies', () => {
    expect(formatClock(65_000)).toBe('01:05');
    expect(formatClock(3_725_000)).toBe('1:02:05');
    expect(formatClock(-5)).toBe('00:00');
    expect(timerLevel(6 * 60_000)).toBe('ok');
    expect(timerLevel(5 * 60_000)).toBe('warn5');
    expect(timerLevel(60_000)).toBe('warn1');
    expect(timerLevel(0)).toBe('expired');
  });
});

describe('Timer', () => {
  it('counts down to the server deadline and is exposed as a timer', () => {
    render(<Timer deadline={T0 + 10 * 60_000} />);
    const timer = screen.getByRole('timer', { name: 'Thời gian còn lại' });
    expect(timer).toHaveTextContent('10:00');
    tick(61_000);
    expect(timer).toHaveTextContent('08:59');
  });

  it('warns once at 5 minutes and once at 1 minute, announced politely and visible as text', () => {
    const onWarn = vi.fn();
    const onExpire = vi.fn();
    const { container } = render(<Timer deadline={T0 + 6 * 60_000} onWarn={onWarn} onExpire={onExpire} />);
    expect(screen.getByRole('status')).toHaveTextContent('');
    tick(61_000); // 4:59 left
    expect(onWarn).toHaveBeenCalledTimes(1);
    expect(onWarn).toHaveBeenLastCalledWith('warn5');
    expect(screen.getByRole('status')).toHaveTextContent('Còn dưới 5 phút');
    expect(container.firstElementChild).toHaveAttribute('data-level', 'warn5');
    tick(240_000); // 0:59 left
    expect(onWarn).toHaveBeenLastCalledWith('warn1');
    expect(screen.getByRole('status')).toHaveTextContent('Còn dưới 1 phút');
    tick(30_000);
    expect(onWarn).toHaveBeenCalledTimes(2);
    tick(30_000);
    expect(onExpire).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('status')).toHaveTextContent('Hết giờ');
    tick(10_000);
    expect(onExpire).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('timer')).toHaveTextContent('00:00');
  });

  it('corrects client clock skew with serverOffsetMs', () => {
    // Client clock is 2 minutes behind the server: server now = client now + 120 s.
    render(<Timer deadline={T0 + 10 * 60_000} serverOffsetMs={120_000} />);
    expect(screen.getByRole('timer')).toHaveTextContent('08:00');
  });

  it('accepts an ISO deadline and does not announce every second', () => {
    render(<Timer deadline={new Date(T0 + 20 * 60_000).toISOString()} />);
    expect(screen.getByRole('timer')).toHaveTextContent('20:00');
    expect(screen.getByRole('timer')).not.toHaveAttribute('aria-live', 'polite');
    expect(screen.getByRole('status')).toHaveTextContent('');
  });
});
