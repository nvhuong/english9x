import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { AuthMenu } from './AuthMenu';
import { VerifyEmailBanner } from './VerifyEmailBanner';
import { refreshStorage } from './session';
import { me, resetAuth, stubApi } from './testUtils';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  // created per call: openapi-fetch captures the global fetch when the client is made, and tests stub it later
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

const wrap = (ui: React.ReactElement) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );

describe('AuthMenu', () => {
  it('offers sign in and sign up to a visitor', () => {
    wrap(<AuthMenu />);
    expect(screen.getByRole('link', { name: 'Đăng nhập' })).toHaveAttribute('href', '/login');
    expect(screen.getByRole('link', { name: 'Đăng ký' })).toHaveAttribute('href', '/register');
  });

  it('shows the person and signs them out', async () => {
    refreshStorage.set('refresh-1');
    useAuthStore.setState({ accessToken: 'a', status: 'authenticated', user: me() as never });
    const calls = stubApi({ 'POST /api/auth/logout': () => new Response(null, { status: 204 }) });
    wrap(<AuthMenu />);
    expect(screen.getByText('Đã đăng nhập: An')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Đăng xuất' }));
    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'));
    expect(refreshStorage.get()).toBeNull();
    expect(calls[0]!.path).toBe('/api/auth/logout');
  });

  it('shows nothing while a stored session is being restored', () => {
    useAuthStore.setState({ status: 'loading' });
    const { container } = wrap(<AuthMenu />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('VerifyEmailBanner', () => {
  it('is shown only to a signed-in person with an unverified email, and can resend', async () => {
    useAuthStore.setState({ accessToken: 'a', status: 'authenticated', user: me({ emailVerified: false }) as never });
    const calls = stubApi({ 'POST /api/auth/verify-email/resend': () => new Response(null, { status: 202 }) });
    wrap(<VerifyEmailBanner />);
    expect(screen.getByTestId('verify-email-banner')).toHaveTextContent('chưa được xác thực');
    await userEvent.click(screen.getByRole('button', { name: 'Gửi lại email xác thực' }));
    expect(await screen.findByText('Đã gửi email xác thực. Hãy kiểm tra hộp thư.')).toBeInTheDocument();
    expect(calls[0]!.body).toEqual({ email: 'an@example.test' });
  });

  it('is absent for visitors and verified users', () => {
    const { container, rerender } = wrap(<VerifyEmailBanner />);
    expect(container).toBeEmptyDOMElement();
    useAuthStore.setState({ status: 'authenticated', user: me({ emailVerified: true }) as never });
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <VerifyEmailBanner />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(screen.queryByTestId('verify-email-banner')).not.toBeInTheDocument();
  });
});
