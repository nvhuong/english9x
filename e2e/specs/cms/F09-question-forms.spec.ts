// F09 — Soạn câu hỏi theo form (spec §16.2): "Tạo câu cho từng dạng câu TOEIC và IELTS → lưu nháp → dữ liệu sai schema bị chặn với thông báo rõ".
//
// The REAL stack: the author works in the browser through Caddy; the server validates every question against the JSON Schemas of its type
// (P2.1) and the form is built from those same schemas. The number of types is the catalog's own (GET /api/cms/question-types, compared with the
// example files of the backend's schema tests), never a number written here.
import { test, expect } from '../../fixtures';
import { join } from 'node:path';
import { chooseQuestionType, createQuestion, exampleOf, exampleTypeCodes, MEDIA_FILES, staffed, withMedia } from '../../fixtures/content';

test.describe('F09 question forms', () => {
  test('F09 every question type of the catalog saves as a draft through the API, and the catalog and the examples agree on the types', async ({ request, org, uniqueEmail }) => {
    const s = await staffed(['IELTS', 'TOEIC'], 'f09-api', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const forms = await org.api(s.author.token, 'GET', '/api/cms/question-types', { org: s.orgId });
    expect(forms.status, forms.text).toBe(200);
    const codes = (forms.body as Array<{ code: string; contentSchema: object; answerKeySchema: object }>).map((f) => f.code).sort();
    expect(codes, 'the catalog and the example files list the same types').toEqual(exampleTypeCodes());
    expect(codes.length).toBeGreaterThanOrEqual(31);
    for (const f of forms.body as Array<{ contentSchema: { properties?: object }; answerKeySchema: { properties?: object } }>) {
      expect(Object.keys(f.contentSchema.properties ?? {}).length, 'a form has fields').toBeGreaterThan(0);
    }

    for (const code of codes) {
      const r = await createQuestion(s, code);
      expect(r.status, `${code}: ${r.text}`).toBe(201);
      expect(r.body, code).toMatchObject({ status: 'DRAFT', versionNo: 1, typeCode: code, createdBy: s.author.userId });
    }
    const list = await org.api(s.author.token, 'GET', '/api/cms/questions?size=100', { org: s.orgId });
    expect(list.body.total, 'one question per type').toBe(codes.length);
  });

  for (const exam of ['TOEIC', 'IELTS'] as const) {
    test(`F09 the author creates a ${exam} question of every type on the editor screen and each is saved as a draft`, async ({ page, request, auth, org, uniqueEmail }) => {
      test.setTimeout(240_000);
      const s = await staffed([exam], `f09-ui-${exam.toLowerCase()}`, { request, uniqueEmail, apiLogin: org.apiLogin });
      await auth.loginAs(page, s.authorLogin);
      const codes = exampleTypeCodes().filter((c) => c.startsWith(`${exam}_`));
      expect(codes.length).toBeGreaterThan(0);

      for (const code of codes) {
        const example = withMedia(exampleOf(code), s.mediaByExam[exam]!);
        await page.goto('/cms/questions/new');
        await expect(page.getByRole('heading', { level: 1, name: 'Câu hỏi mới' })).toBeVisible();
        await chooseQuestionType(page, exam, code);
        // the form of the type is there (built from its schema) ...
        await expect(page.getByRole('group', { name: 'Nội dung câu hỏi' }).first()).toBeVisible();
        await expect(page.getByText(code, { exact: true })).toBeVisible();
        // ... and so is the same data as JSON, which is how all the types are filled here
        await page.getByRole('button', { name: 'Soạn bằng JSON' }).click();
        await page.getByLabel(/Câu hỏi dạng JSON/).fill(JSON.stringify({ ...example, explanation: `Giải thích ${code}`, points: 1 }));
        await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
        await expect(page, code).toHaveURL(/\/cms\/questions\/[0-9a-f-]{36}$/);
        await expect(page.getByText('Bản nháp', { exact: true })).toBeVisible();
        await expect(page.getByText('Phiên bản 1')).toBeVisible();
        // what the screen saved is what the server holds
        const id = page.url().split('/').pop()!;
        const saved = await org.api(s.author.token, 'GET', `/api/cms/questions/${id}`, { org: s.orgId });
        expect(saved.body, code).toMatchObject({ typeCode: code, status: 'DRAFT', content: example.content, answerKey: example.answerKey, explanation: `Giải thích ${code}` });
      }
    });
  }

  test('F09 filling the form field by field: wrong data is refused with the JSON path and the reason, right data is saved and shown again', async ({ page, request, auth, org, uniqueEmail }) => {
    const s = await staffed(['TOEIC'], 'f09-form', { request, uniqueEmail, apiLogin: org.apiLogin });
    await auth.loginAs(page, s.authorLogin);
    await page.goto('/cms/questions/new');
    await chooseQuestionType(page, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE');

    // only the stem: the four options and the answer are missing
    const stem = `The manager ------- the report yesterday. (${s.author.userId.slice(0, 8)})`;
    await page.locator('#root_content_stem').fill(stem);
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    const alert = page.getByRole('alert').filter({ hasText: 'chưa đúng với dạng câu' });
    await expect(alert).toBeVisible();
    await expect(alert.getByText('content.options[0].key')).toBeVisible();
    await expect(alert.locator('code', { hasText: /^answerKey\.slots/ }).first()).toBeVisible();
    await expect(page, 'nothing was created').toHaveURL(/\/cms\/questions\/new$/);
    // the reason is also next to the field it is about
    await expect(page.locator('#root_content_options_0_key')).toHaveAccessibleDescription(/\S/);
    // an option key that does not fit its pattern is refused by path as well
    for (const [i, key] of ['A', 'B', 'C', 'TOOLONG'].entries()) {
      await page.locator(`#root_content_options_${i}_key`).fill(key);
      await page.locator(`#root_content_options_${i}_text`).fill(`choice ${i + 1}`);
    }
    await page.locator('#root_answerKey_slots_0_0').fill('C');
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    const again = page.getByRole('alert').filter({ hasText: 'chưa đúng với dạng câu' });
    await expect(again.getByText('content.options[3].key')).toBeVisible();
    await expect(again.getByText('content.options[0].key')).toHaveCount(0);

    await page.locator('#root_content_options_3_key').fill('D');
    await page.locator('#root_explanation').fill('Cần một động từ ở quá khứ.');
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page).toHaveURL(/\/cms\/questions\/[0-9a-f-]{36}$/);
    await expect(page.getByText('Bản nháp', { exact: true })).toBeVisible();
    // opened again from the server: the same values in the same fields
    await page.reload();
    await expect(page.locator('#root_content_stem')).toHaveValue(stem);
    await expect(page.locator('#root_content_options_3_key')).toHaveValue('D');
    await expect(page.locator('#root_answerKey_slots_0_0')).toHaveValue('C');
    await expect(page.locator('#root_explanation')).toHaveValue('Cần một động từ ở quá khứ.');
    // the list shows it, with its status
    await page.goto('/cms/questions');
    await expect(page.getByRole('link', { name: stem })).toBeVisible();
    await expect(page.getByRole('row').filter({ hasText: stem }).getByRole('cell', { name: 'Bản nháp' })).toBeVisible();
  });

  test('F09 a file field takes the id of an uploaded file, or uploads one right there', async ({ page, request, auth, org, uniqueEmail }) => {
    const s = await staffed(['TOEIC'], 'f09-media', { request, uniqueEmail, apiLogin: org.apiLogin });
    await auth.loginAs(page, s.authorLogin);
    await page.goto('/cms/questions/new');
    await chooseQuestionType(page, 'TOEIC', 'TOEIC_P1_PHOTO');

    // the audio is uploaded from the field, the image is pasted by id
    const audio = page.locator('.form-group.rjsf-field-string').filter({ has: page.getByLabel('Âm thanh (mã tệp)') });
    await audio.getByText('Tải tệp mới lên').click();
    await audio.getByLabel('Tệp media').setInputFiles(join(MEDIA_FILES, 'tone.mp3'));
    await audio.getByRole('button', { name: 'Tải lên', exact: true }).click();
    await expect(page.getByLabel('Âm thanh (mã tệp)')).toHaveValue(/^[0-9a-f-]{36}$/);
    await page.getByLabel('Hình ảnh (mã tệp)').fill(s.imageId);
    for (const [i, key] of ['A', 'B', 'C', 'D'].entries()) await page.locator(`#root_content_options_${i}_key`).fill(key);
    await page.locator('#root_answerKey_slots_0_0').fill('A');
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page).toHaveURL(/\/cms\/questions\/[0-9a-f-]{36}$/);

    // a file id that is not a file of this exam is refused, by path
    await page.locator('#root_content_imageMediaId').fill('11111111-1111-4111-8111-111111111111');
    await page.getByRole('button', { name: 'Lưu thay đổi' }).click();
    const alert = page.getByRole('alert').filter({ hasText: 'chưa đúng với dạng câu' });
    await expect(alert.getByText('content.imageMediaId')).toBeVisible();
    await expect(alert.getByText(/not a file of this exam/)).toBeVisible();
  });

  test('F09 only the author of an exam writes its questions: no form for the others, the server refuses them too', async ({ page, request, seed, auth, org, uniqueEmail }) => {
    const s = await staffed(['IELTS'], 'f09-access', { request, uniqueEmail, apiLogin: org.apiLogin });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f09-learner') });
    const toeicAuthor = await seed.verifiedLearner({ email: uniqueEmail('f09-toeic') });
    await seed.staffGrant({ userId: toeicAuthor.userId, role: 'AUTHOR', examCode: 'TOEIC' });
    const made = await createQuestion(s, 'IELTS_R_SHORT_ANSWER');
    expect(made.status, made.text).toBe(201);

    // an author of TOEIC: no IELTS type in the editor, IELTS questions refused and invisible
    const t = (await org.apiLogin(toeicAuthor)).token;
    const types = await org.api(t, 'GET', '/api/cms/question-types');
    expect((types.body as Array<{ examCode: string }>).every((f) => f.examCode === 'TOEIC')).toBe(true);
    const body = { typeCode: 'IELTS_R_SHORT_ANSWER', ...exampleOf('IELTS_R_SHORT_ANSWER') };
    expect((await org.api(t, 'POST', '/api/cms/questions', { body })).status).toBe(403);
    expect((await org.api(t, 'GET', `/api/cms/questions/${made.body.questionId}`)).status).toBe(404);
    expect((await org.api(t, 'GET', '/api/cms/questions?exam=IELTS')).status).toBe(403);

    // a learner: nothing
    const l = (await org.apiLogin(learner)).token;
    expect((await org.api(l, 'GET', '/api/cms/question-types')).status).toBe(403);
    expect((await org.api(l, 'POST', '/api/cms/questions', { body })).status).toBe(403);
    expect((await org.api(l, 'GET', `/api/cms/questions/${made.body.questionId}`)).status).toBe(404);
    await auth.loginAs(page, learner);
    await page.goto('/cms/questions/new');
    await expect(page.getByRole('heading', { level: 1, name: 'Câu hỏi mới' })).toHaveCount(0);

    // the reviewer reads the key (to review it) but writes nothing
    const rv = s.reviewer.token;
    expect((await org.api(rv, 'GET', `/api/cms/questions/${made.body.questionId}`, { org: s.orgId })).body.answerKey).toEqual(exampleOf('IELTS_R_SHORT_ANSWER').answerKey);
    expect((await org.api(rv, 'POST', '/api/cms/questions', { org: s.orgId, body })).status).toBe(403);
  });
});
