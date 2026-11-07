import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import { InviteForm } from './InviteForm';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ORG = 'aaaaaaaa-0000-0000-0000-00000000000a';
const created = { id: '1', email: 'x@example.test', roles: [], status: 'PENDING', createdAt: '2026-11-07T00:00:00Z', expiresAt: '2026-11-14T00:00:00Z' };

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

const show = (scope: 'staff' | 'CENTER' | 'ENTERPRISE', orgId: string | null) => renderAt('/', '/', <InviteForm scope={scope} orgId={orgId} />);

describe('InviteForm', () => {
  it('offers only the roles the scope allows (an enterprise has no teacher)', () => {
    show('ENTERPRISE', ORG);
    expect(screen.getByRole('checkbox', { name: 'Học viên' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Quản trị tổ chức' })).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /Giáo viên/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /Biên soạn/ })).not.toBeInTheDocument();
  });

  it('a center offers teachers per exam', () => {
    show('CENTER', ORG);
    expect(screen.getByRole('checkbox', { name: 'Giáo viên · IELTS' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Giáo viên · TOEIC' })).toBeInTheDocument();
  });

  it('refuses an invalid address and an empty choice in the browser, next to the fields', async () => {
    const calls = stubApi({});
    show('staff', null);
    await userEvent.type(screen.getByLabelText(/^Email/), 'not-an-email');
    await userEvent.click(screen.getByRole('button', { name: 'Gửi lời mời' }));
    expect(await screen.findByText('Địa chỉ email chưa hợp lệ.')).toBeInTheDocument();
    expect(screen.getByText('Hãy chọn ít nhất một vai trò.')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('invites English9x staff through /api/admin/invitations with the chosen roles', async () => {
    const calls = stubApi({ 'POST /api/admin/invitations': () => json(201, created) });
    show('staff', null);
    await userEvent.type(screen.getByLabelText(/^Email/), 'new.author@example.test');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Biên soạn · IELTS' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gửi lời mời' }));
    expect(await screen.findByText('Đã gửi lời mời tới new.author@example.test.')).toBeInTheDocument();
    expect(calls[0]!.body).toEqual({ email: 'new.author@example.test', roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] });
  });

  it('invites into an organisation through /api/orgs/{id}/invitations', async () => {
    const calls = stubApi({ [`POST /api/orgs/${ORG}/invitations`]: () => json(201, created) });
    show('CENTER', ORG);
    await userEvent.type(screen.getByLabelText(/^Email/), 'teacher@example.test');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Giáo viên · TOEIC' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gửi lời mời' }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.body).toEqual({ email: 'teacher@example.test', roles: [{ role: 'TEACHER', examCode: 'TOEIC' }] });
  });

  it('shows a rule the server enforces as a message, and a rate limit as its own message', async () => {
    stubApi({ [`POST /api/orgs/${ORG}/invitations`]: () => problem(409, 'role-not-allowed') });
    show('CENTER', ORG);
    await userEvent.type(screen.getByLabelText(/^Email/), 'a@example.test');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Học viên' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gửi lời mời' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Tổ chức doanh nghiệp không có biên soạn, người duyệt hoặc giáo viên.');
    vi.unstubAllGlobals();
    stubApi({ [`POST /api/orgs/${ORG}/invitations`]: () => problem(429, 'too-many-invitations') });
    await userEvent.click(screen.getByRole('button', { name: 'Gửi lời mời' }));
    expect(await screen.findByText('Bạn đã gửi quá nhiều lời mời. Hãy thử lại sau.')).toBeInTheDocument();
  });

  it('places server field errors next to the email field', async () => {
    stubApi({ 'POST /api/admin/invitations': () => problem(400, 'validation-failed', { errors: [{ field: 'email', code: 'Email', message: 'must be a well-formed email address' }] }) });
    show('staff', null);
    await userEvent.type(screen.getByLabelText(/^Email/), 'a@b.c');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Biên soạn · IELTS' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gửi lời mời' }));
    expect(await screen.findByText('Địa chỉ email chưa hợp lệ.')).toBeInTheDocument();
  });
});
