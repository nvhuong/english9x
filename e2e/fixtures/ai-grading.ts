// Shared by the AI grading specs (F34, F37, P4.2): a published IELTS test with Writing and Speaking, the published grading skills of those two question types,
// and a learner who sits the test over the API of the exam room (essay written, a recording uploaded) and submits it.
import { expect, type APIRequestContext } from '@playwright/test';
import { publishedQuestion, type Staffed } from './content';
import { api } from './org';
import { seed } from './seed';
import { env } from '../helpers/env';

async function publishedGroup(s: Staffed, typeCode: string, title: string, withAudio: boolean): Promise<string> {
  const q = await publishedQuestion(s, typeCode);
  const content: Record<string, string> = { title };
  if (withAudio) content.audioMediaId = s.audioId;
  const made = await api(s.author.token, 'POST', '/api/cms/question-groups', { org: s.orgId, body: { examCode: 'IELTS', content, questionVersionIds: [q.versionId] } });
  expect(made.status, made.text).toBe(201);
  await seed.reviewVersion({ type: 'question_group', versionId: made.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
  return made.body.versionId as string;
}

export async function publishedIeltsTest(s: Staffed, title: string): Promise<string> {
  const parts = [
    { sectionCode: 'LISTENING', durationSec: 600, groups: [await publishedGroup(s, 'IELTS_L_SENTENCE_COMPLETION', `${title} listening`, true)] },
    { sectionCode: 'READING', durationSec: 600, groups: [await publishedGroup(s, 'IELTS_R_IDENTIFYING_INFORMATION', `${title} reading`, false)] },
    { sectionCode: 'WRITING', durationSec: 600, groups: [await publishedGroup(s, 'IELTS_W_TASK2', `${title} writing`, false)] },
    { sectionCode: 'SPEAKING', durationSec: 600, groups: [await publishedGroup(s, 'IELTS_S_PART1', `${title} speaking`, false)] },
  ];
  const created = await api(s.author.token, 'POST', '/api/cms/tests', { org: s.orgId, body: { examCode: 'IELTS', variant: 'AC', title } });
  expect(created.status, created.text).toBe(201);
  const saved = await api(s.author.token, 'PUT', `/api/cms/tests/${created.body.testId}/structure`, { org: s.orgId, body: { parts } });
  expect(saved.status, saved.text).toBe(200);
  await seed.reviewVersion({ type: 'test', versionId: saved.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
  return saved.body.versionId as string;
}

const CRITERIA = [
  { code: 'task_response', name: 'Task response', min: 0, max: 9, step: 0.5 },
  { code: 'coherence', name: 'Coherence and cohesion', min: 0, max: 9, step: 0.5 },
];

/**
 * English9x's PUBLISHED grading skill of a question type, made through the real CMS and review unless it exists (one skill per owner and type: the second spec to
 * need it finds a 409 and uses the first one's).
 */
export async function ensureGradingSkill(s: Staffed, questionTypeCode: string): Promise<void> {
  const body = {
    questionTypeCode,
    rubric: '## Task response\nBand 7: addresses all parts.\n\n## Coherence\nBand 7: logical progression.',
    criteria: CRITERIA,
    promptTemplate: 'Rubric:\n{{rubric}}\n\nScored examples:\n{{samples}}\n\nQuestion:\n{{task}}\n\nLearner answer:\n{{response}}',
    samples: [{ band: 7, text: 'Some people believe that technology isolates us. I partly agree, because...', commentary: 'Clear position.' }],
    resultSchema: {
      type: 'object',
      properties: { task_response: { type: 'number' }, coherence: { type: 'number' }, feedback: { type: 'string' } },
      required: ['task_response', 'coherence', 'feedback'],
    },
  };
  const made = await api(s.author.token, 'POST', '/api/cms/grading-skills', { org: s.orgId, body });
  if (made.status === 409) return;
  expect(made.status, made.text).toBe(201);
  await seed.reviewVersion({ type: 'grading_skill', versionId: made.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
}

/** The smallest body the server accepts as a WebM recording (the EBML magic; the fake Gemini does not decode it). */
const WEBM = Buffer.concat([Buffer.from([0x1a, 0x45, 0xdf, 0xa3]), Buffer.alloc(64, 1)]);

/**
 * Sits the test as a learner with a plan: Listening and Reading left, an essay written, a recording uploaded, submitted. Returns the attempt.
 * Sections are finished one by one so that each is the current one when its answer is written.
 */
export async function sitWritingAndSpeaking(request: APIRequestContext, token: string, testVersionId: string, essay: string): Promise<string> {
  const started = await api(token, 'POST', '/api/exam/attempts', { body: { testVersionId } });
  expect(started.status, started.text).toBe(201);
  const attemptId = started.body.attemptId as string;
  const questionOf = (view: any) => view.currentSection.groups[0].questions[0].questionId as string;
  const finish = async (section: string) => {
    const r = await api(token, 'POST', `/api/exam/attempts/${attemptId}/sections/${section}/finish`);
    expect(r.status, r.text).toBe(200);
    return r.body;
  };
  await finish('LISTENING');
  const writing = await finish('READING');
  await new Promise((r) => setTimeout(r, 1500));
  const answeredAt = new Date(Date.parse(writing.serverNow) + 1000).toISOString();
  const put = await api(token, 'PUT', `/api/exam/attempts/${attemptId}/responses/${questionOf(writing)}`, { body: { answer: essay, answeredAt } });
  expect(put.status, put.text).toBe(204);
  const speaking = await finish('WRITING');
  const upload = await request.post(`${env.baseURL}/api/exam/attempts/${attemptId}/recordings/${questionOf(speaking)}`, {
    headers: { authorization: `Bearer ${token}` },
    multipart: { file: { name: 'answer.webm', mimeType: 'audio/webm', buffer: WEBM } },
  });
  expect(upload.status(), await upload.text()).toBe(204);
  const submitted = await api(token, 'POST', `/api/exam/attempts/${attemptId}/submit`);
  expect(submitted.status, submitted.text).toBe(200);
  return attemptId;
}
