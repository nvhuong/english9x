import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { json, me, problem, renderAt, resetAuth, stubApi, tokens } from '../testUtils';
import { refreshStorage } from '../session';
import { peekReturn, rememberReturn } from '../returnTo';
import GoogleCallbackPage from './GoogleCallbackPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  // created per call: openapi-fetch captures the global fetch when the client is made, and tests stub it later
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

describe('GoogleCallbackPage', () => {
  it('exchanges the one-time code exactly once (StrictMode) and signs the person in', async () => {
    const calls = stubApi({ 'POST /api/auth/oauth/exchange': () => json(200, tokens(1)), 'GET /api/auth/me': () => json(200, me()) });
    renderAt('/login/google?code=ONETIMECODE', '/login/google', <GoogleCallbackPage />);
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/));
    expect(calls.filter((c) => c.path === '/api/auth/oauth/exchange')).toHaveLength(1);
    expect(calls[0]!.body).toEqual({ code: 'ONETIMECODE' });
    expect(useAuthStore.getState().status).toBe('authenticated');
    expect(refreshStorage.get()).toBe('refresh-1');
  });

  it('returns to what was remembered before leaving for Google, once', async () => {
    localStorage.clear();
    rememberReturn('/accept-invitation?token=abc');
    stubApi({ 'POST /api/auth/oauth/exchange': () => json(200, tokens(1)), 'GET /api/auth/me': () => json(200, me()) });
    renderAt('/login/google?code=C', '/login/google', <GoogleCallbackPage />);
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/accept-invitation?token=abc'));
    expect(peekReturn()).toBeNull();
  });

  it('never follows an off-site value found in storage', async () => {
    localStorage.setItem('e9x.auth.returnTo', JSON.stringify({ path: '//evil.example/x', at: Date.now() }));
    stubApi({ 'POST /api/auth/oauth/exchange': () => json(200, tokens(1)), 'GET /api/auth/me': () => json(200, me()) });
    renderAt('/login/google?code=C', '/login/google', <GoogleCallbackPage />);
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/));
  });

  it('shows an error and a way back when the code is refused', async () => {
    stubApi({ 'POST /api/auth/oauth/exchange': () => problem(400, 'invalid-login-code') });
    renderAt('/login/google?code=USED', '/login/google', <GoogleCallbackPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('không hợp lệ');
    expect(screen.getByRole('link', { name: 'Quay lại đăng nhập' })).toHaveAttribute('href', '/login');
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  describe('consent', () => {
    const consentRoutes = () => ({
      'POST /api/auth/oauth/exchange': () => problem(403, 'consent-required'),
      'POST /api/auth/oauth/consent': () => json(200, tokens(1)),
      'GET /api/auth/me': () => json(200, me()),
    });

    it('a server that asks for consent gets a real checkbox, and no token before it is ticked', async () => {
      const calls = stubApi(consentRoutes());
      renderAt('/login/google?code=ONETIMECODE', '/login/google', <GoogleCallbackPage />);
      const box = await screen.findByRole('checkbox', { name: /Nghị định 13\/2023/ });
      expect(box).not.toBeChecked();
      expect(useAuthStore.getState().status).toBe('anonymous');

      await userEvent.click(screen.getByRole('button', { name: 'Đồng ý và tiếp tục' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Bạn cần đồng ý');
      expect(calls.filter((c) => c.path === '/api/auth/oauth/consent')).toHaveLength(0);

      await userEvent.click(box);
      await userEvent.click(screen.getByRole('button', { name: 'Đồng ý và tiếp tục' }));
      await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/));
      expect(calls.filter((c) => c.path === '/api/auth/oauth/consent').map((c) => c.body)).toEqual([{ code: 'ONETIMECODE', consent: true }]);
      expect(calls.filter((c) => c.path === '/api/auth/oauth/exchange')).toHaveLength(1);
      expect(useAuthStore.getState().status).toBe('authenticated');
    });

    it('offers a way out instead of consent', async () => {
      stubApi(consentRoutes());
      renderAt('/login/google?code=ONETIMECODE', '/login/google', <GoogleCallbackPage />);
      await screen.findByRole('checkbox');
      expect(screen.getByRole('link', { name: 'Quay lại đăng nhập' })).toHaveAttribute('href', '/login');
    });

    it('an expired code at the consent step says so', async () => {
      stubApi({ ...consentRoutes(), 'POST /api/auth/oauth/consent': () => problem(400, 'invalid-login-code') });
      renderAt('/login/google?code=ONETIMECODE', '/login/google', <GoogleCallbackPage />);
      await userEvent.click(await screen.findByRole('checkbox'));
      await userEvent.click(screen.getByRole('button', { name: 'Đồng ý và tiếp tục' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('không hợp lệ');
    });
  });

  describe('a session is already live in this browser', () => {
    const signedIn = () => {
      refreshStorage.set('refresh-old');
      useAuthStore.setState({ accessToken: 'access-old', user: me({ displayName: 'Nạn Nhân' }) as never, status: 'authenticated' });
    };

    it('does not touch it (no exchange at all) until the person says so', async () => {
      signedIn();
      const calls = stubApi({});
      renderAt('/login/google?code=SOMEONESCODE', '/login/google', <GoogleCallbackPage />);
      expect(await screen.findByText(/Nạn Nhân/)).toBeInTheDocument();
      expect(calls).toHaveLength(0);
      expect(refreshStorage.get()).toBe('refresh-old');
      expect(useAuthStore.getState().user?.displayName).toBe('Nạn Nhân');
    });

    it('keeping the current session spends nothing and goes home', async () => {
      signedIn();
      const calls = stubApi({});
      renderAt('/login/google?code=SOMEONESCODE', '/login/google', <GoogleCallbackPage />);
      await userEvent.click(await screen.findByRole('link', { name: /Giữ phiên hiện tại/ }));
      await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/));
      expect(calls).toHaveLength(0);
      expect(refreshStorage.get()).toBe('refresh-old');
    });

    it('choosing the new account replaces the session and revokes the old one', async () => {
      signedIn();
      const calls = stubApi({
        'POST /api/auth/oauth/exchange': () => json(200, tokens(1)),
        'GET /api/auth/me': () => json(200, me({ id: 'new', displayName: 'Người Mới' })),
        'POST /api/auth/logout': () => new Response(null, { status: 204 }),
      });
      renderAt('/login/google?code=MYCODE', '/login/google', <GoogleCallbackPage />);
      await userEvent.click(await screen.findByRole('button', { name: /Tiếp tục với tài khoản Google/ }));
      await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/));
      expect(refreshStorage.get()).toBe('refresh-1');
      expect(useAuthStore.getState().user?.displayName).toBe('Người Mới');
      await waitFor(() => expect(calls.filter((c) => c.path === '/api/auth/logout').map((c) => c.body)).toEqual([{ refreshToken: 'refresh-old' }]));
    });

    it('a code the server refuses leaves the live session alone', async () => {
      signedIn();
      stubApi({ 'POST /api/auth/oauth/exchange': () => problem(400, 'invalid-login-code') });
      renderAt('/login/google?code=SOMEONESCODE', '/login/google', <GoogleCallbackPage />);
      await userEvent.click(await screen.findByRole('button', { name: /Tiếp tục với tài khoản Google/ }));
      expect(await screen.findByRole('alert')).toHaveTextContent('không hợp lệ');
      expect(refreshStorage.get()).toBe('refresh-old');
      expect(useAuthStore.getState().status).toBe('authenticated');
    });

    it('a stored token with the session still being restored counts as live', async () => {
      refreshStorage.set('refresh-old');
      useAuthStore.setState({ status: 'loading' });
      const calls = stubApi({});
      renderAt('/login/google?code=SOMEONESCODE', '/login/google', <GoogleCallbackPage />);
      expect(await screen.findByRole('button', { name: /Tiếp tục với tài khoản Google/ })).toBeInTheDocument();
      expect(calls).toHaveLength(0);
    });
  });

  it('every text of the two new steps exists in both languages', async () => {
    const vi_ = (await import('../../../i18n/ns/auth.vi.json')).default as unknown as { google: Record<string, string> };
    const en_ = (await import('../../../i18n/ns/auth.en.json')).default as unknown as { google: Record<string, string> };
    for (const key of ['consentTitle', 'consentBody', 'consentSubmit', 'replaceTitle', 'replaceBody', 'replaceContinue', 'replaceStay']) {
      expect(vi_.google[key], `vi ${key}`).toBeTruthy();
      expect(en_.google[key], `en ${key}`).toBeTruthy();
    }
  });

  it('without a code it does not call the server', () => {
    const calls = stubApi({});
    renderAt('/login/google', '/login/google', <GoogleCallbackPage />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});
