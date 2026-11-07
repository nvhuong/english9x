import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import { InvitationList } from './InvitationList';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

const row = (status: string, email: string) => ({
  id: `00000000-0000-0000-0000-00000000000${status.length}`, email, roles: [{ role: 'LEARNER', examCode: null }], status,
  createdAt: '2026-11-07T00:00:00Z', expiresAt: '2026-11-14T00:00:00Z',
});

describe('InvitationList', () => {
  it('revoking an invitation that has just expired shows the list as it is now', async () => {
    let status = 'PENDING';
    const calls = stubApi({
      'GET /api/admin/invitations': () => json(200, [row(status, 'gone@x.test')]),
      [`DELETE /api/admin/invitations/${row('PENDING', '').id}`]: () => {
        status = 'EXPIRED';
        return json(404, { type: 'urn:e9x:problem:not-found', title: 'Not found', status: 404, errors: [] });
      },
    });
    renderAt('/', '/', <InvitationList orgId={null} />);
    await userEvent.click(await screen.findByRole('button', { name: /gone@x\.test/ }));
    await waitFor(() => expect(screen.getByText('gone@x.test').closest('tr')!).toHaveTextContent('Hết hạn'));
    expect(calls.filter((c) => c.method === 'GET').length).toBeGreaterThanOrEqual(2);
  });

  it('an invitation voided by the inviter losing authority is not shown as pending and has no revoke button', async () => {
    stubApi({ 'GET /api/admin/invitations': () => json(200, [row('VOIDED', 'void@x.test'), row('PENDING', 'live@x.test')]) });
    renderAt('/', '/', <InvitationList orgId={null} />);
    const voided = (await screen.findByText('void@x.test')).closest('tr')!;
    expect(voided).toHaveTextContent('Mất hiệu lực');
    expect(voided).not.toHaveTextContent('Đang chờ');
    expect(voided.querySelector('button')).toBeNull();
    expect(screen.getByText('live@x.test').closest('tr')!.querySelector('button')).not.toBeNull();
  });
});
