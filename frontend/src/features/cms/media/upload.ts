import { ApiProblemError, type ProblemBody } from '../../../api/problem';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { refreshSession } from '../../auth/session';
import type { MediaView } from './types';

/** The request never reached the server, or the answer never came back. */
export class UploadNetworkError extends Error {
  constructor() {
    super('network error while uploading');
    this.name = 'UploadNetworkError';
  }
}

export interface UploadOptions {
  examCode: string;
  file: File;
  /** 0..1, as the browser reports the bytes sent. */
  onProgress?: (fraction: number) => void;
  signal?: AbortSignal;
}

interface Answer {
  status: number;
  body: unknown;
}

function send(opts: UploadOptions, token: string | null): Promise<Answer> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/cms/media?examCode=${encodeURIComponent(opts.examCode)}`);
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    const org = useContextStore.getState().orgId;
    if (org) xhr.setRequestHeader('X-Org-Context', org);
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && e.total > 0) opts.onProgress?.(e.loaded / e.total);
    };
    xhr.onload = () => {
      let body: unknown = {};
      try {
        body = xhr.responseText ? JSON.parse(xhr.responseText) : {};
      } catch {
        /* a proxy's page, not JSON: the status is still the answer */
      }
      resolve({ status: xhr.status, body });
    };
    xhr.onerror = () => reject(new UploadNetworkError());
    xhr.ontimeout = () => reject(new UploadNetworkError());
    xhr.onabort = () => reject(new DOMException('upload cancelled', 'AbortError'));
    opts.signal?.addEventListener('abort', () => xhr.abort(), { once: true });
    if (opts.signal?.aborted) {
      reject(new DOMException('upload cancelled', 'AbortError'));
      return;
    }
    const form = new FormData();
    form.append('file', opts.file, opts.file.name);
    xhr.send(form);
  });
}

/**
 * POST /api/cms/media as multipart, with progress (fetch cannot report upload progress). Like the API client: the bearer token and the context
 * header go along, and one 401 triggers one session refresh and one repeat. Non-2xx answers are thrown as {@link ApiProblemError}.
 */
export async function uploadMedia(opts: UploadOptions): Promise<MediaView> {
  let token = useAuthStore.getState().accessToken;
  let answer = await send(opts, token);
  if (answer.status === 401 && token) {
    const fresh = await refreshSession();
    if (fresh) {
      token = fresh;
      opts.onProgress?.(0);
      answer = await send(opts, token);
    }
  }
  if (answer.status < 200 || answer.status >= 300) {
    throw new ApiProblemError(answer.status, (typeof answer.body === 'object' && answer.body ? answer.body : {}) as ProblemBody);
  }
  return answer.body as MediaView;
}
