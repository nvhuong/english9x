import { env, json, request } from '../helpers/env';
import { notImplemented } from './notImplemented';

export interface SeededLearner { email: string; password: string; userId: string; verified: boolean }
export interface SeededTest { testId: string; title: string }
export interface SeededCenter {
  orgId: string;
  admin: { email: string; password: string };
  teachers: Array<{ email: string; password: string }>;
  learners: Array<{ email: string; password: string }>;
}
export interface UserInfo { count: number; userId?: string; hasPassword?: boolean; emailVerified?: boolean; hasConsent?: boolean; displayName?: string | null; identities?: number }

const seedHeaders = (): Record<string, string> => ({ 'content-type': 'application/json', 'x-e2e-seed-secret': env.seedSecret });

/** POST /api/test/seed/<name>: the backend route exists only under the Spring profile `e2e` and needs the shared secret header. */
async function post<T>(name: string, body?: unknown): Promise<T> {
  return json<T>(`${env.baseURL}/api/test/seed/${name}`, {
    method: 'POST',
    headers: seedHeaders(),
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

  /**
   * A learner with a known password who can sign in (P1.4). Verified by default; `verified: false` leaves the mailbox
   * unconfirmed. Not the real sign-up path (use auth.registerLearner for that): it exists so other features can start
   * from "a signed-in-able person" without going through the form.
   */
  verifiedLearner: (opts: { email?: string; password?: string; displayName?: string; verified?: boolean; locale?: 'vi' | 'en' } = {}) =>
    post<{ userId: string; email: string; password: string; verified: boolean }>('learner', opts).then((r): SeededLearner => r),

  /** What the database holds for an address: number of accounts, whether it has a password, verified, consent, name, linked identities. */
  userInfo: (email: string) => post<UserInfo>('user-info', { email }),

  /**
   * Calls the guard billing uses before checkout (identity.api.UserDirectory#requireVerified) as that user.
   * Resolves to 200 when the email is verified and 403 when it is not. TODO(P5.1): the real checkout takes over.
   */
  requireVerified: async (userId: string): Promise<number> =>
    (await request(`${env.baseURL}/api/test/seed/require-verified`, { method: 'POST', headers: seedHeaders(), body: JSON.stringify({ userId }) })).status,

  /** A verified learner holding an active plan with the given courses. Needs billing, course modules. */
  learnerWithPlan: (_opts: { courses: string[] }): Promise<SeededLearner> =>
    notImplemented('seed.learnerWithPlan', 'P5.1 (plans/entitlements), after P3.1 (courses); sign-in itself exists since P1.4 (seed.verifiedLearner)'),

  /** A published TOEIC L&R test (200 questions). Needs content + exam modules. */
  publishedToeicTest: (): Promise<SeededTest> => notImplemented('seed.publishedToeicTest', 'P2.6 (test assembly) and P3.3 (exam backend)'),

  /** A published IELTS test of the given variant. Needs content + exam modules. */
  publishedIeltsTest: (_opts: { variant: 'AC' | 'GT' }): Promise<SeededTest> =>
    notImplemented('seed.publishedIeltsTest', 'P2.6 (test assembly) and P3.3 (exam backend)'),

  /**
   * An organisation, created as the table owner (no SYS_ADMIN session needed to set the scene). To test the real creation
   * flow drive the admin screen instead (F05).
   */
  organization: (opts: { name?: string; type?: 'CENTER' | 'ENTERPRISE' } = {}) =>
    post<{ orgId: string; name: string; type: string }>('organization', opts),

  /** Makes a person a member of an organisation with the given roles (the membership row first, like every real path). */
  member: (opts: { orgId: string; userId: string; roles?: Array<{ role: string; examCode?: string | null }> }) =>
    post<{ status: string }>('membership', { ...opts, roles: (opts.roles ?? []).map((r) => ({ role: r.role, examCode: r.examCode ?? null })) }),

  /** An English9x-level role (no organisation): AUTHOR / REVIEWER / TEACHER of an exam, or SYS_ADMIN. */
  staffGrant: (opts: { userId: string; role: string; examCode?: string | null }) =>
    post<{ status: string }>('staff-grant', { ...opts, examCode: opts.examCode ?? null }),

  /** What the server's own Authz (used by the CMS and every module) answers for "role R, optionally for exam X" for that person in that context. */
  authz: (opts: { userId: string; orgId?: string | null; role: string; examCode?: string | null }) =>
    post<{ allowed: boolean; canReviewOwnVersion: boolean }>('authz', { orgId: null, examCode: null, ...opts }),

  /** A verified person who holds SYS_ADMIN, who can sign in through the real login screen. The operator's script does this in production. */
  sysAdmin: async (opts: { email?: string; password?: string; displayName?: string } = {}): Promise<SeededLearner> => {
    const learner = await seed.verifiedLearner({ displayName: 'Quản trị hệ thống', ...opts });
    await seed.staffGrant({ userId: learner.userId, role: 'SYS_ADMIN' });
    return learner;
  },

  /**
   * Publishes the event the review workflow publishes ('requested' | 'changes' | 'approved') through the real event registry, so the
   * real notification listeners, jobs, mail and Telegram take over. A stand-in for the CMS review screens (P2.9); `orgId` omitted =
   * an English9x review. 'requested' goes to the REVIEWERs of `examCode` in that context; the other two to `authorId`.
   */
  reviewEvent: (opts: { kind: 'requested' | 'changes' | 'approved'; authorId: string; examCode?: string; orgId?: string | null }) =>
    post<{ reviewId: string; kind: string }>('review-event', { examCode: 'IELTS', orgId: null, ...opts }),

  /** A centre with teachers and learners (classes, members). Needs the classroom module. */
  center: (_opts: { teachers: number; learners: number }): Promise<SeededCenter> =>
    notImplemented('seed.center', 'P6.1 (members and classes); organisations themselves exist since P1.5 (seed.organization, seed.member)'),
};
