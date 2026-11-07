import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import AuditLogPage from './AuditLogPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const entry = (n: number, over: Record<string, unknown> = {}) => ({
  id: `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`, seq: n, at: '2026-11-07T03:00:00Z', actorKind: 'USER', actorId: 'a1',
  actorName: `Actor ${n}`, actorEmail: `actor${n}@example.test`, orgId: null, orgName: null, action: 'org.create', targetType: 'organization',
  targetId: `org-${n}`, detail: { name: `Org ${n}` }, ...over,
});
const page = (items: unknown[], over: Record<string, unknown> = {}) => ({ items, page: 0, size: 50, hasMore: false, ...over });
const actions = () => json(200, ['invitation.send', 'org.create', 'review.approve']);

beforeEach(() => {
  resetAuth();
  useContextStore.setState({ orgId: null });
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

function render() {
  return renderAt('/admin/audit-log', '/admin/audit-log', <AuditLogPage />);
}

describe('AuditLogPage', () => {
  it('shows a loading text, then the records', async () => {
    stubApi({ 'GET /api/admin/audit-log': () => json(200, page([entry(1), entry(2)])), 'GET /api/admin/audit-log/actions': actions });
    render();
    expect(screen.getByRole('heading', { level: 1, name: 'Nhật ký hệ thống' })).toBeInTheDocument();
    expect(screen.getByText('Đang tải nhật ký')).toBeInTheDocument();
    const rows = await screen.findAllByTestId('audit-row');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByText('Actor 1')).toBeInTheDocument();
  });

  it('explains an empty log, and an empty result of a filter with a way back', async () => {
    const calls = stubApi({
      'GET /api/admin/audit-log': () => json(200, page([])),
      'GET /api/admin/audit-log/actions': actions,
    });
    render();
    expect(await screen.findByRole('heading', { name: 'Nhật ký chưa có bản ghi nào' })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Loại đối tượng'), 'user');
    await userEvent.click(screen.getByRole('button', { name: 'Lọc' }));
    expect(await screen.findByRole('heading', { name: 'Không có bản ghi nào khớp' })).toBeInTheDocument();
    expect(calls.at(-1)!.search).toContain('targetType=user');
    await userEvent.click(within(screen.getByRole('heading', { name: 'Không có bản ghi nào khớp' }).closest('section')!).getByRole('button', { name: 'Xoá bộ lọc' }));
    expect(await screen.findByRole('heading', { name: 'Nhật ký chưa có bản ghi nào' })).toBeInTheDocument();
    expect(screen.getByLabelText('Loại đối tượng')).toHaveValue('');
  });

  it('shows a failure with a retry that asks again', async () => {
    let fail = true;
    stubApi({
      'GET /api/admin/audit-log': () => (fail ? problem(500, 'internal') : json(200, page([entry(1)]))),
      'GET /api/admin/audit-log/actions': actions,
    });
    render();
    expect(await screen.findByText('Không tải được nhật ký. Hãy thử lại.')).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByTestId('audit-row')).toBeInTheDocument();
    expect(screen.queryByText('Không tải được nhật ký. Hãy thử lại.')).toBeNull();
  });

  it('a person without the right sees the no-access page, not an empty log', async () => {
    stubApi({ 'GET /api/admin/audit-log': () => problem(403, 'forbidden'), 'GET /api/admin/audit-log/actions': () => problem(403, 'forbidden') });
    render();
    expect(await screen.findByRole('heading', { name: /chưa có quyền/i })).toBeInTheDocument();
  });

  it('sends the filters as the server expects them: action, actor, target and the day bounds', async () => {
    const calls = stubApi({ 'GET /api/admin/audit-log': () => json(200, page([entry(1)])), 'GET /api/admin/audit-log/actions': actions });
    render();
    await screen.findByTestId('audit-row');
    await userEvent.click(screen.getByRole('combobox', { name: 'Hành động' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Gửi lời mời' }));
    await userEvent.type(screen.getByLabelText(/^Người thực hiện/), 'boss@example.test');
    await userEvent.type(screen.getByLabelText('Mã đối tượng'), 'inv-7');
    await userEvent.type(screen.getByLabelText('Từ ngày'), '2026-11-01');
    await userEvent.type(screen.getByLabelText('Đến ngày'), '2026-11-07');
    await userEvent.click(screen.getByRole('button', { name: 'Lọc' }));
    await waitFor(() => expect(new URLSearchParams(calls.at(-1)!.search).get('action')).toBe('invitation.send'));
    const q = new URLSearchParams(calls.at(-1)!.search);
    expect(q.get('actor')).toBe('boss@example.test');
    expect(q.get('targetId')).toBe('inv-7');
    expect(q.get('from')).toBe(new Date(2026, 10, 1).toISOString());
    expect(q.get('to')).toBe(new Date(2026, 10, 8).toISOString());
    expect(q.get('page')).toBe('0');
  });

  it('pages forward and back, and a new filter starts again at the first page', async () => {
    const calls = stubApi({
      'GET /api/admin/audit-log': (c) => {
        const p = Number(new URLSearchParams(c.search).get('page') ?? '0');
        return json(200, page([entry(p * 2 + 1), entry(p * 2 + 2)], { page: p, hasMore: p < 1 }));
      },
      'GET /api/admin/audit-log/actions': actions,
    });
    render();
    await screen.findByText('Actor 1');
    expect(screen.getByRole('button', { name: 'Trang trước' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Trang sau' }));
    expect(await screen.findByText('Actor 3')).toBeInTheDocument();
    expect(screen.getByText('Trang 2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Trang sau' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Mã đối tượng'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Lọc' }));
    await waitFor(() => expect(new URLSearchParams(calls.at(-1)!.search).get('page')).toBe('0'));
    await userEvent.click(await screen.findByRole('button', { name: 'Trang sau' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Trang trước' }));
    expect(await screen.findByText('Actor 1')).toBeInTheDocument();
  });

  it('keys its queries by the context: switching context drops what was loaded', async () => {
    stubApi({ 'GET /api/admin/audit-log': () => json(200, page([entry(1)])), 'GET /api/admin/audit-log/actions': actions });
    const { client } = render();
    await screen.findByTestId('audit-row');
    const keys = client.getQueryCache().getAll().map((q) => q.queryKey);
    expect(keys.length).toBeGreaterThan(0);
    for (const k of keys) {
      expect(k.slice(0, 2)).toEqual(['ctx', 'personal']);
    }
  });
});
