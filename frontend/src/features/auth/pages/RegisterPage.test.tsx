import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetI18n } from '../../../i18n';
import { json, problem, renderAt, resetAuth, stubApi } from '../testUtils';
import RegisterPage from './RegisterPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  // created per call: openapi-fetch captures the global fetch when the client is made, and tests stub it later
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

const base = { 'GET /api/auth/config': () => json(200, { googleEnabled: false }) };

async function fillForm(opts: { password?: string; consent?: boolean } = {}) {
  const u = userEvent.setup();
  await u.type(screen.getByLabelText(/Tên hiển thị/), 'Nguyễn An');
  await u.type(screen.getByLabelText(/Email/), 'an@example.test');
  await u.type(screen.getByLabelText(/Mật khẩu/), opts.password ?? 'Correct-horse-1');
  if (opts.consent !== false) await u.click(screen.getByRole('checkbox'));
  return u;
}

describe('RegisterPage', () => {
  it('will not submit without consent (Decree 13/2023) and says why', async () => {
    const calls = stubApi(base);
    renderAt('/register', '/register', <RegisterPage />);
    const u = await fillForm({ consent: false });
    await u.click(screen.getByRole('button', { name: 'Đăng ký' }));
    expect(await screen.findByText('Bạn cần đồng ý để tạo tài khoản.')).toBeInTheDocument();
    expect(calls.some((c) => c.path === '/api/auth/register')).toBe(false);
  });

  it('checks the password rules in the browser, including the 72 byte limit of bcrypt', async () => {
    const calls = stubApi(base);
    renderAt('/register', '/register', <RegisterPage />);
    const u = await fillForm({ password: 'short' });
    await u.click(screen.getByRole('button', { name: 'Đăng ký' }));
    expect(await screen.findByText('Mật khẩu cần ít nhất 8 ký tự.')).toBeInTheDocument();
    await u.clear(screen.getByLabelText(/Mật khẩu/));
    await u.type(screen.getByLabelText(/Mật khẩu/), 'é'.repeat(40)); // 40 characters, 80 bytes
    await u.click(screen.getByRole('button', { name: 'Đăng ký' }));
    expect(await screen.findByText(/quá dài/)).toBeInTheDocument();
    expect(calls.some((c) => c.path === '/api/auth/register')).toBe(false);
  });

  it('registers with consent:true and tells the person to check their email (same wording whoever owns the address)', async () => {
    const calls = stubApi({ ...base, 'POST /api/auth/register': () => json(201, { status: 'pending_verification' }) });
    renderAt('/register', '/register', <RegisterPage />);
    const u = await fillForm();
    await u.click(screen.getByRole('button', { name: 'Đăng ký' }));
    expect(await screen.findByRole('heading', { name: 'Hãy kiểm tra email của bạn' })).toBeInTheDocument();
    expect(screen.getByText(/an@example.test/)).toBeInTheDocument();
    expect(calls.find((c) => c.path === '/api/auth/register')!.body).toEqual({
      email: 'an@example.test', displayName: 'Nguyễn An', password: 'Correct-horse-1', consent: true, locale: 'vi',
    });
  });

  it('offers to resend the verification email', async () => {
    const calls = stubApi({
      ...base,
      'POST /api/auth/register': () => json(201, { status: 'pending_verification' }),
      'POST /api/auth/verify-email/resend': () => new Response(null, { status: 202 }),
    });
    renderAt('/register', '/register', <RegisterPage />);
    const u = await fillForm();
    await u.click(screen.getByRole('button', { name: 'Đăng ký' }));
    await u.click(await screen.findByRole('button', { name: 'Gửi lại email xác thực' }));
    expect(await screen.findByRole('status')).toHaveTextContent('một email mới đã được gửi');
    expect(calls.find((c) => c.path === '/api/auth/verify-email/resend')!.body).toEqual({ email: 'an@example.test' });
  });

  it('shows server errors[] next to their fields', async () => {
    stubApi({
      ...base,
      'POST /api/auth/register': () =>
        problem(400, 'validation-failed', {
          errors: [{ field: 'password', row: null, code: 'Utf8MaxBytes', message: 'x' }, { field: 'consent', row: null, code: 'AssertTrue', message: 'y' }],
        }),
    });
    renderAt('/register', '/register', <RegisterPage />);
    const u = await fillForm();
    await u.click(screen.getByRole('button', { name: 'Đăng ký' }));
    const pw = screen.getByLabelText(/Mật khẩu/);
    await waitFor(() => expect(pw).toHaveAttribute('aria-invalid', 'true'));
    expect(document.getElementById(pw.id + '-error')).toHaveTextContent('Mật khẩu quá dài');
  });

  it('is available in English and sends the English locale for the mail', async () => {
    await resetI18n('en');
    const calls = stubApi({ ...base, 'POST /api/auth/register': () => json(201, { status: 'pending_verification' }) });
    renderAt('/register', '/register', <RegisterPage />);
    const u = userEvent.setup();
    await u.type(screen.getByLabelText(/Display name/), 'An');
    await u.type(screen.getByLabelText(/Email/), 'an@example.test');
    await u.type(screen.getByLabelText(/Password/), 'Correct-horse-1');
    await u.click(screen.getByRole('checkbox'));
    await u.click(screen.getByRole('button', { name: 'Sign up' }));
    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument();
    expect((calls.find((c) => c.path === '/api/auth/register')!.body as { locale: string }).locale).toBe('en');
  });
});
