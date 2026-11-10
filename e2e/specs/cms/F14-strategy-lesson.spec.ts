// F14 — Bài phương pháp (spec §16.2): "Soạn bài có đủ loại khối (văn bản, bảng, ảnh, audio, video upload, YouTube, mẹo, câu nhúng, ví dụ giải) → học viên thấy đúng".
//
// The REAL stack: the author builds a lesson with every block type on the editor screen; the server validates each block against its schema (video
// hosts against the allowlist, files and the embedded question against the exam), keeps the blocks in order, and the lesson is published only through a
// review that waits for the embedded question to be published. The part about the LEARNER seeing it ("học viên thấy đúng") needs the lesson page of
// P3.2; it is listed in e2e/partial-features.json. Until then the learner side is covered by the Vitest tests of StrategyLessonView and by the
// integration test of what the learner-facing reads return (StrategyLessonIT).
import { test, expect } from '../../fixtures';
import { publishedQuestion, createQuestion, staffed, uploadFile } from '../../fixtures/content';
import type { Page } from '@playwright/test';

/** Adds a block the way a keyboard user chooses from a long list (the options are taller than the page in a small window). */
async function addBlock(page: Page, name: string): Promise<void> {
  await page.getByRole('combobox', { name: 'Thêm khối' }).click();
  await page.getByRole('option', { name, exact: true }).focus();
  await page.keyboard.press('Enter');
}

test.describe('F14 strategy lesson', () => {
  test('F14 the author writes a lesson with every kind of block on the screen, the server keeps them in order, and a bad video host is refused by path', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(180_000);
    const s = await staffed(['IELTS'], 'f14-ui', { request, uniqueEmail, apiLogin: org.apiLogin });
    const media = s.mediaByExam.IELTS!;
    const videoId = await uploadFile(request, s.author.token, s.orgId, 'IELTS', 'clip.mp4', 'video/mp4');
    const q = await publishedQuestion(s, 'IELTS_R_SHORT_ANSWER');
    await auth.loginAs(page, s.authorLogin);

    await page.goto('/cms/strategy-lessons/new');
    await expect(page.getByRole('heading', { level: 1, name: 'Bài phương pháp mới' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Kỳ thi' }).click();
    await page.getByRole('option', { name: 'IELTS', exact: true }).click();
    const title = `Short answer: the method (${s.author.userId.slice(0, 8)})`;
    await page.getByLabel(/^Tên bài/).fill(title);
    await page.getByRole('checkbox', { name: 'IELTS_R_SHORT_ANSWER' }).check();

    const kinds = ['Tiêu đề', 'Đoạn văn', 'Bảng', 'Hình ảnh', 'Âm thanh', 'Video tải lên', 'Video YouTube/Vimeo', 'Hộp mẹo/bẫy', 'Hộp mẹo/bẫy', 'Câu hỏi nhúng', 'Ví dụ giải'];
    for (const k of kinds) await addBlock(page, k);
    const block = (n: number, kind: string) => page.getByRole('region', { name: `Khối ${n}: ${kind}` });

    await block(1, 'Tiêu đề').getByLabel('Nội dung').fill('Read the question first');
    await block(2, 'Đoạn văn').getByLabel('Nội dung').fill('Underline the keywords, then scan the passage.');
    await block(3, 'Bảng').getByLabel('Dòng tiêu đề của bảng').fill('Step | Action');
    await block(3, 'Bảng').getByLabel(/^Các dòng của bảng/).fill('1 | Skim\n2 | Scan');
    await block(4, 'Hình ảnh').getByLabel('Mã tệp hình ảnh').fill(media.imageId);
    await block(4, 'Hình ảnh').getByLabel(/^Mô tả hình/).fill('A one pixel diagram');
    await block(5, 'Âm thanh').getByLabel('Mã tệp âm thanh').fill(media.audioId);
    await block(6, 'Video tải lên').getByLabel('Mã tệp video').fill(videoId);
    await block(7, 'Video YouTube/Vimeo').getByLabel('Địa chỉ video').fill('https://evil.example.com/watch?v=dQw4w9WgXcQ');
    await block(8, 'Hộp mẹo/bẫy').getByLabel('Nội dung').fill('Answers keep the words of the passage.');
    await block(9, 'Hộp mẹo/bẫy').getByLabel('Nội dung').fill('A word that matches is not always the answer.');
    await block(10, 'Câu hỏi nhúng').getByLabel('Mã phiên bản câu hỏi', { exact: true }).fill(q.versionId);
    await block(11, 'Ví dụ giải').getByLabel('Tên ví dụ').fill('Worked example 1');
    await block(11, 'Ví dụ giải').getByLabel('Đề bài').fill('Where was the meeting held?');
    await block(11, 'Ví dụ giải').getByLabel('Các bước giải').fill('Underline "meeting".\nFind the sentence with the place.');
    await block(11, 'Ví dụ giải').getByLabel('Kết quả (không bắt buộc)').fill('In the library');

    // the trap block is the second box: switch its kind
    await block(9, 'Hộp mẹo/bẫy').getByRole('combobox', { name: 'Loại hộp' }).click();
    await page.getByRole('option', { name: 'Bẫy', exact: true }).click();

    // a video from another site is refused, with the path of the block and the reason, next to the field
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    const alert = page.getByRole('alert').filter({ hasText: 'Bài chưa đúng' });
    await expect(alert.getByText('blocks[6].url')).toBeVisible();
    await expect(page, 'nothing was created').toHaveURL(/\/cms\/strategy-lessons\/new$/);
    await expect(block(7, 'Video YouTube/Vimeo').getByText(/not an address of an allowed video host/)).toBeVisible();

    // YouTube is accepted
    await block(7, 'Video YouTube/Vimeo').getByLabel('Địa chỉ video').fill('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page).toHaveURL(/\/cms\/strategy-lessons\/[0-9a-f-]{36}$/);
    await expect(page.getByText('Bản nháp', { exact: true })).toBeVisible();
    const lessonId = page.url().split('/').pop()!;

    // what the screen saved is what the server holds, in the order of the screen
    const saved = await org.api(s.author.token, 'GET', `/api/cms/strategy-lessons/${lessonId}`, { org: s.orgId });
    expect(saved.status, saved.text).toBe(200);
    expect(saved.body).toMatchObject({ title, status: 'DRAFT', versionNo: 1, questionTypeCodes: ['IELTS_R_SHORT_ANSWER'] });
    expect(saved.body.blocks.map((b: { type: string }) => b.type)).toEqual([
      'heading', 'paragraph', 'table', 'image', 'audio', 'video-upload', 'video-embed', 'callout', 'callout', 'embedded-question', 'worked-example',
    ]);
    expect(saved.body.blocks[2]).toMatchObject({ header: ['Step', 'Action'], rows: [['1', 'Skim'], ['2', 'Scan']] });
    expect(saved.body.blocks[6]).toMatchObject({ provider: 'youtube', url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' });
    expect(saved.body.blocks[7]).toMatchObject({ kind: 'tip' });
    expect(saved.body.blocks[8]).toMatchObject({ kind: 'trap' });
    expect(saved.body.blocks[9]).toMatchObject({ questionVersionId: q.versionId });
    expect(saved.body.blocks[10]).toMatchObject({ steps: ['Underline "meeting".', 'Find the sentence with the place.'], answer: 'In the library' });

    // opened again from the server: the same blocks in the same fields; a block moves up
    await page.reload();
    await expect(page.getByLabel(/^Tên bài/)).toHaveValue(title);
    await expect(block(4, 'Hình ảnh').getByLabel('Mã tệp hình ảnh')).toHaveValue(media.imageId);
    await block(2, 'Đoạn văn').getByRole('button', { name: 'Lên' }).click();
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page.getByText('Đã lưu bản nháp.')).toBeVisible();
    const moved = await org.api(s.author.token, 'GET', `/api/cms/strategy-lessons/${lessonId}`, { org: s.orgId });
    expect(moved.body.blocks.slice(0, 2).map((b: { type: string }) => b.type)).toEqual(['paragraph', 'heading']);
    await page.goto('/cms/strategy-lessons');
    await expect(page.getByRole('link', { name: title })).toBeVisible();
  });

  test('F14 a lesson needs at least one question type of its exam', async ({ page, request, auth, org, uniqueEmail }) => {
    const s = await staffed(['IELTS'], 'f14-types', { request, uniqueEmail, apiLogin: org.apiLogin });
    await auth.loginAs(page, s.authorLogin);
    await page.goto('/cms/strategy-lessons/new');
    await page.getByRole('combobox', { name: 'Kỳ thi' }).click();
    await page.getByRole('option', { name: 'IELTS', exact: true }).click();
    await page.getByLabel(/^Tên bài/).fill('No type');
    await addBlock(page, 'Đoạn văn');
    await page.getByRole('region', { name: 'Khối 1: Đoạn văn' }).getByLabel('Nội dung').fill('Text.');
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'Bài chưa đúng' }).getByText('questionTypeCodes')).toBeVisible();
    await expect(page.getByText(/ít nhất một dạng câu/).first()).toBeVisible();
    await expect(page).toHaveURL(/\/cms\/strategy-lessons\/new$/);
  });

  test('F14 the lesson is published only after its embedded question is, by a review; version 1 is then untouched by an edit', async ({ page, request, auth, seed, org, uniqueEmail }) => {
    const s = await staffed(['IELTS'], 'f14-review', { request, uniqueEmail, apiLogin: org.apiLogin });
    const draftQuestion = await createQuestion(s, 'IELTS_R_SHORT_ANSWER');
    expect(draftQuestion.status, draftQuestion.text).toBe(201);
    const { versionId } = draftQuestion.body as { versionId: string };
    const body = { examCode: 'IELTS', title: 'Gated lesson', questionTypeCodes: ['IELTS_R_SHORT_ANSWER'], blocks: [{ type: 'paragraph', text: 'Intro.' }, { type: 'embedded-question', questionVersionId: versionId }] };
    const made = await org.api(s.author.token, 'POST', '/api/cms/strategy-lessons', { org: s.orgId, body });
    expect(made.status, made.text).toBe(201);
    const lesson = made.body as { lessonId: string; versionId: string };

    // the author submits it on the screen; the version is locked
    await auth.loginAs(page, s.authorLogin);
    await page.goto(`/cms/strategy-lessons/${lesson.lessonId}`);
    await page.getByRole('button', { name: 'Gửi duyệt' }).click();
    await expect(page.getByText(/Đã gửi duyệt/)).toBeVisible();
    const locked = await org.api(s.author.token, 'PUT', `/api/cms/strategy-lessons/${lesson.lessonId}`, { org: s.orgId, body });
    expect(locked.status).toBe(409);
    expect(locked.body.type).toBe('urn:e9x:problem:version-under-review');

    // the question is still a draft, so the lesson cannot be approved and stays in review (the refusal itself is asserted in StrategyLessonIT)
    const status = async () => ((await org.api(s.reviewer.token, 'GET', `/api/cms/strategy-lessons/${lesson.lessonId}/versions`, { org: s.orgId })).body as Array<{ status: string }>).map((v) => v.status);
    expect(await status()).toEqual(['IN_REVIEW']);

    // a lesson that names a question nobody may see, or one of another exam, is refused at once
    const ghost = await org.api(s.author.token, 'POST', '/api/cms/strategy-lessons', { org: s.orgId, body: { ...body, blocks: [{ type: 'embedded-question', questionVersionId: '00000000-0000-0000-0000-000000000000' }] } });
    expect(ghost.status).toBe(422);
    expect(ghost.body.errors[0].field).toBe('blocks[0].questionVersionId');

    // a second lesson whose question is published goes through the real review to PUBLISHED, and editing it then makes version 2
    const published = await publishedQuestion(s, 'IELTS_R_SHORT_ANSWER');
    const ok = await org.api(s.author.token, 'POST', '/api/cms/strategy-lessons', { org: s.orgId, body: { ...body, title: 'Open lesson', blocks: [{ type: 'embedded-question', questionVersionId: published.versionId }] } });
    expect(ok.status, ok.text).toBe(201);
    await seed.reviewVersion({ type: 'strategy_lesson', versionId: ok.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    const after = await org.api(s.reviewer.token, 'GET', `/api/cms/strategy-lessons/${ok.body.lessonId}`, { org: s.orgId });
    expect(after.body.status).toBe('PUBLISHED');

    await page.goto(`/cms/strategy-lessons/${ok.body.lessonId}`);
    await expect(page.getByText(/Bản này đã xuất bản và không sửa được/)).toBeVisible();
    await page.getByLabel(/^Tên bài/).fill('Open lesson, revised');
    await page.getByRole('button', { name: 'Lưu thành phiên bản mới' }).click();
    await expect(page.getByText(/Đã tạo bản nháp mới, phiên bản 2/)).toBeVisible();
    const all = (await org.api(s.reviewer.token, 'GET', `/api/cms/strategy-lessons/${ok.body.lessonId}/versions`, { org: s.orgId })).body as Array<{ versionNo: number; status: string; title: string }>;
    expect(all.map((v) => [v.versionNo, v.status, v.title])).toEqual([[1, 'PUBLISHED', 'Open lesson'], [2, 'DRAFT', 'Open lesson, revised']]);
  });

  test('F14 an author of another exam and a reviewer cannot write or read the lesson', async ({ request, org, uniqueEmail }) => {
    const s = await staffed(['IELTS', 'TOEIC'], 'f14-roles', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const other = await staffed(['TOEIC'], 'f14-other', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const body = { examCode: 'IELTS', title: 'Mine', questionTypeCodes: ['IELTS_R_SHORT_ANSWER'], blocks: [{ type: 'paragraph', text: 'x' }] };
    const made = await org.api(s.author.token, 'POST', '/api/cms/strategy-lessons', { org: s.orgId, body });
    expect(made.status, made.text).toBe(201);
    expect((await org.api(s.reviewer.token, 'POST', '/api/cms/strategy-lessons', { org: s.orgId, body })).status).toBe(403);
    expect((await org.api(other.author.token, 'GET', `/api/cms/strategy-lessons/${made.body.lessonId}`, { org: other.orgId })).status).toBe(404);
    expect((await org.api(other.author.token, 'POST', '/api/cms/strategy-lessons', { org: other.orgId, body })).status).toBe(403);
  });
});
