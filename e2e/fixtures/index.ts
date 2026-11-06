import { test as base, expect } from '@playwright/test';
import { acquireGeminiLock, gemini as geminiHelper } from '../helpers/gemini';
import { mailpit } from '../helpers/mailpit';
import { telegram } from '../helpers/telegram';
import { loginAs, registerLearner } from './auth';
import { seed } from './seed';

/**
 * `import { test, expect } from '../../fixtures'` in every spec.
 *  - uniqueEmail(label): an address no other test uses (parallel tests share Mailpit; isolate by address).
 *  - mailpit / telegram: polling helpers for the fake containers. For telegram use telegram.uniqueChatId().
 *  - gemini: the fake Gemini has ONE global scenario. Asking for this fixture serialises you against every other test
 *    that asks for it (cross-process lock) and resets the fake (scenario 'ok', no recorded requests) before and after.
 *  - seed: test-data factory (POST /api/test/seed/*, with the shared secret header).
 *  - auth: loginAs(page, user) and registerLearner(page, user) drive the real sign-in / sign-up screens.
 */
export const test = base.extend<{
  uniqueEmail: (label?: string) => string;
  mailpit: typeof mailpit;
  telegram: typeof telegram;
  gemini: typeof geminiHelper;
  seed: typeof seed;
  auth: { loginAs: typeof loginAs; registerLearner: typeof registerLearner };
}>({
  uniqueEmail: async ({}, use) => { await use((label) => mailpit.uniqueEmail(label)); },
  mailpit: async ({}, use) => { await use(mailpit); },
  telegram: async ({}, use) => { await use(telegram); },
  seed: async ({}, use) => { await use(seed); },
  auth: async ({}, use) => { await use({ loginAs, registerLearner }); },
  gemini: async ({}, use) => {
    const release = await acquireGeminiLock();
    try {
      await geminiHelper.reset();
      await use(geminiHelper);
    } finally {
      try { await geminiHelper.reset(); } finally { await release(); }
    }
  },
});

export { expect };
