import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { refreshSession } from '../auth/session';
import type { ExamAttemptView, ExamResult, Json } from './types';

/** The request never reached the server (offline, DNS, reset): the answer is kept and sent later. */
export class ExamNetworkError extends Error {
  constructor() {
    super('network');
    this.name = 'ExamNetworkError';
  }
}
export class ExamHttpError extends Error {
  constructor(readonly status: number, readonly type?: string, readonly title?: string) {
    super(`${status} ${title ?? ''}`.trim());
    this.name = 'ExamHttpError';
  }
}

/** A token, waiting briefly while another tab's sign-in is being followed (the exam room keeps its session steady: ledger 110). */
async function token(): Promise<string | null> {
  for (let i = 0; i < 20; i++) {
    const s = useAuthStore.getState();
    if (s.accessToken) return s.accessToken;
    if (s.status !== 'loading') break;
    await new Promise((r) => setTimeout(r, 250));
  }
  return useAuthStore.getState().accessToken ?? (await refreshSession());
}

async function send(method: string, path: string, body?: unknown, form?: FormData): Promise<Response> {
  const run = async (tok: string | null) => {
    const headers: Record<string, string> = {};
    if (tok) headers.Authorization = `Bearer ${tok}`;
    const org = useContextStore.getState().orgId;
    if (org) headers['X-Org-Context'] = org;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    try {
      return await fetch(path, { method, headers, body: form ?? (body === undefined ? undefined : JSON.stringify(body)) });
    } catch {
      throw new ExamNetworkError();
    }
  };
  const first = await token();
  let res = await run(first);
  if (res.status === 401) {
    const fresh = useAuthStore.getState().accessToken;
    const next = fresh && fresh !== first ? fresh : await refreshSession();
    if (next) res = await run(next);
  }
  return res;
}

async function problem(res: Response): Promise<ExamHttpError> {
  let type: string | undefined;
  let title: string | undefined;
  try {
    const b = (await res.clone().json()) as { type?: string; title?: string };
    type = b.type;
    title = b.title;
  } catch {
    /* not JSON */
  }
  return new ExamHttpError(res.status, type, title);
}

async function json<T>(res: Response, ok: number[]): Promise<T> {
  if (!ok.includes(res.status)) throw await problem(res);
  return (await res.json()) as T;
}

export const examApi = {
  /** 201 = a new attempt; 409 = the one already running (same body). */
  async start(testVersionId: string): Promise<ExamAttemptView> {
    return json(await send('POST', '/api/exam/attempts', { testVersionId }), [200, 201, 409]);
  },
  async get(id: string): Promise<ExamAttemptView> {
    return json(await send('GET', `/api/exam/attempts/${id}`), [200]);
  },
  /** 409 = the attempt was submitted meanwhile (time, or too many resumes): the body is the submitted attempt. */
  async resume(id: string): Promise<ExamAttemptView> {
    return json(await send('POST', `/api/exam/attempts/${id}/resume`), [200, 409]);
  },
  async save(id: string, questionId: string, answer: Json, answeredAt: string): Promise<void> {
    const res = await send('PUT', `/api/exam/attempts/${id}/responses/${questionId}`, { answer, answeredAt });
    if (res.status !== 204 && res.status !== 200) throw await problem(res);
  },
  async finishSection(id: string, sectionId: string): Promise<ExamAttemptView> {
    return json(await send('POST', `/api/exam/attempts/${id}/sections/${encodeURIComponent(sectionId)}/finish`), [200]);
  },
  async submit(id: string): Promise<ExamResult> {
    return json(await send('POST', `/api/exam/attempts/${id}/submit`), [200]);
  },
  /** One-time link; 410 once it was given out. */
  async audio(id: string, mediaId: string): Promise<{ url: string; expiresAt: string }> {
    return json(await send('GET', `/api/exam/attempts/${id}/audio/${mediaId}`), [200]);
  },
  async recording(id: string, questionId: string, blob: Blob): Promise<void> {
    const form = new FormData();
    form.append('file', blob, 'recording.webm');
    const res = await send('POST', `/api/exam/attempts/${id}/recordings/${questionId}`, undefined, form);
    if (res.status !== 204 && res.status !== 200) throw await problem(res);
  },
  async mediaUrl(mediaId: string): Promise<string | null> {
    const res = await send('GET', `/api/media/${mediaId}/url`);
    return res.ok ? ((await res.json()) as { url: string }).url : null;
  },
};

export const DEFAULT_AUTOSAVE_MS = 5000;
let configured: Promise<number> | undefined;
/** e9x.exam.autosave-interval from GET /api/config/public (the key `autosaveIntervalMs`/`examAutosaveInterval`); 5 s when the server does not say. */
export function autosaveIntervalMs(): Promise<number> {
  configured ??= (async () => {
    try {
      const res = await fetch('/api/config/public');
      if (!res.ok) return DEFAULT_AUTOSAVE_MS;
      const b = (await res.json()) as Record<string, unknown>;
      const v = b['e9x.exam.autosave-interval'] ?? b.examAutosaveInterval ?? b.autosaveInterval ?? (b.exam as Record<string, unknown> | undefined)?.autosaveInterval;
      if (typeof v === 'number') return v >= 100 && v < 1000 ? v * 1000 : v; // seconds or ms
      if (typeof v === 'string') {
        const m = /^(\d+(?:\.\d+)?)(ms|s|m)?$/.exec(v.trim());
        if (m) return Math.round(Number(m[1]) * (m[2] === 'ms' ? 1 : m[2] === 'm' ? 60000 : 1000));
      }
    } catch {
      /* fall through */
    }
    return DEFAULT_AUTOSAVE_MS;
  })();
  return configured;
}
