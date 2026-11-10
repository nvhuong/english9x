import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resetI18n } from '../../i18n';
import { OrderStatusView } from './OrderStatusView';
import type { OrderView } from './queries';

const order = (over: Partial<OrderView> = {}): OrderView => ({
  code: 'AB12CD',
  status: 'PENDING_PAYMENT',
  amountVnd: 499000,
  planId: 'p',
  planMonths: 3,
  createdAt: '2026-11-10T03:00:00Z',
  transferNote: 'E9X AB12CD IELTS1',
  courses: [],
  rejectReason: undefined,
  qrPayload: '000201',
  qrPngUrl: '/api/orders/AB12CD/qr.png',
  payTo: { bankBin: '970436', accountNo: '1234567890', accountName: 'ENGLISH9X JSC' },
  ...over,
});

beforeEach(async () => {
  await resetI18n('vi');
});

describe('OrderStatusView', () => {
  it('shows the QR and the exact amount, account and note, and copies the note exactly', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    render(<OrderStatusView order={order()} qrSrc="blob:qr" qrFailed={false} />);
    expect(screen.getByRole('img', { name: 'Mã QR thanh toán đơn AB12CD' })).toHaveAttribute('src', 'blob:qr');
    expect(screen.getByTestId('copy-value-note')).toHaveTextContent('E9X AB12CD IELTS1');
    expect(screen.getByTestId('copy-value-amount')).toHaveTextContent('499000');
    await userEvent.click(screen.getByRole('button', { name: 'Sao chép Nội dung chuyển khoản' }));
    expect(writeText).toHaveBeenCalledWith('E9X AB12CD IELTS1');
    await userEvent.click(screen.getByRole('button', { name: 'Sao chép Số tiền (đ)' }));
    expect(writeText).toHaveBeenLastCalledWith('499000');
  });

  it('says so when no account is configured, and shows no payment details', () => {
    render(<OrderStatusView order={order({ payTo: undefined, qrPayload: undefined, qrPngUrl: undefined })} qrSrc={null} qrFailed={false} />);
    expect(screen.getByText(/Chưa có tài khoản nhận tiền/)).toBeInTheDocument();
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('shows the rejection reason as text, never as markup', () => {
    render(<OrderStatusView order={order({ status: 'REJECTED', payTo: undefined, rejectReason: '<img src=x onerror=alert(1)>' })} qrSrc={null} qrFailed={false} />);
    expect(screen.getByTestId('reject-reason')).toHaveTextContent('<img src=x onerror=alert(1)>');
    expect(document.querySelector('img')).toBeNull();
  });

  it('a confirmed order shows no payment block', () => {
    render(<OrderStatusView order={order({ status: 'CONFIRMED', payTo: undefined })} qrSrc={null} qrFailed={false} />);
    expect(screen.getByText(/Thanh toán đã được xác nhận/)).toBeInTheDocument();
    expect(screen.queryByText('Quét mã để thanh toán')).toBeNull();
  });
});
