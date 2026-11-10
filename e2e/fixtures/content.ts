import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { APIRequestContext } from '@playwright/test';
import { env } from '../helpers/env';
import { api } from './org';
import { seed } from './seed';

/**
 * Content through the REAL CMS API (P2.3). The valid example of every question type is the one the backend's own schema tests use
 * (backend/e9x-app/src/test/resources/catalog/examples): the E2E suite and the unit tests agree on what a valid question is.
 */
const EXAMPLES = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', 'backend', 'e9x-app', 'src', 'test', 'resources', 'catalog', 'examples');
export const MEDIA_FILES = join(fileURLToPath(new URL('.', import.meta.url)), 'media');

export interface QuestionExample { content: Record<string, unknown>; answerKey: Record<string, unknown> }
export interface Staffed { orgId: string | null; author: { userId: string; token: string }; reviewer: { userId: string; token: string }; audioId: string; imageId: string }

/** Every question type that has an example, from the files (the count is the catalog's, not a number written here). */
export function exampleTypeCodes(): string[] {
  return readdirSync(EXAMPLES).filter((f) => f.endsWith('.valid.json')).map((f) => f.replace('.valid.json', '')).sort();
}

export function exampleOf(code: string): QuestionExample {
  return JSON.parse(readFileSync(join(EXAMPLES, `${code}.valid.json`), 'utf8')) as QuestionExample;
}

/** The example with its file ids (any field ending in MediaId) replaced by real files of the right kind. */
export function withMedia<T>(node: T, media: { audioId: string; imageId: string }): T {
  if (Array.isArray(node)) return node.map((n) => withMedia(n, media)) as T;
  if (node && typeof node === 'object') {
    return Object.fromEntries(Object.entries(node).map(([k, v]) => [k, k.endsWith('MediaId') && typeof v === 'string' ? (k.startsWith('audio') ? media.audioId : media.imageId) : withMedia(v, media)])) as T;
  }
  return node;
}

export async function uploadFile(request: APIRequestContext, token: string, org: string | null, exam: string, name: string, type: string): Promise<string> {
  const res = await request.post(`${env.baseURL}/api/cms/media?examCode=${exam}`, {
    headers: { authorization: `Bearer ${token}`, ...(org ? { 'x-org-context': org } : {}) },
    multipart: { file: { name, mimeType: type, buffer: readFileSync(join(MEDIA_FILES, name)) } },
  });
  if (res.status() !== 200) throw new Error(`media upload answered ${res.status()} ${await res.text()}`);
  return ((await res.json()) as { id: string }).id;
}

/** An organisation with an author and a reviewer of one exam, signed in, with an audio file and an image of that exam to point questions at. */
export interface StaffedDeps {
  request: APIRequestContext;
  uniqueEmail: (label: string) => string;
  apiLogin: (u: { email: string; password: string }) => Promise<{ token: string; userId: string }>;
}

/**
 * An author and a reviewer of `exams`, signed in over the API, with an audio file and an image of the first exam to point questions at. `inOrganisation`
 * puts them in a new CENTER (context = that organisation, `orgName` is what the switcher shows); otherwise they are English9x's own staff (the personal
 * context, nothing to switch). Everyone is unique to the call, so parallel tests do not see each other's questions.
 */
export async function staffed(exams: string[], label: string, deps: StaffedDeps, opts: { inOrganisation?: boolean } = {}): Promise<Staffed & { orgName: string | null; authorLogin: { email: string; password: string } }> {
  const inOrg = opts.inOrganisation ?? false;
  const org = inOrg ? await seed.organization({ type: 'CENTER' }) : null;
  const author = await seed.verifiedLearner({ email: deps.uniqueEmail(`${label}-author`) });
  const reviewer = await seed.verifiedLearner({ email: deps.uniqueEmail(`${label}-reviewer`) });
  for (const [person, role] of [[author, 'AUTHOR'], [reviewer, 'REVIEWER']] as const) {
    if (org) await seed.member({ orgId: org.orgId, userId: person.userId, roles: exams.map((examCode) => ({ role, examCode })) });
    else for (const examCode of exams) await seed.staffGrant({ userId: person.userId, role, examCode });
  }
  const a = await deps.apiLogin(author);
  const r = await deps.apiLogin(reviewer);
  const orgId = org?.orgId ?? null;
  const audioId = await uploadFile(deps.request, a.token, orgId, exams[0]!, 'tone.mp3', 'audio/mpeg');
  const imageId = await uploadFile(deps.request, a.token, orgId, exams[0]!, 'pixel.png', 'image/png');
  return { orgId, orgName: org?.name ?? null, authorLogin: author, author: { userId: a.userId, token: a.token }, reviewer: { userId: r.userId, token: r.token }, audioId, imageId };
}

export async function createQuestion(s: Staffed, typeCode: string, example: QuestionExample = withMedia(exampleOf(typeCode), s)) {
  return api(s.author.token, 'POST', '/api/cms/questions', { org: s.orgId, body: { typeCode, content: example.content, answerKey: example.answerKey, explanation: 'because', points: 1 } });
}

/** Creates a question and takes it through the real review to PUBLISHED. Returns the question and its first version. */
export async function publishedQuestion(s: Staffed, typeCode: string): Promise<{ questionId: string; versionId: string }> {
  const made = await createQuestion(s, typeCode);
  if (made.status !== 201) throw new Error(`creating ${typeCode} answered ${made.status} ${made.text}`);
  const { questionId, versionId } = made.body as { questionId: string; versionId: string };
  await seed.reviewVersion({ type: 'question', versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
  return { questionId, versionId };
}
