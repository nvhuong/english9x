import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SpeakingRecorder, type RecorderDeps } from './SpeakingRecorder';

class FakeRecorder {
  static last: FakeRecorder;
  state: 'inactive' | 'recording' = 'inactive';
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  stopCalls = 0;
  constructor() {
    FakeRecorder.last = this;
  }
  start() {
    this.state = 'recording';
  }
  stop() {
    this.stopCalls++;
    this.state = 'inactive';
    this.ondataavailable?.({ data: new Blob(['opus'], { type: 'audio/webm' }) });
    this.onstop?.();
  }
}

const track = { stop: vi.fn() };
const deps: RecorderDeps = {
  getUserMedia: async () => ({ getTracks: () => [track] }) as unknown as MediaStream,
  createRecorder: () => new FakeRecorder() as unknown as MediaRecorder,
};

beforeEach(() => {
  vi.useFakeTimers();
  track.stop.mockClear();
  // no Web Audio in jsdom: the recorder falls back to "permission is the check"
});
afterEach(() => vi.useRealTimers());

const flush = async (ms = 0) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};

async function startRecording() {
  fireEvent.click(screen.getByRole('button', { name: /kiểm tra micro|micro/i }));
  await flush(1300);
  fireEvent.click(screen.getByRole('button', { name: /bắt đầu/i }));
}

describe('SpeakingRecorder', () => {
  it('stopsAtDuration: the recording ends by itself after exactly speakSeconds, the microphone is released and the file is uploaded', async () => {
    const upload = vi.fn().mockResolvedValue(undefined);
    const done = vi.fn();
    render(<SpeakingRecorder speakSeconds={3} upload={upload} deps={deps} onUploaded={done} />);
    await startRecording();
    expect(FakeRecorder.last.state).toBe('recording');
    await flush(2999);
    expect(FakeRecorder.last.stopCalls).toBe(0);
    await flush(1);
    expect(FakeRecorder.last.stopCalls).toBe(1);
    expect(track.stop).toHaveBeenCalled();
    expect(upload).toHaveBeenCalledTimes(1);
    expect((upload.mock.calls[0]![0] as Blob).size).toBeGreaterThan(0);
    expect(done).toHaveBeenCalled();
  });

  it('counts the preparation down before it records', async () => {
    render(<SpeakingRecorder prepareSeconds={2} speakSeconds={5} upload={vi.fn().mockResolvedValue(undefined)} deps={deps} />);
    await startRecording();
    await flush(1000);
    expect(document.querySelector('[data-phase="preparing"]')).not.toBeNull();
    await flush(1100);
    expect(document.querySelector('[data-phase="recording"]')).not.toBeNull();
  });

  it('retries a failed upload by itself, then offers a manual retry that sends the same recording', async () => {
    const upload = vi.fn().mockRejectedValue(new Error('offline'));
    render(<SpeakingRecorder speakSeconds={1} retryDelaysMs={[100]} upload={upload} deps={deps} />);
    await startRecording();
    await flush(1000);
    await flush(200);
    expect(upload).toHaveBeenCalledTimes(2);
    expect(document.querySelector('[data-phase="error"]')).not.toBeNull();
    upload.mockResolvedValue(undefined);
    fireEvent.click(screen.getByRole('button', { name: /thử lại/i }));
    await flush(0);
    expect(upload).toHaveBeenCalledTimes(3);
    expect(upload.mock.calls[2]![0]).toBe(upload.mock.calls[0]![0]);
    expect(document.querySelector('[data-phase="done"]')).not.toBeNull();
  });

  it('says so when the microphone is refused', async () => {
    render(<SpeakingRecorder speakSeconds={3} upload={vi.fn()} deps={{ ...deps, getUserMedia: () => Promise.reject(new Error('denied')) }} />);
    fireEvent.click(screen.getByRole('button', { name: /micro/i }));
    await flush(0);
    expect(document.querySelector('[data-phase="denied"]')).not.toBeNull();
  });
});
