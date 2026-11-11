import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { resetI18n } from '../../i18n';
import { json, me, problem, resetAuth, stubApi } from '../auth/testUtils';
import CheckoutPage from './CheckoutPage';
import OrderStatusPage from './OrderStatusPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const plan = { id: 'plan-1', name: 'Gói 3 tháng', priceVnd: 499000, months: 3, maxCourses: 1, active: true };
const catalog = [
  { id: 'c-1', code: 'IELTS1', examCode: 'IELTS', title: 'IELTS Foundation', shared: false },
  { id: 'c-2', code: 'TOEIC1', examCode: 'TOEIC', title: 'TOEIC Basic', shared: false },
  { id: 'c-3', code: 'ORGX01', examCode: 'IELTS', title: 'Khoá của tổ chức', shared: true },
];
const orderView = {
  code: 'AB12CD', status: 'PENDING_PAYMENT', amountVnd: 499000, planId: 'plan-1', planMonths: 3, createdAt: '2026-11-10T03:00:00Z',
  transferNote: 'E9X AB12CD IELTS1', courses: [{ id: 'c-1', code: 'IELTS1' }], qrPayload: '000201', qrPngUrl: '/api/orders/AB12CD/qr.png',
  payTo: { bankBin: '970436', accountNo: '1234567890', accountName: 'ENGLISH9X JSC' },
};

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/checkout']}>
        <Routes>
          <Route path="/checkout" element={<CheckoutPage />} />
          <Route path="/orders/:code" element={<OrderStatusPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await resetI18n('vi');
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:qr', revokeObjectURL: () => {} }));
});
afterEach(() => vi.unstubAllGlobals());

const routes = {
  'GET /api/plans': () => json(200, [plan]),
  'GET /api/catalog/courses': () => json(200, catalog),
};

describe('CheckoutPage', () => {
  it('copyButtonsCopyExactNote: places the order with the chosen course ids, then the copy buttons copy the note and amount verbatim', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const calls = stubApi({
      ...routes,
      'POST /api/orders': () => json(201, { orderCode: 'AB12CD', amountVnd: 499000, transferNote: 'E9X AB12CD IELTS1' }),
      'GET /api/orders/AB12CD': () => json(200, orderView),
      'GET /api/orders/AB12CD/qr.png': () => new Response(new Blob(['x']), { status: 200 }),
    });
    renderApp();
    await userEvent.click(await screen.findByRole('radio', { name: /Gói 3 tháng/ }));
    // the organisation's shared course is not for sale; the plan's limit is one course
    expect(screen.queryByText('Khoá của tổ chức')).toBeNull();
    await userEvent.click(screen.getByRole('checkbox', { name: /IELTS Foundation/ }));
    expect(screen.getByRole('checkbox', { name: /TOEIC Basic/ })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Đặt đơn và thanh toán' }));
    expect(await screen.findByTestId('copy-value-note')).toHaveTextContent('E9X AB12CD IELTS1');
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ planId: 'plan-1', courseIds: ['c-1'] });
    await userEvent.click(screen.getByRole('button', { name: 'Sao chép Nội dung chuyển khoản' }));
    expect(writeText).toHaveBeenCalledWith('E9X AB12CD IELTS1');
    await userEvent.click(screen.getByRole('button', { name: 'Sao chép Số tiền (đ)' }));
    expect(writeText).toHaveBeenLastCalledWith('499000');
  });

  it('tells an unverified learner to verify the email, and keeps the choice', async () => {
    stubApi({ ...routes, 'POST /api/orders': () => problem(403, 'email-not-verified') });
    renderApp();
    await userEvent.click(await screen.findByRole('radio', { name: /Gói 3 tháng/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: /IELTS Foundation/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Đặt đơn và thanh toán' }));
    expect(await screen.findByText('Hãy xác thực email trước khi mua gói.')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /IELTS Foundation/ })).toBeChecked();
  });

  it('cannot submit without a plan and a course', async () => {
    stubApi(routes);
    renderApp();
    expect(await screen.findByRole('button', { name: 'Đặt đơn và thanh toán' })).toBeDisabled();
  });
});
