import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import { MembersPanel } from './MembersPanel';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ORG = 'aaaaaaaa-0000-0000-0000-00000000000a';
const U1 = '11111111-1111-1111-1111-111111111111';
const U2 = '22222222-2222-2222-2222-222222222222';
const members = {
  members: [
    { userId: U1, email: 'admin@example.test', displayName: 'Lan Quản Trị', roles: [{ role: 'ORG_ADMIN', examCode: null }] },
    { userId: U2, email: 'gv@example.test', displayName: 'Minh Giáo Viên', roles: [{ role: 'TEACHER', examCode: 'IELTS' }] },
  ],
};

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

const show = (type: 'CENTER' | 'ENTERPRISE' = 'CENTER') => renderAt('/', '/', <MembersPanel orgId={ORG} type={type} />);

describe('MembersPanel', () => {
  it('lists the members of the organisation with their roles', async () => {
    stubApi({ [`GET /api/orgs/${ORG}/role-grants`]: () => json(200, members) });
    show();
    const row = (await screen.findByRole('row', { name: /Minh Giáo Viên/ }));
    expect(within(row).getByText('Giáo viên · IELTS')).toBeInTheDocument();
    expect(within(screen.getByRole('row', { name: /Lan Quản Trị/ })).getByText('Quản trị tổ chức')).toBeInTheDocument();
  });

  it('edits the roles of one member with PUT and shows the new roles', async () => {
    let current: { members: Array<Record<string, unknown>> } = members;
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/role-grants`]: () => json(200, current),
      [`PUT /api/orgs/${ORG}/role-grants`]: (c) => {
        const body = c.body as { userId: string; roles: Array<{ role: string; examCode: string | null }> };
        current = { members: current.members.map((m) => (m.userId === body.userId ? { ...m, roles: body.roles } : m)) };
        return json(200, current.members.find((m) => m.userId === body.userId));
      },
    });
    show();
    const row = await screen.findByRole('row', { name: /Minh Giáo Viên/ });
    await userEvent.click(within(row).getByRole('button', { name: /Sửa vai trò/ }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Giáo viên · IELTS' })); // off
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Giáo viên · TOEIC' })); // on
    await userEvent.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ userId: U2, roles: [{ role: 'TEACHER', examCode: 'TOEIC' }] });
    expect(await screen.findByText('Giáo viên · TOEIC')).toBeInTheDocument();
  });

  it('an enterprise offers no teacher role to edit', async () => {
    stubApi({ [`GET /api/orgs/${ORG}/role-grants`]: () => json(200, members) });
    show('ENTERPRISE');
    const row = await screen.findByRole('row', { name: /Lan Quản Trị/ });
    await userEvent.click(within(row).getByRole('button', { name: /Sửa vai trò/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('checkbox', { name: /Giáo viên/ })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('checkbox', { name: 'Quản trị tổ chức' })).toBeChecked();
  });

  it('shows "not found" for an organisation the server does not show to this person (404), without any member data', async () => {
    stubApi({ [`GET /api/orgs/${ORG}/role-grants`]: () => problem(404, 'not-found') });
    show();
    expect(await screen.findByRole('heading', { name: 'Không tìm thấy trang' })).toBeInTheDocument();
    expect(screen.queryByText(/example.test/)).not.toBeInTheDocument();
  });

  it('shows "no access" on 403', async () => {
    stubApi({ [`GET /api/orgs/${ORG}/role-grants`]: () => problem(403, 'forbidden') });
    show();
    expect(await screen.findByRole('heading', { name: 'Bạn chưa có quyền xem trang này' })).toBeInTheDocument();
  });

  it('explains the last-admin rule when the server refuses', async () => {
    stubApi({
      [`GET /api/orgs/${ORG}/role-grants`]: () => json(200, members),
      [`PUT /api/orgs/${ORG}/role-grants`]: () => problem(409, 'last-org-admin'),
    });
    show();
    const row = await screen.findByRole('row', { name: /Lan Quản Trị/ });
    await userEvent.click(within(row).getByRole('button', { name: /Sửa vai trò/ }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Quản trị tổ chức' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Tổ chức phải còn ít nhất một quản trị viên.');
  });
});
