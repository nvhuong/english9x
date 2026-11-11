// F33 — Chấm tự động (spec §16.2): "Đáp án biến thể hợp lệ được chấp nhận; vượt giới hạn số từ bị tính sai".
//
// Through the real stack: an IELTS test (Academic) with a gap-typed Listening question whose key has two slots (one with two accepted spellings) and a
// limit of two words, a Reading question, a Writing and a Speaking question, all PUBLISHED through the real review. The administrator's conversion tables
// for the test are PUBLISHED first (P2.1 seeds none, spec 10.2 gives no values). Two learners sit it over the API of the exam room:
//   - learner A answers with another case and with the accepted variant of the second slot: both are right;
//   - learner B writes three words into the slot that allows two: that slot is wrong, so half of the question is lost, and 0.5 right rounds DOWN to 0.
// After the late-sync grace (the answers may still arrive until then) the scoring job reads the answers back, scores the objective questions, converts the
// Listening and Reading scores with the table of the test and keeps the version of the table it used. Writing and Speaking are not scored here (the AI or a
// teacher does that, P4.2 / P4.3), so there is no overall band and nothing is announced yet.
import { test, expect } from '../../fixtures';
import { staffed, publishedQuestion, type Staffed } from '../../fixtures/content';
import { api } from '../../fixtures/org';
import { poll } from '../../helpers/env';
import { seed } from '../../fixtures/seed';

async function publishedGroup(s: Staffed, typeCode: string, title: string, withAudio: boolean): Promise<string> {
  const q = await publishedQuestion(s, typeCode);
  const content: Record<string, string> = { title };
  if (withAudio) content.audioMediaId = s.audioId;
  const made = await api(s.author.token, 'POST', '/api/cms/question-groups', { org: s.orgId, body: { examCode: 'IELTS', content, questionVersionIds: [q.versionId] } });
  expect(made.status, made.text).toBe(201);
  await seed.reviewVersion({ type: 'question_group', versionId: made.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
  return made.body.versionId as string;
}

async function publishedIeltsTest(s: Staffed, title: string): Promise<string> {
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

/** Sits the test as a learner with a plan: Listening answered as given, Reading answered right ("not given"), Writing and Speaking left. Returns the attempt. */
async function sit(token: string, testVersionId: string, listening: string[]): Promise<string> {
  const started = await api(token, 'POST', '/api/exam/attempts', { body: { testVersionId } });
  expect(started.status, started.text).toBe(201);
  const attemptId = started.body.attemptId as string;
  const questionOf = (view: any) => view.currentSection.groups[0].questions[0].questionId as string;
  const answer = async (view: any, value: unknown) => {
    // written one second after the section began on the server's clock (not before it began, not in the future)
    await new Promise((r) => setTimeout(r, 1500));
    const answeredAt = new Date(Date.parse(view.serverNow) + 1000).toISOString();
    const put = await api(token, 'PUT', `/api/exam/attempts/${attemptId}/responses/${questionOf(view)}`, { body: { answer: value, answeredAt } });
    expect(put.status, put.text).toBe(204);
  };
  await answer(started.body, listening);
  const reading = await api(token, 'POST', `/api/exam/attempts/${attemptId}/sections/LISTENING/finish`);
  expect(reading.status, reading.text).toBe(200);
  await answer(reading.body, 'not given');
  const submitted = await api(token, 'POST', `/api/exam/attempts/${attemptId}/submit`);
  expect(submitted.status, submitted.text).toBe(200);
  return attemptId;
}

test.describe('F33 automatic scoring', () => {
  test('F33 a valid variant is accepted, a slot over the word limit is wrong, the score is converted with the published table of the test and that version is kept', async ({ request, uniqueEmail, org, gemini }) => {
    // the AI grading now also reads Writing and Speaking of this attempt: hold the shared fake Gemini (success scenario) so that it cannot disturb F37's counts
    // two minutes of late-sync grace (e9x.exam.late-sync-grace) are waited out for real: the job is queued for the end of it
    test.setTimeout(480_000);
    const s = await staffed(['IELTS'], 'f33', { request, uniqueEmail, apiLogin: org.apiLogin });
    const testVersionId = await publishedIeltsTest(s, `F33 ${Math.random().toString(36).slice(2, 8)}`);
    // 0 right = 0, 1 right = 5.5 (Listening); 0 right = 0, 1 right = 6.0 (Reading, Academic)
    const listeningTable = await seed.grading.scoreTable({ testVersionId, skill: 'LISTENING', scaled: [0, 5.5] });
    const readingTable = await seed.grading.scoreTable({ testVersionId, skill: 'READING_AC', scaled: [0, 6.0] });

    const a = await seed.learnerWithPlan({ courses: 1 });
    const b = await seed.learnerWithPlan({ courses: 1 });
    const tokenA = (await org.apiLogin({ email: a.email, password: a.password })).token;
    const tokenB = (await org.apiLogin({ email: b.email, password: b.password })).token;
    const attemptA = await sit(tokenA, testVersionId, ['SMITH', '0123 456']);       // other case; the second spelling of the second slot
    const attemptB = await sit(tokenB, testVersionId, ['Mr John Smith', '0123456']); // three words where two are allowed

    // nothing is scored while an answer may still arrive
    expect((await seed.grading.scores(attemptA)).graded).toBe(false);

    const scoresA = await poll('the scores of learner A', async () => {
      const r = await seed.grading.scores(attemptA);
      return r.graded ? r : null;
    }, { timeoutMs: 300_000, intervalMs: 2_000 });
    const scoresB = await poll('the scores of learner B', async () => {
      const r = await seed.grading.scores(attemptB);
      return r.graded ? r : null;
    }, { timeoutMs: 60_000, intervalMs: 2_000 });

    for (const r of [scoresA, scoresB]) {
      // Since P4.2 Writing and Speaking are no longer left unscored: the AI grader (or, failing that, a teacher) writes their rows, with source AI. Automatic scoring owns
      // Listening and Reading ONLY, and never writes a Writing or Speaking score itself.
      const auto = r.scores.filter((x) => x.source === 'AUTO');
      expect(auto.map((x) => x.skill), 'automatic scoring: Listening and Reading only').toEqual(['LISTENING', 'READING']);
      expect(auto.every((x) => x.kind === 'OFFICIAL')).toBe(true);
      expect(r.scores.filter((x) => x.source !== 'AUTO').every((x) => ['WRITING', 'SPEAKING'].includes(x.skill) && x.source === 'AI')).toBe(true);
      expect(r.interrupted).toBe(false);
      const reading = r.scores.find((x) => x.skill === 'READING')!;
      expect(reading).toMatchObject({ raw: 1, rawMax: 1, scaled: 6, scoreTableVersionId: readingTable.versionId });
    }
    const listeningA = scoresA.scores.find((x) => x.skill === 'LISTENING')!;
    expect(listeningA, 'a different case and the accepted variant: both slots are right').toMatchObject({ raw: 1, rawMax: 1, scaled: 5.5, scoreTableVersionId: listeningTable.versionId });
    const listeningB = scoresB.scores.find((x) => x.skill === 'LISTENING')!;
    expect(listeningB, 'three words in a slot that allows two: that slot is wrong, 0.5 right rounds down to 0').toMatchObject({ raw: 0.5, rawMax: 1, scaled: 0, scoreTableVersionId: listeningTable.versionId });
  });
});
