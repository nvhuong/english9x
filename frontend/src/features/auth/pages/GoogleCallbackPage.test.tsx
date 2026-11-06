import { screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { json, me, problem, renderAt, resetAuth, stubApi, tokens } from '../testUtils';
import { refreshStorage } from '../session';
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

  it('shows an error and a way back when the code is refused', async () => {
    stubApi({ 'POST /api/auth/oauth/exchange': () => problem(400, 'invalid-login-code') });
    renderAt('/login/google?code=USED', '/login/google', <GoogleCallbackPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('không hợp lệ');
    expect(screen.getByRole('link', { name: 'Quay lại đăng nhập' })).toHaveAttribute('href', '/login');
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  it('without a code it does not call the server', () => {
    const calls = stubApi({});
    renderAt('/login/google', '/login/google', <GoogleCallbackPage />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});
