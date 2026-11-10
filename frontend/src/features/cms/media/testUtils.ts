import { vi } from 'vitest';

/** Replaces XMLHttpRequest: records the request and lets the test play the server (progress events, the answer). */
export class FakeXhr {
  static instances: FakeXhr[] = [];
  static last(): FakeXhr {
    return FakeXhr.instances[FakeXhr.instances.length - 1]!;
  }
  method = '';
  url = '';
  headers: Record<string, string> = {};
  body: FormData | null = null;
  status = 0;
  responseText = '';
  upload: { onprogress: ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  ontimeout: (() => void) | null = null;
  onabort: (() => void) | null = null;
  aborted = false;

  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }
  setRequestHeader(name: string, value: string) {
    this.headers[name] = value;
  }
  send(body: FormData) {
    this.body = body;
    FakeXhr.instances.push(this);
  }
  abort() {
    this.aborted = true;
    this.onabort?.();
  }
  progress(loaded: number, total: number) {
    this.upload.onprogress?.({ lengthComputable: true, loaded, total });
  }
  respond(status: number, body: unknown) {
    this.status = status;
    this.responseText = JSON.stringify(body);
    this.onload?.();
  }
  fail() {
    this.onerror?.();
  }
}

export function installFakeXhr() {
  FakeXhr.instances = [];
  vi.stubGlobal('XMLHttpRequest', FakeXhr);
}

export const LIMITS = { maxUploadBytes: 200 * 1024 * 1024, extensions: ['jpeg', 'jpg', 'm4a', 'm4v', 'mp3', 'mp4', 'oga', 'ogg', 'opus', 'png', 'weba', 'webm', 'webp'] };

export const MP3_VIEW = {
  id: '0b8e7f3a-4c1d-4e2b-9a55-3f1d2c4b6a70',
  kind: 'AUDIO',
  mimeType: 'audio/mpeg',
  sizeBytes: 3361,
  durationMs: 84000,
  originalFilename: 'part1.mp3',
  examCode: 'IELTS',
};
