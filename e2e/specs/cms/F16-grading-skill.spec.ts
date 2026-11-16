// F16 — Skill chấm (spec §16.2): "Soạn skill IELTS W Task 2 → chạy thử với bài mẫu → duyệt → dùng khi chấm".
//
// What the grading skills of P2.5 do, through the real stack: the author writes the skill of IELTS Writing Task 2 on the screen (rubric, criteria, prompt
// template, scored samples, the JSON Schema of the AI's answer); the server refuses a result schema that does not declare every criterion; the skill is
// published only by a review, a teacher of the exam reads it once published (a learner never does), and editing a published skill makes a new draft
// version. The "chạy thử" with a sample answer calls the AI grader through the fake Gemini and shows the answer, validated against the result schema, on
// the screen (nothing is saved); a model that breaks the schema is a 502 and a failing one a 5xx, never a made-up result. "Dùng khi chấm" (the AI grading
// job reads the published skill of the question type) is F34.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';

type Version = { versionNo: number; status: string; versionId: string; rubric: string };

test.describe('F16 grading skill', () => {
  test('F16 the author writes the IELTS Writing Task 2 skill; a result schema missing a criterion is refused by path; a dry run shows the AI answer and saves nothing; a review publishes it', async ({ page, request, auth, seed, org, uniqueEmail, gemini }) => {
    test.setTimeout(180_000);
    await gemini.reset();
    const s = await staffed(['IELTS'], 'f16-ui', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const teacher = await seed.verifiedLearner({ email: uniqueEmail('f16-teacher') });
    await seed.member({ orgId: s.orgId!, userId: teacher.userId, roles: [{ role: 'TEACHER', examCode: 'IELTS' }] });
    const t = await org.apiLogin(teacher);
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f16-learner') });
    await seed.member({ orgId: s.orgId!, userId: learner.userId });
    const l = await org.apiLogin(learner);
    await auth.loginAs(page, s.authorLogin);
    await org.switchContext(page, s.orgName);

    await page.goto('/cms/grading-skills/new');
    await expect(page.getByRole('heading', { level: 1, name: 'Skill chấm mới' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Dạng câu' }).click();
    await page.getByRole('option', { name: 'IELTS · IELTS_W_TASK2', exact: true }).click();
    await page.getByLabel(/^Thang chấm/).fill('## Task response\nBand 7: addresses all parts of the task.\n\n## Coherence\nBand 7: logical progression.');
    const first = page.getByRole('group', { name: 'Tiêu chí 1' });
    await first.getByLabel('Mã tiêu chí').fill('task_response');
    await first.getByLabel('Tên tiêu chí').fill('Task response');
    await page.getByRole('button', { name: 'Thêm tiêu chí' }).click();
    const second = page.getByRole('group', { name: 'Tiêu chí 2' });
    await second.getByLabel('Mã tiêu chí').fill('coherence');
    await second.getByLabel('Tên tiêu chí').fill('Coherence and cohesion');
    await page.getByRole('button', { name: 'Thêm bài mẫu' }).click();
    const sample = page.getByRole('group', { name: 'Bài mẫu 1' });
    await sample.getByLabel('Điểm (band)').fill('7');
    await sample.getByLabel('Bài làm mẫu (văn bản)').fill('Some people believe that technology isolates us. I partly agree, because...');
    await sample.getByLabel('Nhận xét của giám khảo').fill('Clear position, some repetition.');

    // a schema that declares only one of the two criteria: refused with the path of the missing one, next to the field
    await page.getByLabel(/^JSON Schema/).fill(JSON.stringify({ type: 'object', properties: { task_response: { type: 'number' }, feedback: { type: 'string' } } }));
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    const alert = page.getByRole('alert').filter({ hasText: 'Skill chưa đúng' });
    await expect(alert.getByText('resultSchema.properties.coherence')).toBeVisible();
    await expect(page).toHaveURL(/\/cms\/grading-skills\/new$/);

    // the generated schema declares every criterion
    await page.getByRole('button', { name: 'Tạo từ các tiêu chí' }).click();
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page).toHaveURL(/\/cms\/grading-skills\/[0-9a-f-]{36}$/);
    await expect(page.getByText('Bản nháp', { exact: true })).toBeVisible();
    const skillId = page.url().split('/').pop()!;

    const saved = await org.api(s.author.token, 'GET', `/api/cms/grading-skills/${skillId}`, { org: s.orgId });
    expect(saved.status, saved.text).toBe(200);
    expect(saved.body).toMatchObject({ examCode: 'IELTS', questionTypeCode: 'IELTS_W_TASK2', status: 'DRAFT', versionNo: 1 });
    expect(saved.body.criteria.map((c: { code: string }) => c.code)).toEqual(['task_response', 'coherence']);
    expect(saved.body.samples).toEqual([{ band: 7, text: 'Some people believe that technology isolates us. I partly agree, because...', commentary: 'Clear position, some repetition.' }]);
    expect(Object.keys(saved.body.resultSchema.properties).sort()).toEqual(['coherence', 'feedback', 'task_response']); // jsonb does not keep the order of keys
    for (const v of ['{{rubric}}', '{{samples}}', '{{task}}', '{{response}}']) expect(saved.body.promptTemplate).toContain(v);

    // the dry run calls the AI grader (the fake Gemini answers per the skill's result schema) and shows its answer; nothing is saved
    await page.getByLabel('Bài làm mẫu để chạy thử').fill('Some people believe that...');
    const running = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === `/api/cms/grading-skills/${skillId}/dry-run`);
    await page.getByRole('button', { name: 'Chạy thử' }).click();
    const ran = await running;
    expect(ran.status(), await ran.text()).toBe(200);
    const shown = page.getByRole('region', { name: 'Kết quả chạy thử' });
    await expect(shown.getByText(/Band \d/)).toBeVisible();
    await expect(shown.getByText('Fake feedback from fake-gemini.')).toBeVisible();
    const dry = await org.api(s.author.token, 'POST', `/api/cms/grading-skills/${skillId}/dry-run`, { org: s.orgId, body: { response: 'Some people believe that technology...' } });
    expect(dry.status, dry.text).toBe(200);
    expect(Object.keys(dry.body.result).sort(), 'the answer matches the skill\'s result schema: a score per criterion and the feedback').toEqual(['coherence', 'feedback', 'task_response']);
    expect(dry.body).toMatchObject({ versionNo: 1, versionId: saved.body.versionId });
    expect(typeof dry.body.band).toBe('number');
    const sent = await gemini.requests();
    expect(sent.length, 'one request to the model per dry run').toBe(2);
    // neither run kept anything: the skill is still the one draft, and a dry run needs a response
    expect((await org.api(s.author.token, 'GET', `/api/cms/grading-skills/${skillId}/versions`, { org: s.orgId })).body).toHaveLength(1);
    const empty = await org.api(s.author.token, 'POST', `/api/cms/grading-skills/${skillId}/dry-run`, { org: s.orgId, body: {} });
    expect(empty.status).toBe(422);
    expect((await gemini.requests()).length, 'a refused dry run sends nothing').toBe(2);

    // one skill per question type and owner
    const again = await org.api(s.author.token, 'POST', '/api/cms/grading-skills', { org: s.orgId, body: { ...saved.body, questionTypeCode: 'IELTS_W_TASK2' } });
    expect(again.status).toBe(409);
    expect(again.body.type).toBe('urn:e9x:problem:grading-skill-exists');

    // submit, review, publish: a teacher reads the published skill, a learner never does
    await page.getByRole('button', { name: 'Gửi duyệt' }).click();
    await expect(page.getByText(/Đã gửi duyệt/)).toBeVisible();
    expect((await org.api(t.token, 'GET', `/api/cms/grading-skills/${skillId}`, { org: s.orgId })).status, 'a teacher reads no draft').toBe(404);
    await seed.reviewVersion({ type: 'grading_skill', versionId: saved.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    const forTeacher = await org.api(t.token, 'GET', `/api/cms/grading-skills/${skillId}`, { org: s.orgId });
    expect(forTeacher.status, forTeacher.text).toBe(200);
    expect(forTeacher.body.status).toBe('PUBLISHED');
    expect((await org.api(l.token, 'GET', `/api/cms/grading-skills/${skillId}`, { org: s.orgId })).status, 'a learner reads no skill').toBe(404);
    expect((await org.api(t.token, 'PUT', `/api/cms/grading-skills/${skillId}`, { org: s.orgId, body: saved.body })).status, 'a teacher does not write').toBe(403);

    // editing the published skill makes version 2; version 1 is untouched
    await page.reload();
    await expect(page.getByText(/Bản này đã xuất bản và không sửa được/)).toBeVisible();
    await page.getByLabel(/^Thang chấm/).fill('A revised rubric');
    await page.getByRole('button', { name: 'Lưu thành phiên bản mới' }).click();
    await expect(page.getByText(/Đã tạo bản nháp mới, phiên bản 2/)).toBeVisible();
    const all = (await org.api(s.reviewer.token, 'GET', `/api/cms/grading-skills/${skillId}/versions`, { org: s.orgId })).body as Version[];
    expect(all.map((v) => [v.versionNo, v.status])).toEqual([[1, 'PUBLISHED'], [2, 'DRAFT']]);
    expect(all[0]!.rubric).toContain('Band 7: addresses all parts');
    expect(all[1]!.rubric).toBe('A revised rubric');

    await seed.reviewVersion({ type: 'grading_skill', versionId: all[1]!.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    const after = (await org.api(s.reviewer.token, 'GET', `/api/cms/grading-skills/${skillId}/versions`, { org: s.orgId })).body as Version[];
    expect(after.map((v) => [v.versionNo, v.status])).toEqual([[1, 'ARCHIVED'], [2, 'PUBLISHED']]);
    await page.goto('/cms/grading-skills');
    await expect(page.getByRole('link', { name: 'IELTS_W_TASK2' })).toBeVisible();
  });

  test('F16 the prompt template must use exactly the four variables, a criterion needs a scale that divides, and a Reading type has no skill', async ({ request, org, uniqueEmail }) => {
    const s = await staffed(['IELTS'], 'f16-api', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const base = {
      questionTypeCode: 'IELTS_W_TASK2',
      rubric: 'r',
      criteria: [{ code: 'a', name: 'A', min: 0, max: 9, step: 0.5 }],
      promptTemplate: '{{rubric}} {{samples}} {{task}} {{response}}',
      samples: [],
      resultSchema: { type: 'object', properties: { a: { type: 'number' } }, required: ['a'] },
    };
    const post = (body: unknown) => org.api(s.author.token, 'POST', '/api/cms/grading-skills', { org: s.orgId, body });
    const fields = (r: { body: { errors: Array<{ field: string }> } }) => r.body.errors.map((e) => e.field);

    const noResponse = await post({ ...base, promptTemplate: '{{rubric}} {{samples}} {{task}}' });
    expect(noResponse.status, noResponse.text).toBe(422);
    expect(fields(noResponse)).toEqual(['promptTemplate']);
    expect(fields(await post({ ...base, promptTemplate: '{{rubric}} {{samples}} {{task}} {{response}} {{student}}' }))).toEqual(['promptTemplate']);
    expect(fields(await post({ ...base, criteria: [{ code: 'a', name: 'A', min: 0, max: 9, step: 0.7 }] }))).toEqual(['criteria[0].step']);
    expect(fields(await post({ ...base, resultSchema: { type: 'object', properties: { a: { type: 'number' } }, required: ['a'], $ref: 'https://example.test/s.json' } }))).toEqual(['resultSchema']);
    expect(fields(await post({ ...base, questionTypeCode: 'IELTS_R_SHORT_ANSWER' }))).toEqual(['questionTypeCode']);
    expect(fields(await post({ ...base, resultSchema: { type: 'object', properties: { a: { type: 'string' } }, required: ['a'] } }))).toEqual(['resultSchema.properties.a']);
    expect(fields(await post({ ...base, resultSchema: { type: 'object', properties: { a: { type: 'number' } } } }))).toEqual(['resultSchema.required']);

    const ok = await post(base);
    expect(ok.status, ok.text).toBe(201);
    // a reviewer does not write
    expect((await org.api(s.reviewer.token, 'POST', '/api/cms/grading-skills', { org: s.orgId, body: base })).status).toBe(403);
  });
});
