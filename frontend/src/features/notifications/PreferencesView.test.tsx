import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PreferencesView } from './PreferencesView';

const events = [
  { type: 'REVIEW_REQUESTED', audience: 'REVIEWER', email: true, telegram: false },
  { type: 'CONTENT_PUBLISHED', audience: 'AUTHOR', email: false, telegram: true },
];

describe('PreferencesView', () => {
  it('shows one row per event with in-app always on and a checkbox per optional channel', () => {
    render(<PreferencesView events={events} telegramLinked busy={false} onToggle={vi.fn()} />);
    const row = screen.getByRole('row', { name: /Có nội dung chờ duyệt/ });
    expect(within(row).getByText('Luôn bật')).toBeInTheDocument();
    expect(within(row).getAllByRole('checkbox')).toHaveLength(2);
    expect(within(row).getByRole('checkbox', { name: /Email/ })).toBeChecked();
    expect(within(row).getByRole('checkbox', { name: /Telegram/ })).not.toBeChecked();
    expect(screen.getByText(/Dành cho Người duyệt/)).toBeInTheDocument();
  });

  it('reports the toggle with event, channel and the new value', async () => {
    const onToggle = vi.fn();
    render(<PreferencesView events={events} telegramLinked busy={false} onToggle={onToggle} />);
    await userEvent.click(screen.getByRole('checkbox', { name: /Email: Có nội dung chờ duyệt/ }));
    expect(onToggle).toHaveBeenCalledWith('REVIEW_REQUESTED', 'EMAIL', false);
    await userEvent.click(screen.getByRole('checkbox', { name: /Telegram: Có nội dung chờ duyệt/ }));
    expect(onToggle).toHaveBeenCalledWith('REVIEW_REQUESTED', 'TELEGRAM', true);
  });

  it('says so when Telegram is not linked', () => {
    render(<PreferencesView events={events} telegramLinked={false} onToggle={vi.fn()} />);
    expect(screen.getByText(/Telegram chưa được liên kết/)).toBeInTheDocument();
  });
});
