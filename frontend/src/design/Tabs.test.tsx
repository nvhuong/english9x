import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Tabs } from './Tabs';

const items = [
  { value: 'a', label: 'Bài đọc', content: <p>nội dung A</p> },
  { value: 'b', label: 'Từ vựng', content: <p>nội dung B</p> },
  { value: 'c', label: 'Bài tập', content: <p>nội dung C</p> },
];

describe('Tabs', () => {
  it('exposes tablist/tab/tabpanel and selects the first tab', () => {
    render(<Tabs label="Bài học" items={items} />);
    expect(screen.getByRole('tablist', { name: 'Bài học' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Bài đọc' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('nội dung A');
  });

  it('arrow keys move between tabs, Home/End jump', async () => {
    const user = userEvent.setup();
    render(<Tabs label="Bài học" items={items} />);
    screen.getByRole('tab', { name: 'Bài đọc' }).focus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: 'Từ vựng' })).toHaveFocus();
    expect(screen.getByRole('tabpanel')).toHaveTextContent('nội dung B');
    await user.keyboard('{End}');
    expect(screen.getByRole('tab', { name: 'Bài tập' })).toHaveFocus();
    await user.keyboard('{Home}');
    expect(screen.getByRole('tab', { name: 'Bài đọc' })).toHaveFocus();
  });
});
