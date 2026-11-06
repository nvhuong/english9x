import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AudioPlayer } from './AudioPlayer';

let playImpl: () => Promise<void>;
const play = vi.fn(() => playImpl());
beforeEach(() => {
  playImpl = () => Promise.resolve();
  play.mockClear();
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(play);
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
});
afterEach(() => vi.restoreAllMocks());

const audio = (c: HTMLElement) => c.querySelector('audio') as HTMLAudioElement;

describe('AudioPlayer (listening: once, no pause/seek/replay)', () => {
  it('offers no pause, seek, replay or speed controls; only a read-only progress bar and volume', () => {
    const { container } = render(<AudioPlayer src="/a.mp3" />);
    expect(audio(container)).not.toHaveAttribute('controls');
    expect(screen.queryByRole('button', { name: /pause|tạm dừng|replay|phát lại|tua|seek|speed/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument(); // autoplay worked: no buttons at all
    expect(screen.getAllByRole('slider')).toHaveLength(1);
    expect(screen.getByRole('slider', { name: 'Âm lượng' })).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Tiến độ phát' })).toHaveAttribute('aria-valuenow', '0');
  });

  it('starts by itself and reports start once', async () => {
    const onStart = vi.fn();
    const { container } = render(<AudioPlayer src="/a.mp3" onStart={onStart} />);
    await act(async () => {});
    expect(play).toHaveBeenCalledTimes(1);
    fireEvent.play(audio(container));
    fireEvent.play(audio(container));
    expect(onStart).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('status')).toHaveTextContent('Đang phát — chỉ phát một lần');
  });

  it('shows a single Start button when the browser blocks autoplay', async () => {
    playImpl = () => Promise.reject(new DOMException('blocked', 'NotAllowedError'));
    render(<AudioPlayer src="/a.mp3" />);
    const start = await screen.findByRole('button', { name: 'Bắt đầu phát' });
    expect(screen.getByRole('status')).toHaveTextContent('Trình duyệt chặn tự động phát');
    playImpl = () => Promise.resolve();
    await userEvent.setup().click(start);
    expect(play).toHaveBeenCalledTimes(2);
  });

  it('undoes seeking and playback-rate changes', () => {
    const { container } = render(<AudioPlayer src="/a.mp3" />);
    const el = audio(container);
    Object.defineProperty(el, 'duration', { value: 100, configurable: true });
    el.currentTime = 40;
    fireEvent.timeUpdate(el);
    el.currentTime = 90;
    fireEvent.seeking(el);
    expect(el.currentTime).toBe(40);
    el.playbackRate = 2;
    fireEvent.rateChange(el);
    expect(el.playbackRate).toBe(1);
  });

  it('resumes if something pauses it mid-clip', async () => {
    const { container } = render(<AudioPlayer src="/a.mp3" />);
    await act(async () => {});
    play.mockClear();
    fireEvent.pause(audio(container));
    expect(play).toHaveBeenCalledTimes(1);
  });

  it('after the end: reports ended, offers no replay, and a later play is stopped', async () => {
    const onEnded = vi.fn();
    const { container } = render(<AudioPlayer src="/a.mp3" onEnded={onEnded} />);
    await act(async () => {});
    fireEvent.ended(audio(container));
    expect(onEnded).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('status')).toHaveTextContent('Đã phát xong');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    const pause = HTMLMediaElement.prototype.pause as ReturnType<typeof vi.fn>;
    fireEvent.play(audio(container));
    expect(pause).toHaveBeenCalled();
    play.mockClear();
    fireEvent.pause(audio(container));
    expect(play).not.toHaveBeenCalled();
  });
});
