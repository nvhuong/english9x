import type { Page } from '@playwright/test';
import { notImplemented } from './notImplemented';

/** Switches the header context switcher to an organisation (or personal when null). Needs the org module and its UI. */
export async function switchContext(_page: Page, _orgName: string | null): Promise<void> {
  notImplemented('org.switchContext', 'P1.5 org context switcher');
}
