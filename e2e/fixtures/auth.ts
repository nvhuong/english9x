import type { Page } from '@playwright/test';
import { notImplemented } from './notImplemented';

/** Signs `page` in through the real login screen. Needs the identity module. */
export async function loginAs(_page: Page, _user: { email: string; password: string }): Promise<void> {
  notImplemented('auth.loginAs', 'P1.4 identity/login');
}

/** Registers a learner through the real form and returns once the verification email is out. Needs the identity module. */
export async function registerLearner(_email: string, _password: string): Promise<void> {
  notImplemented('auth.registerLearner', 'P1.4 identity/registration');
}
