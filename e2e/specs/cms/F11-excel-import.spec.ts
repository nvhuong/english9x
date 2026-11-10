// F11 — Import Excel (spec §16.2): "Tải mẫu → upload file có lỗi → nhận lỗi theo dòng + file tô đỏ → sửa → import thành công → lô ở trạng thái nháp".
//
// The REAL stack: the browser uploads through Caddy (the 600 MB upload route) to the app, the background worker (excel_import) validates every row against the
// JSON Schemas of the type, and the files are staged in MinIO. Fixtures: e2e/fixtures/excel (filled with the headers the template of the type has).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';

const EXCEL = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', 'fixtures', 'excel');
const file = (name: string) => join(EXCEL, name);

/** The type select lists more types than fit: choose with the keyboard, like the question editor does. */
async function chooseType(page: Page, code: string) {
  await page.getByRole('combobox', { name: 'Dạng câu hỏi' }).click();
  await page.getByRole('option', { name: code, exact: true }).focus();
  await page.keyboard.press('Enter');
}

/** Uploads and returns the id of the import the server made. */
async function upload(page: Page, xlsx: string, zip?: string): Promise<string> {
  await page.getByLabel('File Excel đã điền (.xlsx)').setInputFiles(xlsx);
  if (zip) await page.getByLabel('File zip media (tuỳ chọn)').setInputFiles(zip);
  const started = page.waitForResponse((r) => r.url().includes('/api/cms/import?typeCode=') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Bắt đầu import' }).click();
  const res = await started;
  expect(res.status(), await res.text()).toBe(202);
  return ((await res.json()) as { jobId: string }).jobId;
}

test.describe('F11 excel import', () => {
  test('F11 download the template, upload a file with errors, get the errors by row and the red workbook and NOTHING is created, fix it, import it: the lot is drafts', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(180_000);
    const s = await staffed(['TOEIC'], 'f11', { request, uniqueEmail, apiLogin: org.apiLogin });
    await auth.loginAs(page, s.authorLogin);
    await page.goto('/cms/import');
    await expect(page.getByRole('heading', { level: 1, name: 'Import câu hỏi từ Excel' })).toBeVisible();
    await chooseType(page, 'TOEIC_P5_INCOMPLETE_SENTENCE');

    // 1. the template of the type
    const template = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Tải mẫu Excel' }).click();
    const t = await template;
    expect(t.suggestedFilename()).toBe('TOEIC_P5_INCOMPLETE_SENTENCE.xlsx');
    const templateBytes = readFileSync((await t.path())!);
    expect(templateBytes.subarray(0, 2).toString(), 'an .xlsx is a zip').toBe('PK');

    // 2. a file with errors: row 3 has no answer, row 5 has no text for option B
    const bad = await upload(page, file('toeic_p5_errors.xlsx'));
    await expect(page.getByText('File bị từ chối: 2 lỗi.')).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText('Không có câu hỏi nào được tạo. Sửa các dòng lỗi rồi tải lại.')).toBeVisible();
    const rowOf = (n: string) => page.getByRole('cell', { name: n, exact: true }).locator('xpath=ancestor::tr');
    await expect(rowOf('3')).toContainText('answer.slots.1');
    await expect(rowOf('3')).toContainText('thiếu đáp án');
    await expect(rowOf('5')).toContainText('options.2.text');

    // the workbook comes back with the failed cells marked
    const report = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Tải file Excel tô đỏ ô lỗi' }).click();
    const r = await report;
    expect(r.suggestedFilename()).toBe('loi-toeic_p5_errors.xlsx');
    expect(readFileSync((await r.path())!).subarray(0, 2).toString()).toBe('PK');

    // nothing was created, over the API too
    const rejected = await org.api(s.author.token, 'GET', `/api/cms/import/${bad}`, { org: s.orgId });
    expect(rejected.body.job).toMatchObject({ status: 'REJECTED', createdCount: 0, errorCount: 2 });
    expect(rejected.body.errors).toEqual(expect.arrayContaining([{ row: 3, column: 'answer.slots.1', message: 'thiếu đáp án' }]));
    expect(rejected.body.versionIds).toEqual([]);
    const none = await org.api(s.author.token, 'GET', '/api/cms/questions?examCode=TOEIC&size=100', { org: s.orgId });
    expect(none.body.total, 'a rejected import creates no question').toBe(0);

    // 3. the fixed file: every row becomes a draft, as one lot
    await page.reload();
    await chooseType(page, 'TOEIC_P5_INCOMPLETE_SENTENCE');
    const good = await upload(page, file('toeic_p5_ok.xlsx'));
    await expect(page.getByText('Đã tạo 3 bản nháp.')).toBeVisible({ timeout: 60_000 });
    const made = await org.api(s.author.token, 'GET', `/api/cms/import/${good}`, { org: s.orgId });
    expect(made.body.job).toMatchObject({ status: 'SUCCEEDED', createdCount: 3 });
    expect(made.body.versionIds).toHaveLength(3);
    const drafts = await org.api(s.author.token, 'GET', '/api/cms/questions?examCode=TOEIC&size=100', { org: s.orgId });
    expect(drafts.body.total).toBe(3);
    expect((drafts.body.items as Array<{ status: string; createdBy: string }>).every((q) => q.status === 'DRAFT' && q.createdBy === s.author.userId)).toBe(true);

    // 4. only now does the lot go to review, together
    await page.getByRole('button', { name: 'Gửi duyệt cả lô' }).click();
    await expect(page.getByText('Đã gửi duyệt cả lô.')).toBeVisible();
    const submitted = await org.api(s.author.token, 'GET', '/api/cms/questions?examCode=TOEIC&size=100', { org: s.orgId });
    expect((submitted.body.items as Array<{ status: string }>).map((q) => q.status)).toEqual(['IN_REVIEW', 'IN_REVIEW', 'IN_REVIEW']);
  });

  test('F11 the media zip: the files a row names become media of the exam and the drafts point at them; a file missing from the zip is named', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(180_000);
    const s = await staffed(['IELTS'], 'f11-zip', { request, uniqueEmail, apiLogin: org.apiLogin });
    await auth.loginAs(page, s.authorLogin);
    await page.goto('/cms/import');
    await chooseType(page, 'IELTS_R_MATCHING_HEADINGS');

    // the workbook names pixel.png but no zip is sent: the rows say so and nothing is made
    const without = await upload(page, file('ielts_r_headings_ok.xlsx'));
    await expect(page.getByText('File bị từ chối: 2 lỗi.')).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole('cell', { name: 'image_media_file' }).first()).toBeVisible();
    expect((await org.api(s.author.token, 'GET', `/api/cms/import/${without}`, { org: s.orgId })).body.job.createdCount).toBe(0);

    // with the zip it imports, and the file is a real media file of the IELTS exam
    await page.reload();
    await chooseType(page, 'IELTS_R_MATCHING_HEADINGS');
    const id = await upload(page, file('ielts_r_headings_ok.xlsx'), file('media.zip'));
    await expect(page.getByText('Đã tạo 2 bản nháp.')).toBeVisible({ timeout: 60_000 });
    const list = await org.api(s.author.token, 'GET', '/api/cms/questions?examCode=IELTS&size=100', { org: s.orgId });
    expect(list.body.total).toBe(2);
    const detail = await org.api(s.author.token, 'GET', `/api/cms/import/${id}`, { org: s.orgId });
    const first = await org.api(s.author.token, 'GET', `/api/cms/questions/${list.body.items[0].questionId}`, { org: s.orgId });
    const mediaId = first.body.content.imageMediaId as string;
    expect(mediaId).toMatch(/^[0-9a-f-]{36}$/);
    const link = await org.api(s.author.token, 'GET', `/api/media/${mediaId}/url`, { org: s.orgId });
    expect(link.status, link.text).toBe(200);
    expect(detail.body.versionIds).toHaveLength(2);
  });
});
