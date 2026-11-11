import { useCallback, useEffect, useRef, useState } from 'react';
import { RecorderShell, type RecorderPhase } from '../../design';

export interface RecorderDeps {
  getUserMedia: () => Promise<MediaStream>;
  createRecorder: (stream: MediaStream) => MediaRecorder;
}

export const defaultRecorderDeps: RecorderDeps = {
  getUserMedia: () => navigator.mediaDevices.getUserMedia({ audio: true }),
  createRecorder: (stream) => {
    const type = 'audio/webm;codecs=opus';
    return typeof MediaRecorder.isTypeSupported === 'function' && MediaRecorder.isTypeSupported(type) ? new MediaRecorder(stream, { mimeType: type }) : new MediaRecorder(stream);
  },
};

export interface SpeakingRecorderProps {
  /** Sends the recording; rejects when it cannot (offline, server). */
  upload: (blob: Blob) => Promise<void>;
  prepareSeconds?: number;
  /** The recording stops by itself after exactly this long. */
  speakSeconds: number;
  /** Waits (ms) before each new attempt to upload; its length is the number of automatic retries. */
  retryDelaysMs?: number[];
  deps?: RecorderDeps;
  onUploaded?: () => void;
}

/** Speaking answer: mic check, preparation countdown, recording of exactly `speakSeconds` at most, upload with automatic retries and a manual one. */
export function SpeakingRecorder({ upload, prepareSeconds = 0, speakSeconds, retryDelaysMs = [1000, 3000, 6000], deps = defaultRecorderDeps, onUploaded }: SpeakingRecorderProps) {
  const [phase, setPhase] = useState<RecorderPhase>('mic-check');
  const [level, setLevel] = useState(0);
  const [left, setLeft] = useState(0);
  const stream = useRef<MediaStream | null>(null);
  const recorder = useRef<MediaRecorder | null>(null);
  const chunks = useRef<Blob[]>([]);
  const blob = useRef<Blob | null>(null);
  const timers = useRef<{ stop?: ReturnType<typeof setTimeout>; tick?: ReturnType<typeof setInterval>; level?: number; ctx?: AudioContext }>({});
  const live = useRef(true);

  const clearTimers = useCallback(() => {
    clearTimeout(timers.current.stop);
    clearInterval(timers.current.tick);
    if (timers.current.level) cancelAnimationFrame(timers.current.level);
    timers.current.level = undefined;
  }, []);
  const release = useCallback(() => {
    stream.current?.getTracks().forEach((t) => t.stop());
    stream.current = null;
    void timers.current.ctx?.close().catch(() => undefined);
    timers.current.ctx = undefined;
  }, []);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
      clearTimers();
      if (recorder.current && recorder.current.state !== 'inactive') {
        recorder.current.ondataavailable = null;
        recorder.current.onstop = null;
        recorder.current.stop();
      }
      release();
    };
  }, [clearTimers, release]);

  const meter = (s: MediaStream) => {
    try {
      const Ctx = window.AudioContext;
      const ctx = new Ctx();
      const an = ctx.createAnalyser();
      an.fftSize = 256;
      ctx.createMediaStreamSource(s).connect(an);
      timers.current.ctx = ctx;
      const data = new Uint8Array(an.fftSize);
      const loop = () => {
        an.getByteTimeDomainData(data);
        let peak = 0;
        for (const v of data) peak = Math.max(peak, Math.abs(v - 128));
        setLevel(Math.min(1, peak / 64));
        timers.current.level = requestAnimationFrame(loop);
      };
      loop();
    } catch {
      setLevel(1); // no meter in this browser: the permission itself is the check
    }
  };

  const doUpload = useCallback(
    async (b: Blob) => {
      setPhase('uploading');
      for (let i = 0; ; i++) {
        try {
          await upload(b);
          if (!live.current) return;
          setPhase('done');
          onUploaded?.();
          return;
        } catch {
          const wait = retryDelaysMs[i];
          if (wait === undefined || !live.current) {
            if (live.current) setPhase('error');
            return;
          }
          await new Promise((r) => setTimeout(r, wait));
        }
      }
    },
    [upload, retryDelaysMs, onUploaded],
  );

  const micTest = async () => {
    try {
      const s = await deps.getUserMedia();
      if (!live.current) {
        s.getTracks().forEach((t) => t.stop());
        return;
      }
      stream.current = s;
      meter(s);
      setTimeout(() => {
        if (!live.current) return;
        if (timers.current.level) cancelAnimationFrame(timers.current.level);
        timers.current.level = undefined;
        setPhase('ready');
      }, 1200);
    } catch {
      setPhase('denied');
    }
  };

  const stop = () => {
    clearTimers();
    if (recorder.current && recorder.current.state !== 'inactive') recorder.current.stop();
  };

  const record = () => {
    const s = stream.current;
    if (!s) return;
    const r = deps.createRecorder(s);
    recorder.current = r;
    chunks.current = [];
    r.ondataavailable = (e: BlobEvent) => {
      if (e.data && e.data.size > 0) chunks.current.push(e.data);
    };
    r.onstop = () => {
      clearTimers();
      release();
      const b = new Blob(chunks.current, { type: 'audio/webm' });
      blob.current = b;
      if (live.current) void doUpload(b);
    };
    r.start();
    setPhase('recording');
    setLeft(speakSeconds);
    const endAt = performance.now() + speakSeconds * 1000;
    timers.current.stop = setTimeout(stop, speakSeconds * 1000);
    timers.current.tick = setInterval(() => setLeft(Math.max(0, Math.ceil((endAt - performance.now()) / 1000))), 250);
  };

  const begin = () => {
    if (prepareSeconds <= 0) return record();
    setPhase('preparing');
    setLeft(prepareSeconds);
    const endAt = performance.now() + prepareSeconds * 1000;
    timers.current.tick = setInterval(() => {
      const remaining = Math.ceil((endAt - performance.now()) / 1000);
      setLeft(Math.max(0, remaining));
      if (remaining <= 0) {
        clearInterval(timers.current.tick);
        record();
      }
    }, 250);
  };

  const retry = () => {
    if (phase === 'error' && blob.current) void doUpload(blob.current);
    else {
      setPhase('mic-check');
      setLevel(0);
    }
  };

  return (
    <RecorderShell
      phase={phase}
      micLevel={level}
      secondsLeft={left}
      totalSeconds={phase === 'preparing' ? prepareSeconds : speakSeconds}
      onMicTest={() => void micTest()}
      onBegin={begin}
      onStop={stop}
      onRetry={retry}
    />
  );
}
