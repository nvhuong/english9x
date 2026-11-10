import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import LicensesPage from './LicensesPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ORG = 'aaaaaaaa-0000-0000-0000-00000000000a';
const LIC = '11111111-1111-1111-1111-111111111111';
const C1 = 'c1111111-1111-1111-1111-111111111111';
const U1 = '21111111-1111-1111-1111-111111111111';
const U2 = '22222222-2222-2222-2222-222222222222';

const license = (holders: unknown[], seats = 2) => ({
  id: LIC, orgId: ORG, seats, seatsUsed: holders.length, months: 6, priceVnd: 1000, activation: 'CONTRACT', contractNo: 'HD-1', status: 'ACTIVE',
  validFrom: '2026-11-01T00:00:00Z', validUntil: '2027-05-01T00:00:00Z', createdAt: '2026-11-01T00:00:00Z', courses: [{ id: C1, code: 'IELTS1', title: 'Reading' }], orderCode: null, holders,
});
const holder = { userId: U1, displayName: 'Lan Học Viên', email: 'lan@example.test', courseIds: [C1], assignedAt: '2026-11-02T00:00:00Z' };
const members = { members: [
  { userId: U1, email: 'lan@example.test', displayName: 'Lan Học Viên', roles: [] },
  { userId: U2, email: 'minh@example.test', displayName: 'Minh Học Viên', roles: [] },
] };

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: ORG });
});
afterEach(() => vi.unstubAllGlobals());

describe('LicensesPage', () => {
  it('shows the seats used and who holds them, and takes a seat back after a confirmation', async () => {
    let holders: unknown[] = [holder];
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/licenses`]: () => json(200, [license(holders)]),
      [`GET /api/orgs/${ORG}/role-grants`]: () => json(200, members),
      [`DELETE /api/orgs/${ORG}/licenses/${LIC}/seats/${U1}`]: () => { holders = []; return new Response(null, { status: 204 }); },
    });
    renderAt('/', '/', <LicensesPage />);
    expect(await screen.findByTestId('license-seats')).toHaveTextContent('Đã dùng 1/2 suất');
    const row = screen.getByRole('row', { name: /Lan Học Viên/ });
    expect(within(row).getByText('IELTS1')).toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: 'Thu hồi suất của Lan Học Viên' }));
    const dialog = await screen.findByRole('dialog');
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Thu hồi suất' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'DELETE')).toBe(true));
    expect(await screen.findByText('Đã thu hồi suất của Lan Học Viên.')).toBeInTheDocument();
    expect(await screen.findByTestId('license-seats')).toHaveTextContent('Đã dùng 0/2 suất');
  });

  it('refuses an empty assignment in the form and shows the server\'s reason when no seat is left', async () => {
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/licenses`]: () => json(200, [license([holder], 2)]),
      [`GET /api/orgs/${ORG}/role-grants`]: () => json(200, members),
      [`POST /api/orgs/${ORG}/licenses/${LIC}/seats`]: () => problem(409, 'no-seat-available'),
    });
    renderAt('/', '/', <LicensesPage />);
    const form = await screen.findByRole('form', { name: 'Gán suất' });
    await userEvent.click(within(form).getByRole('button', { name: 'Gán suất' }));
    expect(await within(form).findByText('Chọn một thành viên.')).toBeInTheDocument();
    expect(within(form).getByText('Chọn ít nhất một khoá học.')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    await userEvent.click(within(form).getByRole('combobox', { name: 'Thành viên' }));
    await userEvent.click(await screen.findByRole('option', { name: /Minh Học Viên/ }));
    await userEvent.click(within(form).getByRole('checkbox', { name: 'IELTS1 Reading' }));
    await userEvent.click(within(form).getByRole('button', { name: 'Gán suất' }));
    expect(await screen.findByText('Đã hết suất. Hãy thu hồi một suất trước.')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ userId: U2, courseIds: [C1] });
  });
});
