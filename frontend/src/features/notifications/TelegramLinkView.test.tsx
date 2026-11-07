import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TelegramLinkView } from './TelegramLinkView';

const handlers = { onCreate: vi.fn(), onUnlink: vi.fn(), onCheck: vi.fn() };

describe('TelegramLinkView', () => {
  it('offers to create a link, then shows the one-time deep link', async () => {
    const onCreate = vi.fn();
    const { rerender } = render(<TelegramLinkView {...handlers} onCreate={onCreate} enabled linked={false} />);
    expect(screen.getByText('Chưa kết nối Telegram')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Tạo liên kết kết nối' }));
    expect(onCreate).toHaveBeenCalled();
    rerender(<TelegramLinkView {...handlers} enabled linked={false} link={{ deepLink: 'https://t.me/e9x_bot?start=abc', expiresAt: '2026-11-07T04:00:00Z' }} />);
    const open = screen.getByRole('link', { name: 'Mở trong Telegram' });
    expect(open).toHaveAttribute('href', 'https://t.me/e9x_bot?start=abc');
    expect(open).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('never renders a link that does not point at Telegram', () => {
    render(<TelegramLinkView {...handlers} enabled linked={false} link={{ deepLink: 'javascript:alert(1)', expiresAt: '2026-11-07T04:00:00Z' }} />);
    expect(screen.queryByRole('link', { name: 'Mở trong Telegram' })).toBeNull();
  });

  it('can unlink when linked, and says when the server has no bot', async () => {
    const onUnlink = vi.fn();
    const { rerender } = render(<TelegramLinkView {...handlers} onUnlink={onUnlink} enabled linked />);
    expect(screen.getByText('Đã kết nối Telegram')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Huỷ kết nối' }));
    expect(onUnlink).toHaveBeenCalled();
    rerender(<TelegramLinkView {...handlers} enabled={false} linked={false} />);
    expect(screen.getByText('Telegram chưa được bật trên máy chủ này.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tạo liên kết kết nối' })).toBeNull();
  });
});
