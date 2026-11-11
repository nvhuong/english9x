import { expect, request as pwRequest } from '@playwright/test';
import { mailpit } from '../helpers/mailpit';
import { createQuestion, exampleOf, examOfType, staffed, withMedia, type Staffed } from './content';
import { api, apiLogin } from './org';
import { seed } from './seed';

/** One question of a seeded test, as the exam room will show it. `answer` is the right one (what the answer key says). */
export interface SeededQuestion {
  questionId: string;
  typeCode: string;
  sectionCode: string;
  skill: string;
  /** The correct answer in the form the room sends it. Absent for Writing / Speaking. */
  answer?: string | string[];
}
export interface SeededPart { sectionCode: string; skill: string; questions: SeededQuestion[] }
export interface SeededExam {
  testId: string;
  title: string;
  testVersionId: string;
  parts: SeededPart[];
}

interface QSpec {
  typeCode: string;
  /** Replaces fields of the valid example's content. */
  content?: Record<string, unknown>;
  /** Which option the key points to (default: rotates with the position). */
  pick?: number;
}
interface GSpec { content: Record<string, unknown>; questions: QSpec[]; audio?: boolean }
interface PSpec { sectionCode: string; skill: string; durationSec: number; groups: GSpec[] }

const KEYED = new Set(['MCQ_SINGLE', 'GAP_SELECT']);

async function inBatches<T, R>(items: T[], size: number, fn: (x: T) => Promise<R>): Promise<R[]> {
  const out: R[] = [];
  for (let i = 0; i < items.length; i += size) out.push(...(await Promise.all(items.slice(i, i + size).map(fn))));
  return out;
}

/** Builds, reviews and publishes a test of `examCode` through the REAL CMS and review (the same path as F33), with the answer key it returns. */
async function publishTest(s: Staffed, examCode: string, variant: string | undefined, title: string, parts: PSpec[]): Promise<SeededExam> {
  let counter = 0;
  const seededParts: SeededPart[] = parts.map((p) => ({ sectionCode: p.sectionCode, skill: p.skill, questions: [] }));
  const groupIds: string[][] = parts.map(() => []);
  for (const [pi, p] of parts.entries()) {
    for (const g of p.groups) {
      const made = await inBatches(g.questions, 8, async (q) => {
        const n = ++counter;
        const media = s.mediaByExam[examOfType(q.typeCode)] ?? s;
        const ex = withMedia(exampleOf(q.typeCode), media);
        const content = { ...ex.content, ...(q.content ?? {}) } as Record<string, unknown>;
        const answerKey = JSON.parse(JSON.stringify(ex.answerKey)) as { slots?: string[][] };
        let answer: string | string[] | undefined = undefined;
        if (KEYED.has(kindOf(q.typeCode)) && Array.isArray(content.options)) {
          const keys = (content.options as Array<{ key: string }>).map((o) => o.key);
          const key = keys[(q.pick ?? n * 7 + 3) % keys.length]!;
          answerKey.slots = [[key]];
          answer = key;
          if (typeof content.stem === 'string') content.stem = `Question ${n}`;
        } else if (answerKey.slots && answerKey.slots.length > 0) {
          answer = answerKey.slots.length === 1 && kindOf(q.typeCode) !== 'GAP_TYPED' && kindOf(q.typeCode) !== 'MCQ_MULTI' ? answerKey.slots[0]![0]! : kindOf(q.typeCode) === 'MCQ_MULTI' ? answerKey.slots[0]! : answerKey.slots.map((v) => v[0]!);
        }
        const res = await createQuestion(s, q.typeCode, { content, answerKey: answerKey as Record<string, unknown> });
        if (res.status !== 201) throw new Error(`creating ${q.typeCode} answered ${res.status} ${res.text}`);
        const { questionId, versionId } = res.body as { questionId: string; versionId: string };
        await seed.reviewVersion({ type: 'question', versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
        return { versionId, sq: { questionId, typeCode: q.typeCode, sectionCode: p.sectionCode, skill: p.skill, answer } satisfies SeededQuestion };
      });
      const content = { ...g.content };
      if (g.audio) content.audioMediaId = s.mediaByExam[examCode]!.audioId;
      const grp = await api(s.author.token, 'POST', '/api/cms/question-groups', { org: s.orgId, body: { examCode, content, questionVersionIds: made.map((m) => m.versionId) } });
      expect(grp.status, grp.text).toBe(201);
      await seed.reviewVersion({ type: 'question_group', versionId: grp.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
      groupIds[pi]!.push(grp.body.versionId as string);
      seededParts[pi]!.questions.push(...made.map((m) => m.sq));
    }
  }
  const created = await api(s.author.token, 'POST', '/api/cms/tests', { org: s.orgId, body: { examCode, ...(variant ? { variant } : {}), title } });
  expect(created.status, created.text).toBe(201);
  const saved = await api(s.author.token, 'PUT', `/api/cms/tests/${created.body.testId}/structure`, {
    org: s.orgId,
    body: { parts: parts.map((p, i) => ({ sectionCode: p.sectionCode, durationSec: p.durationSec, groups: groupIds[i] })) },
  });
  expect(saved.status, saved.text).toBe(200);
  await seed.reviewVersion({ type: 'test', versionId: saved.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
  return { testId: created.body.testId as string, title, testVersionId: saved.body.versionId as string, parts: seededParts };
}

function kindOf(typeCode: string): string {
  if (typeCode === 'TOEIC_P6_TEXT_COMPLETION') return 'GAP_SELECT';
  if (typeCode.startsWith('TOEIC')) return 'MCQ_SINGLE';
  if (/_MULTIPLE_CHOICE$/.test(typeCode)) return 'MCQ_MULTI';
  if (/SENTENCE_COMPLETION|SUMMARY_NOTE|FORM_NOTE/.test(typeCode)) return 'GAP_TYPED';
  if (/MATCHING$|MATCHING_/.test(typeCode)) return 'MATCHING';
  return 'OTHER';
}

async function staff(label: string) {
  const ctx = await pwRequest.newContext({ ignoreHTTPSErrors: true });
  return staffed(['IELTS', 'TOEIC'], label, { request: ctx, uniqueEmail: (l) => mailpit.uniqueEmail(l), apiLogin });
}

const TOEIC_PARTS: Array<[string, string, string, number]> = [
  ['P1', 'LISTENING', 'TOEIC_P1_PHOTO', 6], ['P2', 'LISTENING', 'TOEIC_P2_QUESTION_RESPONSE', 25], ['P3', 'LISTENING', 'TOEIC_P3_CONVERSATION', 39],
  ['P4', 'LISTENING', 'TOEIC_P4_TALK', 30], ['P5', 'READING', 'TOEIC_P5_INCOMPLETE_SENTENCE', 30], ['P6', 'READING', 'TOEIC_P6_TEXT_COMPLETION', 16],
  ['P7', 'READING', 'TOEIC_P7_SINGLE_PASSAGE', 54],
];

/** A PUBLISHED TOEIC Listening & Reading test of 200 questions (6/25/39/30/30/16/54 in parts P1-P7), each with a known right answer. */
export async function publishedToeicTest(): Promise<SeededExam> {
  const s = await staff('toeic');
  const title = `TOEIC ${Math.random().toString(36).slice(2, 8)}`;
  return publishTest(s, 'TOEIC', undefined, title, TOEIC_PARTS.map(([sectionCode, skill, typeCode, n]) => ({
    sectionCode,
    skill,
    durationSec: 1800,
    groups: [{ content: { title: `${sectionCode} group`, ...(sectionCode === 'P7' ? { passage: 'A short email.\n\nIt asks for a reply.' } : {}) }, questions: Array.from({ length: n }, () => ({ typeCode })) }],
  })));
}

/**
 * A PUBLISHED IELTS test of `variant` with every kind the room must draw: Listening (audio, multiple choice, typed gaps, matching), Reading (passage,
 * True/False/Not Given, Yes/No/Not Given, short answer), Writing (task 1 and task 2) and Speaking (part 2, with a short preparation).
 */
export async function publishedIeltsTest(opts: { variant: 'AC' | 'GT' }): Promise<SeededExam> {
  const s = await staff(`ielts-${opts.variant.toLowerCase()}`);
  const title = `IELTS ${opts.variant} ${Math.random().toString(36).slice(2, 8)}`;
  const task1 = opts.variant === 'AC' ? 'IELTS_W_AC_TASK1' : 'IELTS_W_GT_TASK1';
  return publishTest(s, 'IELTS', opts.variant, title, [
    { sectionCode: 'LISTENING', skill: 'LISTENING', durationSec: 600, groups: [{ audio: true, content: { title: 'Listening group' }, questions: [{ typeCode: 'IELTS_L_MULTIPLE_CHOICE' }, { typeCode: 'IELTS_L_SENTENCE_COMPLETION' }, { typeCode: 'IELTS_L_MATCHING' }, { typeCode: 'IELTS_L_SHORT_ANSWER' }] }] },
    { sectionCode: 'READING', skill: 'READING', durationSec: 600, groups: [{ content: { title: 'Reading group', passage: 'The bridge was finished in 1932.\n\nThe writer disagrees with the plan.' }, questions: [{ typeCode: 'IELTS_R_IDENTIFYING_INFORMATION' }, { typeCode: 'IELTS_R_IDENTIFYING_WRITERS_VIEWS' }, { typeCode: 'IELTS_R_SHORT_ANSWER' }] }] },
    { sectionCode: 'WRITING', skill: 'WRITING', durationSec: 600, groups: [{ content: { title: 'Writing tasks' }, questions: [{ typeCode: task1 }, { typeCode: 'IELTS_W_TASK2' }] }] },
    { sectionCode: 'SPEAKING', skill: 'SPEAKING', durationSec: 600, groups: [{ content: { title: 'Speaking part 2' }, questions: [{ typeCode: 'IELTS_S_PART2', content: { prepareSeconds: 1, speakSeconds: 4 } }] }] },
  ]);
}
