import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { publicKey } from '../../../app/stores/context';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import AcceptInvitationPage from './AcceptInvitationPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const TOKEN = 'A'.repeat(43);
const path = `/accept-invitation?token=${TOKEN}`;

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

const signedIn = () => useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
const show = () => renderAt(path, '/accept-invitation', <AcceptInvitationPage />);

describe('AcceptInvitationPage', () => {
  it('asks a visitor to sign in with the invited address and comes back here afterwards (no automatic accept)', () => {
    const calls = stubApi({});
    show();
    expect(screen.getByRole('heading', { level: 1, name: 'Lời mời tham gia English9x' })).toBeInTheDocument();
    const login = screen.getByRole('link', { name: 'Đăng nhập' });
    expect(login).toHaveAttribute('href', `/login?next=${encodeURIComponent(path)}`);
    expect(screen.getByRole('link', { name: 'Tạo tài khoản' })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('a signed-in person sees who they are signed in as and can accept', () => {
    signedIn();
    stubApi({});
    show();
    expect(screen.getByRole('button', { name: 'Chấp nhận lời mời' })).toBeEnabled();
  });

  it('accepts on click, reloads the roles and offers the way on', async () => {
    signedIn();
    const calls = stubApi({ [`POST /api/invitations/${TOKEN}/accept`]: () => json(200, { status: 'accepted', orgId: null }) });
    const { client } = show();
    // the roles of the person (loaded by OrgContextBootstrap in the app) must be asked for again
    client.setQueryData(publicKey('me', 'context', me().id), { organizations: [], grants: [] });
    await userEvent.click(screen.getByRole('button', { name: 'Chấp nhận lời mời' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Đã tham gia' })).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'POST')!.path).toBe(`/api/invitations/${TOKEN}/accept`);
    await waitFor(() => expect(client.getQueryState(publicKey('me', 'context', me().id))?.isInvalidated).toBe(true));
  });

  it('a different signed-in address is told so and can sign out to use the right one', async () => {
    signedIn();
    stubApi({ [`POST /api/invitations/${TOKEN}/accept`]: () => problem(403, 'invitation-email-mismatch') });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Chấp nhận lời mời' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Lời mời này gửi cho một địa chỉ email khác');
  });

  it('an unverified mailbox is told to verify first', async () => {
    signedIn();
    stubApi({ [`POST /api/invitations/${TOKEN}/accept`]: () => problem(403, 'email-not-verified') });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Chấp nhận lời mời' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Hãy xác thực email của bạn');
  });

  it('a used, expired, revoked or forged link is one message', async () => {
    signedIn();
    stubApi({ [`POST /api/invitations/${TOKEN}/accept`]: () => problem(404, 'invitation-invalid') });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Chấp nhận lời mời' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Lời mời không hợp lệ, đã được dùng, đã bị thu hồi hoặc đã hết hạn.');
  });

  it('a link without a token says so', () => {
    signedIn();
    renderAt('/accept-invitation', '/accept-invitation', <AcceptInvitationPage />);
    expect(screen.getByRole('alert')).toHaveTextContent('Lời mời không hợp lệ');
  });
});
