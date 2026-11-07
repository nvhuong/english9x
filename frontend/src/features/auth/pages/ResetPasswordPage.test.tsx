import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { problem, renderAt, resetAuth, stubApi } from '../testUtils';
import ResetPasswordPage from './ResetPasswordPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  // created per call: openapi-fetch captures the global fetch when the client is made, and tests stub it later
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

async function fill(a = 'New-password-1', b = 'New-password-1') {
  const u = userEvent.setup();
  await u.type(screen.getByLabelText(/^Mật khẩu mới/), a);
  await u.type(screen.getByLabelText(/^Nhập lại mật khẩu mới/), b);
  await u.click(screen.getByRole('button', { name: 'Đặt mật khẩu' }));
}

describe('ResetPasswordPage', () => {
  it('without a token it offers a new request instead of a form', () => {
    stubApi({});
    renderAt('/reset-password', '/reset-password', <ResetPasswordPage />);
    expect(screen.getByRole('link', { name: 'Yêu cầu liên kết mới' })).toHaveAttribute('href', '/forgot-password');
    expect(screen.queryByLabelText(/^Mật khẩu mới/)).not.toBeInTheDocument();
  });

  it('refuses mismatching and weak passwords before calling the server', async () => {
    const calls = stubApi({});
    renderAt('/reset-password?token=T', '/reset-password', <ResetPasswordPage />);
    await fill('New-password-1', 'Other-password-2');
    expect(await screen.findByText('Hai mật khẩu chưa giống nhau.')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('sets the new password with the emailed token and points to sign in', async () => {
    const calls = stubApi({ 'POST /api/auth/password-reset/confirm': () => new Response(null, { status: 204 }) });
    renderAt('/reset-password?token=T0KEN', '/reset-password', <ResetPasswordPage />);
    await fill();
    expect(await screen.findByRole('heading', { name: 'Đã đổi mật khẩu' })).toBeInTheDocument();
    expect(calls[0]!.body).toEqual({ token: 'T0KEN', newPassword: 'New-password-1' });
    expect(screen.getByRole('link', { name: 'Đăng nhập' })).toHaveAttribute('href', '/login');
  });

  it('an expired or used link is explained and a new one can be requested', async () => {
    stubApi({ 'POST /api/auth/password-reset/confirm': () => problem(400, 'invalid-or-expired-token') });
    renderAt('/reset-password?token=OLD', '/reset-password', <ResetPasswordPage />);
    await fill();
    expect(await screen.findByRole('alert')).toHaveTextContent('Liên kết không hợp lệ, đã được dùng hoặc đã hết hạn.');
    expect(screen.getByRole('link', { name: 'Yêu cầu liên kết mới' })).toBeInTheDocument();
  });
});
