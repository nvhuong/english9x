import { expect, type Page } from '@playwright/test';
import type { SeededPart, SeededQuestion } from '../../fixtures/exam';

export const FINISH = /Kết thúc phần này|Nộp bài/;

export async function enterExam(page: Page, testVersionId: string): Promise<string> {
  await page.goto(`/exam/start/${testVersionId}`);
  await expect(page).toHaveURL(/\/exam\/attempts\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('region', { name: 'Câu hỏi' })).toBeVisible();
  return /attempts\/([0-9a-f-]{36})/.exec(page.url())![1]!;
}

/** The names of the navigator buttons: "Câu 3, chưa trả lời" ... */
export const navigator = (page: Page) => page.getByRole('group', { name: 'Danh sách câu hỏi' }).getByRole('button');

/** Presses the shortcut letter (A-D) of a keyed question and goes to the next one with the right arrow. Focus is on the page, not on a control. */
export async function answerByKeyboard(page: Page, questions: SeededQuestion[], opts: { wrong?: (i: number) => boolean } = {}): Promise<void> {
  for (const [i, q] of questions.entries()) {
    let letter = String(q.answer);
    if (opts.wrong?.(i)) letter = letter === 'A' ? 'B' : 'A';
    await page.keyboard.press(letter);
    if (i < questions.length - 1) await page.keyboard.press('ArrowRight');
  }
}

export async function finishSection(page: Page): Promise<void> {
  await page.getByRole('button', { name: FINISH }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Xác nhận' }).click();
}

export async function expectAllAnswered(page: Page, n: number): Promise<void> {
  await expect(navigator(page)).toHaveCount(n);
  await expect(page.getByRole('group', { name: 'Danh sách câu hỏi' }).getByRole('button', { name: /chưa trả lời/ })).toHaveCount(0);
}

export const partOf = (parts: SeededPart[], code: string) => parts.find((p) => p.sectionCode === code)!;

/** Answers one question of the IELTS test through its own control (checkbox, typed gap, select, radio, text box, essay). Returns nothing; the caller moves on. */
export async function answerIelts(page: Page, q: SeededQuestion, number: number, opts: { wrong?: boolean } = {}): Promise<void> {
  const a = q.answer;
  switch (q.typeCode) {
    case 'IELTS_L_MULTIPLE_CHOICE':
    case 'IELTS_R_MULTIPLE_CHOICE':
      for (const key of a as string[]) await page.getByRole('checkbox', { name: new RegExp(`^${key}\\.`) }).check();
      break;
    case 'IELTS_L_SENTENCE_COMPLETION':
      await page.getByLabel('Chỗ trống 1').fill((a as string[])[0]!);
      await page.getByLabel('Chỗ trống 2').fill((a as string[])[1]!);
      break;
    case 'IELTS_L_MATCHING':
      for (const [i, key] of (a as string[]).entries()) await page.getByLabel(`Ghép cho mục ${number + i}`).selectOption(key);
      break;
    case 'IELTS_L_SHORT_ANSWER':
    case 'IELTS_R_SHORT_ANSWER':
      await page.getByRole('textbox').fill(String(a));
      break;
    case 'IELTS_R_IDENTIFYING_INFORMATION':
    case 'IELTS_R_IDENTIFYING_WRITERS_VIEWS': {
      const right = String(a).toUpperCase();
      const choice = opts.wrong ? (right === 'YES' ? 'NO' : 'YES') : right;
      await page.getByRole('radio', { name: choice, exact: true }).check();
      break;
    }
    default:
      throw new Error(`no answer step for ${q.typeCode}`);
  }
}
