import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { resetI18n } from '../../i18n';
import { NotificationListView, type ListItem } from './NotificationListView';

const item = (over: Partial<ListItem> = {}): ListItem => ({
  id: 'n1', type: 'REVIEW_REQUESTED', data: { contentType: 'question', examCode: 'IELTS' }, createdAt: '2026-11-07T03:00:00Z', read: false, ...over,
});
const props = { unread: 1, hasMore: false, onMarkRead: vi.fn(), onMarkAll: vi.fn(), onMore: vi.fn() };

describe('NotificationListView', () => {
  it('words the event in Vietnamese from its type and data, and marks unread with text', () => {
    render(<NotificationListView {...props} items={[item()]} />);
    expect(screen.getByRole('heading', { name: 'Có nội dung chờ duyệt' })).toBeInTheDocument();
    expect(screen.getByText('Một nội dung (question) của kỳ thi IELTS đang chờ bạn duyệt.')).toBeInTheDocument();
    expect(screen.getByText('Chưa đọc')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('1 chưa đọc');
  });

  it('re-words the same notification when the language changes', async () => {
    await resetI18n('en');
    render(<NotificationListView {...props} items={[item({ type: 'CONTENT_PUBLISHED' })]} />);
    expect(screen.getByRole('heading', { name: 'Your content was published' })).toBeInTheDocument();
    expect(screen.getByText('Unread')).toBeInTheDocument();
  });

  it('marks one as read, or all', async () => {
    const onMarkRead = vi.fn();
    const onMarkAll = vi.fn();
    render(<NotificationListView {...props} onMarkRead={onMarkRead} onMarkAll={onMarkAll} items={[item(), item({ id: 'n2', read: true })]} />);
    const rows = screen.getAllByRole('listitem');
    expect(within(rows[1]!).queryByRole('button')).toBeNull();
    await userEvent.click(within(rows[0]!).getByRole('button', { name: /Đánh dấu đã đọc/ }));
    expect(onMarkRead).toHaveBeenCalledWith('n1');
    await userEvent.click(screen.getByRole('button', { name: 'Đánh dấu tất cả đã đọc' }));
    expect(onMarkAll).toHaveBeenCalled();
  });

  it('shows data as text, never as markup', () => {
    render(<NotificationListView {...props} items={[item({ data: { contentType: '<img src=x onerror=alert(1)>', examCode: '<b>x</b>' } })]} />);
    expect(document.querySelector('img')).toBeNull();
    expect(document.querySelector('li b')).toBeNull();
    expect(screen.getByText(/<img src=x onerror=alert\(1\)>/)).toBeInTheDocument();
  });

  it('shows a type this build does not know by its code, and an empty state', () => {
    const { rerender } = render(<NotificationListView {...props} items={[item({ type: 'FUTURE_THING', data: {} })]} />);
    expect(screen.getByRole('heading', { name: 'FUTURE_THING' })).toBeInTheDocument();
    rerender(<NotificationListView {...props} unread={0} items={[]} />);
    expect(screen.getByRole('heading', { name: 'Chưa có thông báo nào' })).toBeInTheDocument();
  });
});
