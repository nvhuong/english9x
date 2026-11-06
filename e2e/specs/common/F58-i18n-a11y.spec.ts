// F58 — i18n + accessibility: every screen is scanned with axe (WCAG 2.1 A/AA, serious and critical fail) in light and
// dark, and the vi/en language switch really changes the UI and survives a reload.
//
// !! F58 IS NOT FINISHED. The coverage gate counts this file as "covered" because it holds active tests, but spec
// !! §16.2 asks for the 8 MAIN SCREENS to be scanned and most of them do not exist yet. F58 finishes (W10) only when
// !! every screen in PENDING_MAIN_SCREENS below has moved into SCREENS. Each run prints the pending list as a test
// !! annotation. Tracking: docs/superpowers/plans/2026-11-06-english9x-p1-foundation.md, task P1.12 note, referenced
// !! from the W10 completion criteria. Do not delete the pending list to make it look done.
//
// Scanned today (reachable without signing in): learner home, display preferences, exam area (empty state), the
// access-denied screen as shown for /cms, /org and /admin, the not-found screen, and (P1.4) the sign-in family: login,
// register, forgot password, reset password (with a token) and the verify-email failure screen. The empty states of cms / org /
// admin themselves need a signed-in role (P1.4/P1.5) and join SCREENS then. Component-level axe runs in Storybook
// (`pnpm -C frontend run test:a11y`); this spec covers the assembled screens in the real app behind Caddy.
import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';

interface Screen { name: string; path: string; heading: { vi: RegExp; en: RegExp } }

const SCREENS: Screen[] = [
  { name: 'learner home (empty catalogue)', path: '/', heading: { vi: /^English9x$/, en: /^English9x$/ } },
  { name: 'display preferences', path: '/preferences', heading: { vi: /Tuỳ chọn hiển thị/, en: /Display preferences/ } },
  { name: 'exam area (nothing selected)', path: '/exam', heading: { vi: /Chưa chọn bài thi/, en: /No exam selected/ } },
  { name: 'access denied (/cms)', path: '/cms', heading: { vi: /Bạn chưa có quyền xem trang này/, en: /You do not have access to this page/ } },
  { name: 'access denied (/org)', path: '/org', heading: { vi: /Bạn chưa có quyền xem trang này/, en: /You do not have access to this page/ } },
  { name: 'access denied (/admin)', path: '/admin', heading: { vi: /Bạn chưa có quyền xem trang này/, en: /You do not have access to this page/ } },
  { name: 'not found', path: '/khong-ton-tai', heading: { vi: /Không tìm thấy trang/, en: /Page not found/ } },
  // P1.4 identity screens
  { name: 'sign in', path: '/login', heading: { vi: /^Đăng nhập$/, en: /^Sign in$/ } },
  { name: 'sign up', path: '/register', heading: { vi: /^Tạo tài khoản$/, en: /^Create an account$/ } },
  { name: 'forgot password', path: '/forgot-password', heading: { vi: /^Quên mật khẩu$/, en: /^Forgot your password$/ } },
  { name: 'reset password', path: '/reset-password?token=axe-scan-token', heading: { vi: /^Đặt mật khẩu mới$/, en: /^Choose a new password$/ } },
  { name: 'verify email (broken link)', path: '/verify-email', heading: { vi: /^Không xác thực được$/, en: /^Could not verify$/ } },
];

// The 8 main screens of the design mockups (docs/superpowers/design/mockups/01..08) and the task that builds each.
// Move an entry into SCREENS (with a real route and real data from the seed API) as soon as it exists.
const PENDING_MAIN_SCREENS = [
  '01 course catalogue (P3.1)',
  '02 chapter map (P3.1)',
  '03 lesson (P3.2)',
  '04 exam room, Reading (P3.4)',
  '05 exam room, Speaking (P3.4)',
  '06 result (P4.4)',
  '07 CMS review queue (P2.9)',
  '08 admin QR orders (P5.4)',
];

const THEMES = ['light', 'dark'] as const;

async function useStored(page: Page, values: { theme?: 'light' | 'dark'; lang?: 'vi' | 'en' }) {
  await page.addInitScript((v) => {
    if (v.theme) localStorage.setItem('e9x.theme', v.theme);
    if (v.lang) localStorage.setItem('e9x.lang', v.lang);
  }, values);
}

async function scan(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const blocking = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  const summary = blocking.map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(' ')).join(' | ')} — ${v.help}`);
  expect(summary, 'serious/critical axe violations').toEqual([]);
  expect(results.passes.length, 'axe actually ran rules').toBeGreaterThan(10);
}

test.beforeEach(() => {
  test.info().annotations.push({ type: 'F58 pending main screens', description: PENDING_MAIN_SCREENS.join('; ') });
});

for (const screen of SCREENS) {
  for (const theme of THEMES) {
    test(`F58 axe: ${screen.name} in ${theme} mode`, async ({ page }) => {
      await useStored(page, { theme, lang: 'vi' });
      await page.goto(screen.path);
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(screen.heading.vi);
      // the theme is really applied (a scan of the wrong theme would prove nothing)
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      await expect(page.locator('html')).toHaveAttribute('lang', 'vi');
      await scan(page);
    });
  }
}

test.describe('language and theme switches', () => {
  test.use({ colorScheme: 'light' });

  test('F58 switching to English changes the UI, <html lang>, survives a reload and can be switched back', async ({ page }) => {
    // fresh browser context: no stored choice, so the default language (vi) is what the user starts with
    await page.goto('/preferences');
    await expect(page.locator('html')).toHaveAttribute('lang', 'vi');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Tuỳ chọn hiển thị');

    await page.locator('#main').getByRole('group', { name: 'Ngôn ngữ' }).getByRole('button', { name: /EN/ }).click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Display preferences');
    await expect(page.locator('#main').getByRole('group', { name: 'Language' }).getByRole('button', { name: /EN/ })).toHaveAttribute('aria-pressed', 'true');
    expect(await page.evaluate(() => localStorage.getItem('e9x.lang'))).toBe('en');

    // navigate inside the app: the other screens follow the choice
    await page.goto('/exam');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/No exam selected/);
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/No exam selected/);

    await page.goto('/preferences');
    await page.locator('#main').getByRole('group', { name: 'Language' }).getByRole('button', { name: /VI/ }).click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'vi');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Tuỳ chọn hiển thị');
  });

  for (const screen of SCREENS) {
    for (const theme of THEMES) {
      test(`F58 axe: ${screen.name} in English, ${theme} mode`, async ({ page }) => {
        await useStored(page, { theme, lang: 'en' });
        await page.goto(screen.path);
        await expect(page.locator('html')).toHaveAttribute('lang', 'en');
        await expect(page.getByRole('heading', { level: 1 })).toHaveText(screen.heading.en);
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        await scan(page);
      });
    }
  }

  test('F58 the theme switch applies dark mode and is remembered', async ({ page }) => {
    await page.goto('/preferences'); // fresh context, system colour scheme is light (set below)
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await page.locator('#main').getByRole('group', { name: 'Giao diện' }).getByRole('button', { name: 'Tối' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await scan(page);
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  });
});
