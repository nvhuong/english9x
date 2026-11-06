import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Button } from './Button';
import { ToastProvider, useToast } from './Toast';

function Trigger({ tone }: { tone?: 'danger' }) {
  const { toast } = useToast();
  return <Button onClick={() => toast({ title: 'Đã lưu', description: 'Bản nháp được cập nhật', tone })}>Bắn</Button>;
}

describe('Toast', () => {
  it('shows a toast with title/description and a labelled dismiss button', async () => {
    const user = userEvent.setup();
    render(<ToastProvider><Trigger /></ToastProvider>);
    await user.click(screen.getByRole('button', { name: 'Bắn' }));
    const title = (await screen.findAllByText('Đã lưu')).find((e) => e.closest('li')) as HTMLElement;
    const toast = title.closest('li') as HTMLElement;
    expect(toast).toHaveTextContent('Đã lưu');
    expect(toast).toHaveTextContent('Bản nháp được cập nhật');
    await user.click(screen.getByRole('button', { name: 'Tắt thông báo' }));
    await act(async () => {});
    expect(screen.queryByText('Bản nháp được cập nhật')).not.toBeInTheDocument();
  });

  it('exposes a notification region reachable by hotkey label', async () => {
    render(<ToastProvider><Trigger /></ToastProvider>);
    expect(screen.getByRole('region', { name: /Thông báo/ })).toBeInTheDocument();
  });

  it('useToast outside the provider fails loudly', () => {
    expect(() => render(<Trigger />)).toThrow(/ToastProvider/);
  });
});
