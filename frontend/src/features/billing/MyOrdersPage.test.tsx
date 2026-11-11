import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { resetI18n } from '../../i18n';
import { json, me, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import MyOrdersPage from './MyOrdersPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const o = (code: string, status: string) => ({ code, status, amountVnd: 499000, planMonths: 3, createdAt: '2026-11-10T03:00:00Z', transferNote: `E9X ${code}`, courses: [{ id: 'c', code: 'IELTS1' }] });

beforeEach(async () => {
  await resetI18n('vi');
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
});
afterEach(() => vi.unstubAllGlobals());

describe('MyOrdersPage', () => {
  it('lists orders with their status and marks an expired plan read only', async () => {
    stubApi({
      'GET /api/orders': () => json(200, [o('AAA111', 'PENDING_PAYMENT'), o('BBB222', 'CONFIRMED')]),
      'GET /api/me/entitlements': () => json(200, [{ id: 'e1', courseCode: 'IELTS1', active: false, source: 'ORDER', courseId: 'c', validFrom: '2026-01-01T00:00:00Z', validUntil: '2026-04-01T00:00:00Z' }]),
    });
    renderAt('/orders', '/orders', <MyOrdersPage />);
    const rows = await screen.findAllByTestId('my-order');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Chờ thanh toán');
    expect(screen.getByRole('link', { name: 'BBB222' })).toHaveAttribute('href', '/orders/BBB222');
    expect(await screen.findByTestId('my-entitlement')).toHaveTextContent('Hết hạn, chỉ đọc');
  });

  it('says so when there are no orders', async () => {
    stubApi({ 'GET /api/orders': () => json(200, []), 'GET /api/me/entitlements': () => json(200, []) });
    renderAt('/orders', '/orders', <MyOrdersPage />);
    expect(await screen.findByText('Bạn chưa có đơn nào')).toBeInTheDocument();
  });
});
