// F58 — i18n + accessibility: every screen is scanned with axe (WCAG 2.1 A/AA, serious and critical fail) in light and
// dark, and the vi/en language switch really changes the UI and survives a reload.
//
// The 8 MAIN SCREENS of the design mockups (docs/superpowers/design/mockups/01..08) are scanned too, each on its real route in its real
// state, with data made through the real stack (specs/common/_main-screens.ts): 01 course catalogue (a guest, one published course listed),
// 02 chapter map and 03 lesson (a learner with a plan, STRICT course), 04 exam room Reading and 05 exam room Speaking (IELTS attempts at
// that section), 06 result (a TOEIC attempt scored by the real job), 07 CMS review queue (a submitted question), 08 admin QR orders (a
// pending order). Vietnamese and English, light and dark, serious/critical axe violations fail.
//
// Scanned today, signed in (P1.5): the admin area (empty state), organisations, one organisation's members + invitations, staff
// invitations, the audit log (P1.9, with records), and the organisation admin's members page in its context. Both languages, light and dark.
// Scanned today, signed in (P1.8): the notification centre with a notification in it, and the notification preferences with the
// Telegram panel. Both languages, light and dark.
// Scanned today (reachable without signing in): learner home, display preferences, exam area (empty state), the
// access-denied screen as shown for /cms, /org and /admin, the not-found screen, and (P1.4) the sign-in family: login,
// register, forgot password, reset password (with a token), the verify-email failure screen, and the two steps of a Google
// sign-in that take a real session to reach (consent, "you are already signed in"). The empty states of cms / org /
// admin themselves need a signed-in role (P1.4/P1.5) and join SCREENS then. Component-level axe runs in Storybook
// (`pnpm -C frontend run test:a11y`); this spec covers the assembled screens in the real app behind Caddy.
import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { chooseGoogleAccount } from '../../helpers/google';
import { reviewWorld } from '../notify/_world';
import { loginAs } from '../../fixtures/auth';
import { env } from '../../helpers/env';
import { buildMainScreensWorld, type MainScreensWorld, type Person } from './_main-screens';

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
  // P1.5: the invitation page as a visitor sees it (the signed-in organisation screens are scanned in their own test below)
  { name: 'accept invitation (visitor)', path: '/accept-invitation?token=axe-scan-token', heading: { vi: /^Lời mời tham gia English9x$/, en: /^Invitation to join English9x$/ } },
];

// The 8 main screens of the design mockups (docs/superpowers/design/mockups/01..08). All eight exist as real routes; their paths hold ids of the
// data made at run time, so they are described by MAIN_SCREENS below (a function of the world) and not by the static SCREENS list.
// Anything added here must move into MAIN_SCREENS as soon as it has a route; the list must stay empty.
const PENDING_MAIN_SCREENS: string[] = [];

type Lang = 'vi' | 'en';
const L = <T,>(vi: T, en: T): Record<Lang, T> => ({ vi, en });
const exact = (text: string) => new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);

interface MainScreen {
  name: string;
  who: (w: MainScreensWorld) => Person | null;
  path: (w: MainScreensWorld) => string;
  heading: (w: MainScreensWorld) => Record<Lang, RegExp>;
  /** The state the screen is scanned in: what must be on the page before axe runs. */
  ready: (page: Page, w: MainScreensWorld, lang: Lang) => Promise<void>;
}

const MAIN_SCREENS: MainScreen[] = [
  {
    name: '01 course catalogue',
    who: () => null,
    path: () => '/courses',
    heading: () => ({ vi: exact('Khoá học'), en: exact('Courses') }),
    ready: async (page, w) => { await expect(page.getByRole('listitem').filter({ hasText: w.course.title })).toBeVisible(); },
  },
  {
    name: '02 chapter map',
    who: (w) => w.learner,
    path: (w) => `/learn/courses/${w.course.courseId}`,
    heading: (w) => ({ vi: exact(w.course.title), en: exact(w.course.title) }),
    ready: async (page, w) => { await expect(page.getByRole('link', { name: w.course.lessonTitle })).toBeVisible(); },
  },
  {
    name: '03 lesson',
    who: (w) => w.learner,
    path: (w) => `/learn/lessons/${w.course.lessonId}`,
    heading: (w) => ({ vi: exact(w.course.lessonTitle), en: exact(w.course.lessonTitle) }),
    ready: async (page) => { await expect(page.locator('#main')).toContainText('SECRET-LESSON-BODY'); },
  },
  {
    name: '04 exam room, Reading',
    who: (w) => w.reading.learner,
    path: (w) => `/exam/attempts/${w.reading.attemptId}`,
    heading: (w) => ({ vi: exact(w.reading.title), en: exact(w.reading.title) }),
    ready: async (page, _w, lang) => {
      await expect(page.getByRole('region', { name: L('Câu hỏi', 'Question')[lang] })).toBeVisible();
      await expect(page.getByText(lang === 'vi' ? /Phần 2\/4/ : /Section 2 of 4/)).toBeVisible();
    },
  },
  {
    name: '05 exam room, Speaking',
    who: (w) => w.speaking.learner,
    path: (w) => `/exam/attempts/${w.speaking.attemptId}`,
    heading: (w) => ({ vi: exact(w.speaking.title), en: exact(w.speaking.title) }),
    ready: async (page, _w, lang) => {
      await expect(page.getByText(lang === 'vi' ? /Phần 4\/4/ : /Section 4 of 4/)).toBeVisible();
      await expect(page.getByRole('group', { name: L('Ghi âm bài nói', 'Speaking recorder')[lang] })).toBeVisible();
    },
  },
  {
    name: '06 result',
    who: (w) => w.result.learner,
    path: (w) => `/results/attempts/${w.result.attemptId}`,
    heading: () => ({ vi: exact('Kết quả của bạn'), en: exact('Your result') }),
    ready: async (page) => {
      await expect(page.getByTestId('skill-score').first()).toBeVisible();
      await expect(page.getByTestId('question-result')).toHaveCount(2);
    },
  },
  {
    name: '07 CMS review queue',
    who: (w) => w.reviewer,
    path: () => '/cms/reviews',
    heading: () => ({ vi: exact('Hàng chờ duyệt'), en: exact('Review queue') }),
    ready: async (page) => { await expect(page.getByTestId('review-line').first()).toBeVisible(); },
  },
  {
    name: '08 admin QR orders',
    who: (w) => w.admin,
    path: () => '/admin/orders',
    heading: () => ({ vi: exact('Đơn hàng'), en: exact('Orders') }),
    ready: async (page, w) => { await expect(page.getByTestId('order-row').filter({ hasText: w.order.code })).toBeVisible(); },
  },
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
  // empty since W10: every main screen is scanned in 'F58 main screens'. A screen put back here is reported on every run.
  if (PENDING_MAIN_SCREENS.length > 0) test.info().annotations.push({ type: 'F58 pending main screens', description: PENDING_MAIN_SCREENS.join('; ') });
});

const GOOGLE_STEP_HEADINGS = {
  consent: { vi: 'Còn một bước nữa', en: 'One more step' },
  replace: { vi: 'Bạn đang đăng nhập', en: 'You are already signed in' },
} as const;

for (const lang of ['vi', 'en'] as const) {
  for (const theme of THEMES) {
    test(`F58 axe: Google consent step in ${lang}, ${theme} mode`, async ({ page, uniqueEmail }) => {
      await useStored(page, { theme, lang });
      await page.goto('/api/auth/oauth2/google');
      await chooseGoogleAccount(page, { email: uniqueEmail('f58-consent') });
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(GOOGLE_STEP_HEADINGS.consent[lang]);
      await expect(page.getByRole('checkbox')).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('lang', lang);
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      await scan(page);
    });

    test(`F58 axe: Google step for someone already signed in, ${lang}, ${theme} mode`, async ({ page, auth, seed, uniqueEmail }) => {
      const learner = await seed.verifiedLearner({ email: uniqueEmail('f58-replace') });
      await auth.loginAs(page, learner); // the sign-in screens are in Vietnamese here; the language is stored afterwards
      await page.evaluate((v) => {
        localStorage.setItem('e9x.theme', v.theme);
        localStorage.setItem('e9x.lang', v.lang);
      }, { theme, lang });
      await page.goto('/login/google?code=' + 'A'.repeat(43));
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(GOOGLE_STEP_HEADINGS.replace[lang]);
      await expect(page.locator('html')).toHaveAttribute('lang', lang);
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      await scan(page);
    });
  }
}

for (const lang of ['vi', 'en'] as const) {
  for (const theme of THEMES) {
    test(`F58 axe: signed-in notification screens in ${lang}, ${theme} mode`, async ({ page, browser, auth, seed, uniqueEmail }) => {
      const w = await reviewWorld(seed, uniqueEmail, 'f58n', browser);
      await w.requested();
      await auth.loginAs(page, w.reviewer);
      await page.evaluate((v) => {
        localStorage.setItem('e9x.theme', v.theme);
        localStorage.setItem('e9x.lang', v.lang);
      }, { theme, lang });
      const title = { vi: 'Có nội dung chờ duyệt', en: 'Content is waiting for review' }[lang];
      await expect(async () => {
        await page.goto('/notifications');
        await expect(page.getByRole('heading', { level: 2, name: title })).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 40_000 });
      await expect(page.locator('html')).toHaveAttribute('lang', lang);
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      await scan(page);
      await page.goto('/notifications/preferences');
      await expect(page.getByRole('heading', { level: 1, name: { vi: 'Tuỳ chọn thông báo', en: 'Notification preferences' }[lang] })).toBeVisible();
      await expect(page.getByRole('checkbox').first()).toBeVisible();
      await scan(page);
    });
  }
}

for (const lang of ['vi', 'en'] as const) {
  for (const theme of THEMES) {
    test(`F58 axe: signed-in admin and organisation screens in ${lang}, ${theme} mode`, async ({ page, auth, seed, uniqueEmail }) => {
      const orgName = `Trung tâm Axe ${Date.now().toString(36)}`;
      const admin = await seed.sysAdmin({ email: uniqueEmail('f58-sys') });
      const center = await seed.organization({ name: orgName, type: 'CENTER' });
      await seed.member({ orgId: center.orgId, userId: admin.userId, roles: [{ role: 'ORG_ADMIN' }] });
      const learner = await seed.verifiedLearner({ email: uniqueEmail('f58-member'), displayName: 'Học viên Axe' });
      await seed.member({ orgId: center.orgId, userId: learner.userId, roles: [{ role: 'LEARNER' }] });
      // P1.9: the audit log is scanned with records in it (a real review submit by an author)
      const author = await seed.verifiedLearner({ email: uniqueEmail('f58-author'), displayName: 'Tác giả Axe' });
      await seed.staffGrant({ userId: author.userId, role: 'AUTHOR', examCode: 'IELTS' });
      await seed.reviewFlow({ authorId: author.userId, outcome: 'submitted' });
      await auth.loginAs(page, admin);
      await page.evaluate((v) => {
        localStorage.setItem('e9x.theme', v.theme);
        localStorage.setItem('e9x.lang', v.lang);
      }, { theme, lang });
      const screens: Array<[string, string, RegExp]> = [
        ['admin home', '/admin', lang === 'vi' ? /^Chưa có mục quản trị nào$/ : /^No admin tools yet$/],
        ['organisations', '/admin/organizations', lang === 'vi' ? /^Tổ chức$/ : /^Organizations$/],
        ['one organisation', `/admin/organizations/${center.orgId}`, lang === 'vi' ? new RegExp(`^Thành viên của ${orgName}$`) : new RegExp(`^Members of ${orgName}$`)],
        ['staff invitations', '/admin/staff', lang === 'vi' ? /^Tài khoản nội bộ$/ : /^Internal accounts$/],
        ['audit log', '/admin/audit-log', lang === 'vi' ? /^Nhật ký hệ thống$/ : /^Audit log$/],
      ];
      for (const [name, path, heading] of screens) {
        await page.goto(path);
        await expect(page.getByRole('heading', { level: 1 }), name).toHaveText(heading);
        await expect(page.locator('html')).toHaveAttribute('lang', lang);
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        await expect(page.getByText(/Đang tải|Loading/)).toHaveCount(0);
        await scan(page);
      }
      // the organisation admin's own page, in its context (the switcher restores the remembered choice)
      await page.evaluate(([userId, orgId]) => localStorage.setItem('e9x.context', JSON.stringify({ userId, orgId })), [admin.userId, center.orgId]);
      await page.goto('/org/members');
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(lang === 'vi' ? new RegExp(`^Thành viên của ${orgName}$`) : new RegExp(`^Members of ${orgName}$`));
      await expect(page.getByRole('row', { name: /Học viên Axe/ })).toBeVisible();
      await scan(page);
    });
  }
}

// The 8 main screens, in their real state. The world is made once for the group (a scored result alone takes minutes); each test signs in as the
// person who sees that screen in a context of its own, stores the language and the theme after signing in (the sign-in screens are Vietnamese here), and scans.
test.describe('F58 main screens', () => {
  test.describe.configure({ mode: 'serial' });
  let world: MainScreensWorld;

  test.beforeAll(async ({ playwright }) => {
    test.setTimeout(1_200_000);
    const request = await playwright.request.newContext();
    try {
      world = await buildMainScreensWorld(request);
    } finally {
      await request.dispose();
    }
  });

  for (const lang of ['vi', 'en'] as const) {
    for (const theme of THEMES) {
      for (const screen of MAIN_SCREENS) {
        test(`F58 axe: main screen ${screen.name} in ${lang}, ${theme} mode`, async ({ browser }) => {
          const ctx = await browser.newContext({ baseURL: env.baseURL, ignoreHTTPSErrors: true });
          try {
            const page = await ctx.newPage();
            const who = screen.who(world);
            if (who) await loginAs(page, who);
            else await page.goto('/');
            await page.evaluate((v) => {
              localStorage.setItem('e9x.theme', v.theme);
              localStorage.setItem('e9x.lang', v.lang);
            }, { theme, lang });
            await page.goto(screen.path(world));
            await expect(page.getByRole('heading', { level: 1 })).toHaveText(screen.heading(world)[lang]);
            await expect(page.locator('html')).toHaveAttribute('lang', lang);
            await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
            await screen.ready(page, world, lang);
            await scan(page);
          } finally {
            await ctx.close();
          }
        });
      }
    }
  }
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
