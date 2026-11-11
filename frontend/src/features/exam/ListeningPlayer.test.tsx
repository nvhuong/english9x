import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExamHttpError, examApi } from './examApi';
import { ListeningPlayer, resetAudioLinks } from './ListeningPlayer';

beforeEach(() => {
  resetAudioLinks();
  vi.restoreAllMocks();
  // jsdom has no media playback
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
});

describe('ListeningPlayer', () => {
  it('hasNoSeekControls: only a volume slider, no pause/seek/replay/speed, no native controls, and the media keys are taken away', async () => {
    const handlers: Record<string, unknown> = {};
    Object.defineProperty(navigator, 'mediaSession', {
      configurable: true,
      value: { setActionHandler: (a: string, h: unknown) => (handlers[a] = h) },
    });
    vi.spyOn(examApi, 'audio').mockResolvedValue({ url: 'https://files.test/a.mp3', expiresAt: '2026-11-10T10:00:00Z' });
    const { container } = render(<ListeningPlayer attemptId="a1" mediaId="m1" />);
    await waitFor(() => expect(container.querySelector('audio')).not.toBeNull());
    const audio = container.querySelector('audio')!;
    expect(audio.hasAttribute('controls')).toBe(false);
    expect(screen.getAllByRole('slider')).toHaveLength(1);
    expect(screen.getByRole('slider')).toHaveAccessibleName(/âm lượng/i);
    expect(screen.queryByRole('button', { name: /pause|dừng|tạm|tua|seek|replay|phát lại|speed|tốc độ/i })).toBeNull();
    for (const a of ['pause', 'seekbackward', 'seekforward', 'seekto']) {
      expect(Object.prototype.hasOwnProperty.call(handlers, a), a).toBe(true);
      expect(handlers[a]).toBeNull();
    }
  });

  it('says so when the server no longer gives the recording out (410), without a player', async () => {
    vi.spyOn(examApi, 'audio').mockRejectedValue(new ExamHttpError(410));
    const { container } = render(<ListeningPlayer attemptId="a1" mediaId="m2" />);
    expect(await screen.findByText(/không phát lại/i)).toBeInTheDocument();
    expect(container.querySelector('audio')).toBeNull();
  });

  it('asks for the one-time link once per file, however often it renders', async () => {
    const spy = vi.spyOn(examApi, 'audio').mockResolvedValue({ url: 'https://files.test/b.mp3', expiresAt: '2026-11-10T10:00:00Z' });
    const first = render(<ListeningPlayer attemptId="a1" mediaId="m3" />);
    await waitFor(() => expect(first.container.querySelector('audio')).not.toBeNull());
    first.unmount();
    const second = render(<ListeningPlayer attemptId="a1" mediaId="m3" />);
    await waitFor(() => expect(second.container.querySelector('audio')).not.toBeNull());
    expect(spy).toHaveBeenCalledTimes(1);
  });
});
