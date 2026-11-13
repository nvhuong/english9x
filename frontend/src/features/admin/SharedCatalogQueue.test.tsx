import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { useRoleStore, type RoleGrant } from '../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import SharedCatalogQueue from './SharedCatalogQueue';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const OWNER = 'aaaaaaaa-0000-0000-0000-00000000000a';
const C1 = 'c1111111-1111-1111-1111-111111111111';
const item = { courseId: C1, versionId: 'v1', code: 'TOEIC1', examCode: 'TOEIC', versionNo: 2, title: 'Ngữ pháp', authorDisplay: 'Cô Lan', ownerOrgId: OWNER, ownerName: 'Trung tâm A', sharedStatus: 'PENDING', updatedAt: '2026-11-01T00:00:00Z' };

const as = (grants: RoleGrant[]) => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants });
};
afterEach(() => {
  vi.unstubAllGlobals();
  useRoleStore.setState({ grants: null });
});

describe('SharedCatalogQueue', () => {
  it('an English9x reviewer sees the version and the centre, approves, and the course leaves the queue', async () => {
    as([{ role: 'REVIEWER', org: null, exam: 'TOEIC' }]);
    let pending = [item];
    const calls = stubApi({
      'GET /api/admin/shared-catalog': () => json(200, pending),
      [`POST /api/admin/shared-catalog/${C1}/approve`]: () => { pending = []; return json(200, { courseId: C1, sharedStatus: 'APPROVED' }); },
    });
    renderAt('/', '/', <SharedCatalogQueue />);
    const row = await screen.findByTestId('shared-row');
    expect(row).toHaveTextContent('Trung tâm A');
    expect(row).toHaveTextContent('Phiên bản 2');
    await userEvent.click(within(row).getByRole('button', { name: 'Duyệt khoá Ngữ pháp' }));
    expect(await screen.findByText('Đã duyệt khoá Ngữ pháp vào kho chung.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('shared-row')).toBeNull());
    expect(calls.some((c) => c.path === `/api/admin/shared-catalog/${C1}/approve`)).toBe(true);
  });

  it('rejecting asks first, then sends the decision', async () => {
    as([{ role: 'REVIEWER', org: null, exam: null }]);
    const calls = stubApi({
      'GET /api/admin/shared-catalog': () => json(200, [item]),
      [`POST /api/admin/shared-catalog/${C1}/reject`]: () => json(200, { courseId: C1, sharedStatus: 'REJECTED' }),
    });
    renderAt('/', '/', <SharedCatalogQueue />);
    await userEvent.click(await screen.findByRole('button', { name: 'Từ chối khoá Ngữ pháp' }));
    const dialog = await screen.findByRole('dialog');
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Từ chối khoá' }));
    expect(await screen.findByText(/Đã từ chối khoá Ngữ pháp/)).toBeInTheDocument();
    expect(calls.some((c) => c.path === `/api/admin/shared-catalog/${C1}/reject`)).toBe(true);
  });

  it("the owner's own member gets no approve button on that course", async () => {
    as([{ role: 'REVIEWER', org: null, exam: 'TOEIC' }, { role: 'AUTHOR', org: OWNER, exam: 'TOEIC' }]);
    stubApi({ 'GET /api/admin/shared-catalog': () => json(200, [item]) });
    renderAt('/', '/', <SharedCatalogQueue />);
    const row = await screen.findByTestId('shared-row');
    expect(within(row).queryByRole('button', { name: /Duyệt khoá/ })).toBeNull();
    expect(row).toHaveTextContent('bạn không tự duyệt được');
  });

  it('a reviewer of another exam, or a bare administrator, gets no approve button either', async () => {
    as([{ role: 'REVIEWER', org: null, exam: 'IELTS' }, { role: 'SYS_ADMIN', org: null, exam: null }]);
    stubApi({ 'GET /api/admin/shared-catalog': () => json(200, [item]) });
    renderAt('/', '/', <SharedCatalogQueue />);
    const row = await screen.findByTestId('shared-row');
    expect(within(row).queryByRole('button')).toBeNull();
  });

  it('shows no-access when the server refuses the queue', async () => {
    as([{ role: 'SYS_ADMIN', org: null, exam: null }]);
    stubApi({ 'GET /api/admin/shared-catalog': () => problem(403, 'forbidden') });
    renderAt('/', '/', <SharedCatalogQueue />);
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).not.toHaveTextContent('Kho khoá học chung'));
  });
});
