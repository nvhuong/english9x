import type { TestRunnerConfig } from '@storybook/test-runner';
import { checkA11y, injectAxe } from 'axe-playwright';

/**
 * Every story is scanned with axe twice: light and dark theme (colour-contrast is theme dependent).
 * Serious and critical violations fail the run; minor/moderate are reported by axe in the story panel only.
 */
const config: TestRunnerConfig = {
  async preVisit(page) {
    await injectAxe(page);
  },
  async postVisit(page, context) {
    for (const theme of ['light', 'dark'] as const) {
      await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme);
      await page.waitForTimeout(50);
      await checkA11y(
        page,
        '#storybook-root',
        {
          includedImpacts: ['serious', 'critical'],
          detailedReport: true,
          detailedReportOptions: { html: true },
          axeOptions: { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } },
        },
        false,
        'v2',
      );
    }
    void context;
  },
};

export default config;
