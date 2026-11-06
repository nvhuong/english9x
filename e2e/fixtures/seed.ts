import { env, json } from '../helpers/env';
import { notImplemented } from './notImplemented';

export interface SeededLearner { email: string; password: string; userId: string }
export interface SeededTest { testId: string; title: string }
export interface SeededCenter {
  orgId: string;
  admin: { email: string; password: string };
  teachers: Array<{ email: string; password: string }>;
  learners: Array<{ email: string; password: string }>;
}

/** POST /api/test/seed/<name>: the backend route exists only under the Spring profile `e2e` (stack from infra/docker-compose.e2e.yml). */
async function post<T>(name: string, body?: unknown): Promise<T> {
  return json<T>(`${env.baseURL}/api/test/seed/${name}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/**
 * Test-data factory. Every function returns data under unique emails/ids so parallel tests do not collide.
 * Functions whose modules are not built yet throw NotImplementedError naming the task that fills them in.
 */
export const seed = {
  /** Real today: proves the e2e profile is active and reports which DB login the seed code writes as. */
  ping: () => post<{ status: 'ok'; seedLogin: string }>('ping'),

  /** A verified learner holding an active plan with the given courses. Needs identity (P1.4), billing, course modules. */
  learnerWithPlan: (_opts: { courses: string[] }): Promise<SeededLearner> =>
    notImplemented('seed.learnerWithPlan', 'P5.1 (plans/entitlements), after P3.1 (courses) and P1.4 (identity)'),

  /** A published TOEIC L&R test (200 questions). Needs content + exam modules. */
  publishedToeicTest: (): Promise<SeededTest> => notImplemented('seed.publishedToeicTest', 'P2.6 (test assembly) and P3.3 (exam backend)'),

  /** A published IELTS test of the given variant. Needs content + exam modules. */
  publishedIeltsTest: (_opts: { variant: 'AC' | 'GT' }): Promise<SeededTest> =>
    notImplemented('seed.publishedIeltsTest', 'P2.6 (test assembly) and P3.3 (exam backend)'),

  /** A centre with teachers and learners. Needs org (P1.5) and classroom modules. */
  center: (_opts: { teachers: number; learners: number }): Promise<SeededCenter> =>
    notImplemented('seed.center', 'P1.5 (org), then P6.1 (members)'),
};
