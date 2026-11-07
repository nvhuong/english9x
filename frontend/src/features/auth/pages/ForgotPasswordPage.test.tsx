import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { problem, renderAt, resetAuth, stubApi } from '../testUtils';
import ForgotPasswordPage from './ForgotPasswordPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  // created per call: openapi-fetch captures the global fetch when the client is made, and tests stub it later
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

describe('ForgotPasswordPage', () => {
  it('validates the address in the browser', async () => {
    const calls = stubApi({});
    renderAt('/forgot-password', '/forgot-password', <ForgotPasswordPage />);
    const u = userEvent.setup();
    await u.type(screen.getByLabelText(/Email/), 'not-an-email');
    await u.click(screen.getByRole('button', { name: 'Gửi liên kết' }));
    expect(await screen.findByText('Địa chỉ email chưa hợp lệ.')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('asks for the link and shows one neutral message, whichever address was typed', async () => {
    const calls = stubApi({ 'POST /api/auth/password-reset/request': () => new Response(null, { status: 202 }) });
    renderAt('/forgot-password', '/forgot-password', <ForgotPasswordPage />);
    const u = userEvent.setup();
    await u.type(screen.getByLabelText(/Email/), 'anyone@example.test');
    await u.click(screen.getByRole('button', { name: 'Gửi liên kết' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Nếu có tài khoản dùng email này');
    expect(calls[0]!.body).toEqual({ email: 'anyone@example.test' });
    expect(screen.getByRole('link', { name: 'Quay lại đăng nhập' })).toHaveAttribute('href', '/login');
  });

  it('reports the rate limit', async () => {
    stubApi({ 'POST /api/auth/password-reset/request': () => problem(429, 'too-many-attempts') });
    renderAt('/forgot-password', '/forgot-password', <ForgotPasswordPage />);
    const u = userEvent.setup();
    await u.type(screen.getByLabelText(/Email/), 'anyone@example.test');
    await u.click(screen.getByRole('button', { name: 'Gửi liên kết' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('quá nhiều lần');
  });
});
