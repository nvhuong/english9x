import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { resetI18n } from '../../../i18n';
import { json, me, problem, renderAt, resetAuth, stubApi, tokens } from '../testUtils';
import { refreshStorage } from '../session';
import { peekReturn, rememberReturn } from '../returnTo';
import LoginPage from './LoginPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  // created per call: openapi-fetch captures the global fetch when the client is made, and tests stub it later
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

const config = (googleEnabled: boolean) => ({ 'GET /api/auth/config': () => json(200, { googleEnabled }) });

async function fill(email = 'an@example.test', password = 'Correct-horse-1') {
  const u = userEvent.setup();
  await u.type(screen.getByLabelText(/Email/), email);
  await u.type(screen.getByLabelText(/Mật khẩu/), password);
  return u;
}

describe('LoginPage', () => {
  it('shows field errors in Vietnamese and sends nothing when the form is empty', async () => {
    const calls = stubApi(config(false));
    renderAt('/login', '/login', <LoginPage />);
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    expect(await screen.findAllByRole('alert')).toHaveLength(2);
    const emailInput = screen.getByLabelText(/Email/);
    expect(emailInput).toHaveAttribute('aria-invalid', 'true');
    expect(document.getElementById(emailInput.id + '-error')).toHaveTextContent('Vui lòng nhập thông tin này.');
    expect(calls.some((c) => c.path === '/api/auth/login')).toBe(false);
  });

  it('signs in: keeps the access token in memory, the refresh token in storage, then goes to ?next=', async () => {
    const calls = stubApi({
      ...config(false),
      'POST /api/auth/login': () => json(200, tokens(1)),
      'GET /api/auth/me': () => json(200, me()),
    });
    renderAt('/login?next=/preferences', '/login', <LoginPage />);
    const u = await fill();
    await u.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/preferences'));
    expect(calls.find((c) => c.path === '/api/auth/login')!.body).toEqual({ email: 'an@example.test', password: 'Correct-horse-1' });
    expect(useAuthStore.getState()).toMatchObject({ accessToken: 'access-1', status: 'authenticated' });
    expect(refreshStorage.get()).toBe('refresh-1');
    expect(JSON.stringify(Object.entries(localStorage))).not.toContain('access-1');
  });

  it('never follows an off-site ?next=', async () => {
    stubApi({ ...config(false), 'POST /api/auth/login': () => json(200, tokens(1)), 'GET /api/auth/me': () => json(200, me()) });
    renderAt('/login?next=//evil.example/x', '/login', <LoginPage />);
    const u = await fill();
    await u.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/));
  });

  it('says the same generic thing for any wrong credentials', async () => {
    stubApi({ ...config(false), 'POST /api/auth/login': () => problem(401, 'invalid-credentials') });
    renderAt('/login', '/login', <LoginPage />);
    const u = await fill();
    await u.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Email hoặc mật khẩu chưa đúng.');
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(refreshStorage.get()).toBeNull();
  });

  it('explains the rate limit', async () => {
    stubApi({ ...config(false), 'POST /api/auth/login': () => problem(429, 'too-many-attempts') });
    renderAt('/login', '/login', <LoginPage />);
    const u = await fill();
    await u.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('quá nhiều lần');
  });

  it('puts server errors[] next to the fields they name', async () => {
    stubApi({
      ...config(false),
      'POST /api/auth/login': () => problem(400, 'validation-failed', { errors: [{ field: 'email', row: null, code: 'Email', message: 'must be a well-formed email address' }] }),
    });
    renderAt('/login', '/login', <LoginPage />);
    const u = await fill('looks@ok.test');
    await u.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    const emailInput = screen.getByLabelText(/Email/);
    await waitFor(() => expect(emailInput).toHaveAttribute('aria-invalid', 'true'));
    expect(document.getElementById(emailInput.id + '-error')).toHaveTextContent('Địa chỉ email chưa hợp lệ.');
  });

  it('shows the Google button only when the server has Google configured', async () => {
    stubApi(config(true));
    const { unmount } = renderAt('/login', '/login', <LoginPage />);
    const link = await screen.findByRole('link', { name: 'Đăng nhập bằng Google' });
    expect(link).toHaveAttribute('href', '/api/auth/oauth2/google');
    unmount();

    stubApi(config(false));
    renderAt('/login', '/login', <LoginPage />);
    await screen.findByRole('button', { name: 'Đăng nhập' });
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Đăng nhập bằng Google' })).not.toBeInTheDocument());
  });

  it('explains a failed Google sign-in from ?error=', async () => {
    stubApi(config(true));
    renderAt('/login?error=google_email_unverified', '/login', <LoginPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Google chưa xác nhận');
  });

  it('is available in English', async () => {
    await resetI18n('en');
    stubApi(config(false));
    renderAt('/login', '/login', <LoginPage />);
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Forgot your password?' })).toBeInTheDocument();
  });

  describe('the way back', () => {
    const NEXT = '/accept-invitation?token=abc';
    beforeEach(() => localStorage.clear());
    const routes = { ...{ 'GET /api/auth/config': () => json(200, { googleEnabled: true }) }, 'POST /api/auth/login': () => json(200, tokens(1)), 'GET /api/auth/me': () => json(200, me()) };

    async function signIn() {
      const u = userEvent.setup();
      await u.type(screen.getByLabelText(/Email/), 'an@example.test');
      await u.type(screen.getByLabelText(/Mật khẩu/), 'Correct-horse-1');
      await u.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    }

    it('a plain sign-in returns to what was remembered, once', async () => {
      stubApi(routes);
      rememberReturn(NEXT);
      renderAt('/login', '/login', <LoginPage />);
      await signIn();
      await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(NEXT));
      expect(peekReturn()).toBeNull();
    });

    it('?next= wins and the remembered one is spent with it', async () => {
      stubApi(routes);
      rememberReturn('/somewhere-else');
      renderAt(`/login?next=${encodeURIComponent(NEXT)}`, '/login', <LoginPage />);
      await signIn();
      await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(NEXT));
      expect(peekReturn()).toBeNull();
    });

    it('the Google button remembers ?next= before leaving for Google', async () => {
      stubApi(routes);
      renderAt(`/login?next=${encodeURIComponent(NEXT)}`, '/login', <LoginPage />);
      const link = await screen.findByRole('link', { name: 'Đăng nhập bằng Google' });
      link.addEventListener('click', (e) => e.preventDefault()); // jsdom cannot navigate
      await userEvent.click(link);
      expect(peekReturn()).toBe(NEXT);
    });
  });
});
