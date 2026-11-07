import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import OrganizationsPage from './OrganizationsPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const org = (name: string, type: string) => ({ id: `${name.length}`.padStart(8, '0') + '-0000-0000-0000-000000000000', name, type, createdAt: '2026-11-07T01:00:00Z' });

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

describe('OrganizationsPage', () => {
  it('lists every organisation and links to each one', async () => {
    stubApi({ 'GET /api/admin/organizations': () => json(200, [org('Trung tâm Ánh Dương', 'CENTER'), org('Công ty Bình Minh', 'ENTERPRISE')]) });
    renderAt('/admin/organizations', '/admin/organizations', <OrganizationsPage />);
    expect(await screen.findByRole('link', { name: 'Trung tâm Ánh Dương' })).toHaveAttribute('href', expect.stringMatching(/^\/admin\/organizations\/[0-9a-f-]{36}$/));
    const table = screen.getByRole('table', { name: 'Các tổ chức' });
    expect(within(table).getByText('Doanh nghiệp')).toBeInTheDocument();
    expect(within(table).getByText('Trung tâm')).toBeInTheDocument();
  });

  it('creates an organisation: validates the name, posts name and type, then lists it', async () => {
    const list: unknown[] = [];
    const calls = stubApi({
      'GET /api/admin/organizations': () => json(200, list),
      'POST /api/admin/organizations': (c) => {
        const b = c.body as { name: string; type: string };
        const o = org(b.name, b.type);
        list.push(o);
        return json(201, o);
      },
    });
    renderAt('/admin/organizations', '/admin/organizations', <OrganizationsPage />);
    await screen.findByText('Chưa có tổ chức nào.');
    await userEvent.click(screen.getByRole('button', { name: 'Tạo tổ chức' }));
    expect(await screen.findByText('Vui lòng nhập tên tổ chức.')).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    await userEvent.type(screen.getByLabelText(/^Tên tổ chức/), 'Công ty Bình Minh');
    await userEvent.click(screen.getByRole('combobox', { name: 'Loại tổ chức' }));
    await userEvent.click(screen.getByRole('option', { name: 'Doanh nghiệp' }));
    await userEvent.click(screen.getByRole('button', { name: 'Tạo tổ chức' }));
    expect(await screen.findByRole('link', { name: 'Công ty Bình Minh' })).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ name: 'Công ty Bình Minh', type: 'ENTERPRISE' });
  });

  it('shows a server refusal without leaking anything', async () => {
    stubApi({ 'GET /api/admin/organizations': () => problem(403, 'forbidden') });
    renderAt('/admin/organizations', '/admin/organizations', <OrganizationsPage />);
    expect(await screen.findByRole('heading', { name: 'Bạn chưa có quyền xem trang này' })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Chưa có tổ chức nào.')).not.toBeInTheDocument());
  });
});
