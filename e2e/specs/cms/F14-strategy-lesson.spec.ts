// F14 — Bài phương pháp (spec §16.2): "Soạn bài có đủ loại khối (văn bản, bảng, ảnh, audio, video upload, YouTube, mẹo, câu nhúng, ví dụ giải) → học viên thấy đúng".
//
// The REAL stack: the author builds a lesson with every block type on the editor screen; the server validates each block against its schema (video
// hosts against the allowlist, files and the embedded question against the exam), keeps the blocks in order, and the lesson is published only through a
// review that waits for the embedded question to be published. The part about the LEARNER seeing it ("học viên thấy đúng") is the last test: a
// learner with a plan opens the published lesson on the real lesson page (/learn/lessons/:id) and reads all ten kinds of block in order, with the video
// frame the CSP allows, the embedded question answerable, and no answer or explanation anywhere until it has been answered.
import { test, expect } from '../../fixtures';
import { publishedQuestion, createQuestion, staffed, uploadFile } from '../../fixtures/content';
import { newCode } from '../../fixtures/course';
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
  test('F14 the learner reads the published lesson on the lesson page: every kind of block in order, the video frame the CSP allows, the question embedded in it answered, and no answer before that', async ({ page, request, auth, seed, org, uniqueEmail }) => {
    test.setTimeout(240_000);
    const s = await staffed(['TOEIC'], 'f14-learner', { request, uniqueEmail, apiLogin: org.apiLogin });
    const media = s.mediaByExam.TOEIC!;
    const mark = s.author.userId.slice(0, 6);
    const videoId = await uploadFile(request, s.author.token, s.orgId, 'TOEIC', 'clip.mp4', 'video/mp4');
    const q = await publishedQuestion(s, 'TOEIC_P5_INCOMPLETE_SENTENCE');
    const texts = {
      heading: `Read the question first ${mark}`,
      paragraph: `Underline the keywords, then scan the passage ${mark}.`,
      tip: `Answers keep the words of the passage ${mark}.`,
      trap: `A word that matches is not always the answer ${mark}.`,
      example: `Worked example ${mark}`,
      problem: `Where was the meeting held ${mark}?`,
      step1: `Underline "meeting" ${mark}.`,
      step2: `Find the sentence with the place ${mark}.`,
      answer: `In the library ${mark}`,
    };
    const made = await org.api(s.author.token, 'POST', '/api/cms/strategy-lessons', {
      org: s.orgId,
      body: {
        examCode: 'TOEIC',
        title: `Method ${mark}`,
        questionTypeCodes: ['TOEIC_P5_INCOMPLETE_SENTENCE'],
        blocks: [
          { type: 'heading', level: 2, text: texts.heading },
          { type: 'paragraph', text: texts.paragraph },
          { type: 'table', header: ['Step', 'Action'], rows: [['1', 'Skim'], ['2', 'Scan']], caption: 'The two steps' },
          { type: 'image', imageMediaId: media.imageId, alt: 'A one pixel diagram', caption: 'The diagram' },
          { type: 'audio', audioMediaId: media.audioId, caption: 'The recording' },
          { type: 'video-upload', videoMediaId: videoId, caption: 'The clip' },
          { type: 'video-embed', provider: 'youtube', url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', caption: 'The method video' },
          { type: 'callout', kind: 'tip', text: texts.tip },
          { type: 'callout', kind: 'trap', text: texts.trap },
          { type: 'embedded-question', questionVersionId: q.versionId },
          { type: 'worked-example', title: texts.example, problem: texts.problem, steps: [texts.step1, texts.step2], answer: texts.answer },
        ],
      },
    });
    expect(made.status, made.text).toBe(201);
    await seed.reviewVersion({ type: 'strategy_lesson', versionId: made.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    const course = await org.api(s.author.token, 'POST', '/api/cms/courses', {
      org: s.orgId,
      body: { examCode: 'TOEIC', code: newCode(), title: `Method course ${mark}`, description: 'd', authorDisplay: 'Ms Lan', unlockRule: 'FREE', chapters: [{ title: 'Chapter 1', passThreshold: 0.7, lessons: [{ kind: 'LESSON', refId: made.body.versionId }] }] },
    });
    expect(course.status, course.text).toBe(201);
    await seed.reviewVersion({ type: 'course', versionId: course.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    const lessonId = course.body.chapters[0].lessons[0].id as string;
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f14-learner') });
    await seed.billing.entitlement({ userId: learner.userId, courseId: course.body.courseId, endsInDays: 30 });
    const token = (await org.apiLogin(learner)).token;

    // what the server hands the learner has no answer key and no explanation at all
    const raw = await org.api(token, 'GET', `/api/learning/lessons/${lessonId}`);
    expect(raw.status, raw.text).toBe(200);
    for (const secret of ['answerKey', 'answer_key', 'explanation', 'because', 'Script of the recording']) expect(raw.text, secret).not.toContain(secret);

    const violations: string[] = [];
    page.on('console', (m) => {
      if (/Content Security Policy|Refused to (frame|load)/i.test(m.text())) violations.push(m.text());
    });
    await auth.loginAs(page, learner);
    const doc = await page.goto(`/learn/lessons/${lessonId}`);
    const csp = (await doc!.allHeaders())['content-security-policy'] ?? '';
    expect(csp, 'the page may frame YouTube and Vimeo and nothing else').toMatch(/frame-src https:\/\/www\.youtube\.com https:\/\/player\.vimeo\.com;/);

    const article = page.locator('article');
    await expect(article.getByRole('heading', { level: 1, name: `Method ${mark}` })).toBeVisible();
    await expect(article.getByRole('heading', { level: 2, name: texts.heading })).toBeVisible();
    await expect(article.getByText(texts.paragraph)).toBeVisible();
    await expect(article.getByRole('columnheader', { name: 'Step' })).toBeVisible();
    await expect(article.getByRole('cell', { name: 'Scan' })).toBeVisible();
    const image = article.getByRole('img', { name: 'A one pixel diagram' });
    await expect(image).toBeVisible();
    await expect.poll(() => image.evaluate((el) => (el as HTMLImageElement).naturalWidth), 'the image loads from its signed link').toBeGreaterThan(0);
    await expect(article.locator('audio[aria-label="The recording"]')).toHaveAttribute('src', /\/api\/media\//);
    await expect(article.locator('video[aria-label="The clip"]')).toHaveAttribute('src', /\/api\/media\//);
    const frame = article.locator('iframe[title="Video: The method video"]');
    await expect(frame, 'the frame is rebuilt on the provider\'s own embed address').toHaveAttribute('src', 'https://www.youtube.com/embed/dQw4w9WgXcQ');
    await expect(article.getByRole('complementary', { name: 'Mẹo' })).toContainText(texts.tip);
    await expect(article.getByRole('complementary', { name: 'Bẫy' })).toContainText(texts.trap);
    // the blocks are in the order the author wrote them
    const body = await article.innerText();
    const at = [texts.heading, texts.paragraph, 'The two steps', 'The diagram', 'The recording', 'The clip', 'The method video', texts.tip, texts.trap, 'Thử một câu', texts.example].map((x) => body.indexOf(x));
    expect(at.every((x) => x >= 0), `every block is on the page: ${at}`).toBe(true);
    expect(at, 'in order').toEqual([...at].sort((a, b) => a - b));

    // the worked example keeps its result back until it is asked for
    const example = article.getByRole('region', { name: `Ví dụ giải: ${texts.example}` });
    await expect(example.getByText(texts.problem)).toBeVisible();
    await expect(example.getByText(texts.step2)).toBeVisible();
    await expect(example.getByText(texts.answer)).toHaveCount(0);
    await example.getByRole('button', { name: 'Xem kết quả' }).click();
    await expect(example.getByText(texts.answer)).toBeVisible();

    // the embedded question: no answer and no explanation until it is answered; a wrong answer then shows the right one and why
    const question = article.getByRole('region', { name: 'Thử một câu' });
    await expect(question.getByRole('radio')).toHaveCount(4);
    for (const hidden of ['Đáp án:', 'Giải thích', 'because']) await expect(page.getByText(hidden), `before answering: ${hidden}`).toHaveCount(0);
    await question.getByRole('radio', { name: /^A\./ }).check();
    await question.getByRole('button', { name: 'Kiểm tra' }).click();
    await expect(question.getByText('Chưa đúng')).toBeVisible();
    await expect(question.getByText('Đáp án: D')).toBeVisible();
    await expect(question.getByText('because')).toBeVisible();
    await expect(question.getByRole('radio').first()).toBeDisabled();

    expect(violations, 'the CSP refused nothing on the lesson page').toEqual([]);
  });
});
