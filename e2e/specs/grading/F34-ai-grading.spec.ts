// F34 — Chấm AI (spec §16.2): "Chế độ AI → điểm theo tiêu chí, nhận xét, transcript hiển thị".
//
// Through the real stack and the fake Gemini: an IELTS test (Academic) with a Writing and a Speaking question, the published grading skills of both types (written
// through the CMS and published by the review), and a learner who writes an essay and records an answer. After the late-sync grace (the answers may still arrive until
// then) the `ai_grade` job reads the published skill as the grader login, asks Gemini once per response (the recording goes as audio), validates the answer against
// the skill's JSON Schema, stores one evaluation per response (skill version, model, tokens, cost) and writes the Writing and Speaking scores (AI source, OFFICIAL in
// mode AI: the default). The results page that shows the criteria, the comments and the transcript is P4.4 (see e2e/partial-features.json): until then the spec reads
// the evaluations through the seed API.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { ensureGradingSkill, publishedIeltsTest, sitWritingAndSpeaking } from '../../fixtures/ai-grading';
import { poll } from '../../helpers/env';

test.describe('F34 AI grading', () => {
  test('F34 the essay and the recording are graded by Gemini with the published skill; criteria, feedback, tokens and cost are stored; Writing and Speaking get an AI score', async ({ request, uniqueEmail, org, seed, gemini }) => {
    // the late-sync grace (two minutes) is waited out for real; the fake-Gemini lock (fixture `gemini`) is held for the test and released with it, never longer than this timeout
    test.setTimeout(480_000);
    const s = await staffed(['IELTS'], 'f34', { request, uniqueEmail, apiLogin: org.apiLogin });
    await ensureGradingSkill(s, 'IELTS_W_TASK2');
    await ensureGradingSkill(s, 'IELTS_S_PART1');
    const testVersionId = await publishedIeltsTest(s, `F34 ${Math.random().toString(36).slice(2, 8)}`);
    const learner = await seed.learnerWithPlan({ courses: 1 });
    const token = (await org.apiLogin({ email: learner.email, password: learner.password })).token;
    const essay = `F34 essay ${Math.random().toString(36).slice(2, 8)}: some people think cities are better than villages.`;
    await gemini.reset();
    const attemptId = await sitWritingAndSpeaking(request, token, testVersionId, essay);

    expect((await seed.grading.ai(attemptId)).state, 'nothing is graded while an answer may still arrive').toBeNull();
    const ai = await poll('the AI grading of the attempt', async () => {
      const r = await seed.grading.ai(attemptId);
      return r.state ? r : null;
    }, { timeoutMs: 360_000, intervalMs: 2_000 });
    expect(ai.state).toBe('DONE');

    // one evaluation per response, from the model the app is configured for, with the tokens the fake reports and the cost from the configured prices
    expect(ai.evaluations.map((e) => e.section)).toEqual(['SPEAKING', 'WRITING']);
    for (const e of ai.evaluations) {
      expect(e).toMatchObject({ model: 'gemini-2.5-flash', inputTokens: 120, outputTokens: 80, band: 6.5 });
      expect(e.costVnd).toBeCloseTo((120 * 7.5 + 80 * 62.5) / 1000, 3);
      const result = JSON.parse(e.result);
      expect(Object.keys(result).sort(), 'the answer is the skill\'s schema: a score per criterion and the feedback').toEqual(['coherence', 'feedback', 'task_response']);
    }

    // what Gemini was sent: the essay in the prompt of the Writing call, the recording as inline audio in the Speaking call, the schema of the skill
    const sent = await gemini.requests();
    expect(sent).toHaveLength(2);
    const bodies = sent.map((r) => r.body as any);
    const writingCall = bodies.find((b) => JSON.stringify(b).includes(essay));
    expect(writingCall, 'the essay went to Gemini').toBeTruthy();
    expect(writingCall.generationConfig.responseJsonSchema.required).toEqual(['task_response', 'coherence', 'feedback']);
    const speakingCall = bodies.find((b) => b.contents[0].parts.some((p: any) => p.inline_data?.mime_type === 'audio/webm'));
    expect(speakingCall, 'the recording went to Gemini as audio').toBeTruthy();

    // the scores: Writing and Speaking by the AI; mode AI makes them official
    const scores = await seed.grading.scores(attemptId);
    for (const skill of ['WRITING', 'SPEAKING']) {
      expect(scores.scores.find((x) => x.skill === skill), skill).toMatchObject({ kind: 'OFFICIAL', source: 'AI', raw: 6.5, scaled: 6.5, scoreTableVersionId: null });
    }
  });
});
