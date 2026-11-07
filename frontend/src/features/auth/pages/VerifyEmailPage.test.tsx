import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../testUtils';
import { rememberReturn } from '../returnTo';
import VerifyEmailPage from './VerifyEmailPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  // created per call: openapi-fetch captures the global fetch when the client is made, and tests stub it later
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  localStorage.clear();
});
afterEach(() => vi.unstubAllGlobals());

describe('VerifyEmailPage', () => {
  it('spends the link exactly once (also under StrictMode) and confirms', async () => {
    const calls = stubApi({ 'POST /api/auth/verify-email': () => new Response(null, { status: 204 }) });
    renderAt('/verify-email?token=T0KEN', '/verify-email', <VerifyEmailPage />);
    expect(await screen.findByRole('heading', { name: 'Email đã được xác thực' })).toBeInTheDocument();
    expect(calls.filter((c) => c.path === '/api/auth/verify-email')).toHaveLength(1);
    expect(calls[0]!.body).toEqual({ token: 'T0KEN' });
    expect(screen.getByRole('link', { name: 'Đăng nhập' })).toHaveAttribute('href', '/login');
  });

  it('a signed-in person gets their banner cleared (profile reloaded) and a continue button', async () => {
    useAuthStore.setState({ accessToken: 'a', status: 'authenticated', user: me({ emailVerified: false }) as never });
    stubApi({
      'POST /api/auth/verify-email': () => new Response(null, { status: 204 }),
      'GET /api/auth/me': () => json(200, me({ emailVerified: true })),
    });
    renderAt('/verify-email?token=T0KEN', '/verify-email', <VerifyEmailPage />);
    expect(await screen.findByRole('link', { name: 'Tiếp tục' })).toHaveAttribute('href', '/');
    await waitFor(() => expect(useAuthStore.getState().user?.emailVerified).toBe(true));
  });

  describe('with a way back remembered (an invitation)', () => {
    const NEXT = '/accept-invitation?token=abc';
    beforeEach(() => {
      localStorage.clear();
      rememberReturn(NEXT);
    });

    it('a visitor signs in and lands there', async () => {
      stubApi({ 'POST /api/auth/verify-email': () => new Response(null, { status: 204 }) });
      renderAt('/verify-email?token=T0KEN', '/verify-email', <VerifyEmailPage />);
      expect(await screen.findByRole('link', { name: 'Đăng nhập' })).toHaveAttribute('href', `/login?next=${encodeURIComponent(NEXT)}`);
    });

    it('a signed-in person continues straight there', async () => {
      useAuthStore.setState({ accessToken: 'a', status: 'authenticated', user: me({ emailVerified: false }) as never });
      stubApi({
        'POST /api/auth/verify-email': () => new Response(null, { status: 204 }),
        'GET /api/auth/me': () => json(200, me({ emailVerified: true })),
      });
      renderAt('/verify-email?token=T0KEN', '/verify-email', <VerifyEmailPage />);
      expect(await screen.findByRole('link', { name: 'Tiếp tục' })).toHaveAttribute('href', NEXT);
    });
  });

  it('a bad or used link says so and offers a new one, without saying whether the address exists', async () => {
    const calls = stubApi({
      'POST /api/auth/verify-email': () => problem(400, 'invalid-or-expired-token'),
      'POST /api/auth/verify-email/resend': () => new Response(null, { status: 202 }),
    });
    renderAt('/verify-email?token=USED', '/verify-email', <VerifyEmailPage />);
    expect(await screen.findByRole('heading', { name: 'Không xác thực được' })).toBeInTheDocument();
    const u = userEvent.setup();
    await u.type(screen.getByLabelText(/Email/), 'an@example.test');
    await u.click(screen.getByRole('button', { name: 'Gửi liên kết mới' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Nếu tài khoản này còn chờ xác thực');
    expect(calls.find((c) => c.path === '/api/auth/verify-email/resend')!.body).toEqual({ email: 'an@example.test' });
  });

  it('without a token it goes straight to the "get a new link" form and calls nothing', async () => {
    const calls = stubApi({});
    renderAt('/verify-email', '/verify-email', <VerifyEmailPage />);
    expect(await screen.findByText(/thiếu thông tin/)).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});
