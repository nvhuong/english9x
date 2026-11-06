import type { components } from './generated/client';

export type ProblemBody = components['schemas']['Problem'];
export type ProblemFieldError = components['schemas']['ProblemError'];

/** An RFC 7807 problem+json response from the API, as a thrown error. `errors[]` lists invalid fields / rows. */
export class ApiProblemError extends Error {
  readonly status: number;
  readonly type: string | undefined;
  readonly title: string;
  readonly detail: string | undefined;
  readonly instance: string | undefined;
  readonly errors: ProblemFieldError[];

  constructor(status: number, body: ProblemBody = {}) {
    const title = body.title ?? `HTTP ${status}`;
    super(body.detail ? `${title}: ${body.detail}` : title);
    this.name = 'ApiProblemError';
    this.status = body.status ?? status;
    this.type = body.type;
    this.title = title;
    this.detail = body.detail;
    this.instance = body.instance;
    this.errors = Array.isArray(body.errors) ? body.errors : [];
  }
}

/** Build the typed error from a failed response; tolerates non-JSON bodies (proxies, gateways). */
export async function problemFromResponse(response: Response): Promise<ApiProblemError> {
  let body: ProblemBody = {};
  if ((response.headers.get('content-type') ?? '').includes('json')) {
    try {
      body = (await response.clone().json()) as ProblemBody;
    } catch {
      /* fall through with an empty body */
    }
  }
  if (body.title === undefined && response.statusText) body = { ...body, title: response.statusText };
  return new ApiProblemError(response.status, body);
}
