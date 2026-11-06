import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Highlighter } from './Highlighter';

const text = 'Thủ đô của Việt Nam là Hà Nội.\n\nĐoạn hai có ba từ.';
const marks = (c: HTMLElement) => [...c.querySelectorAll('mark')].map((m) => m.textContent?.trim());

describe('Highlighter (keyboard usable)', () => {
  it('has instructions, a toolbar and the words as a single tab stop', async () => {
    const user = userEvent.setup();
    render(<Highlighter text={text} />);
    expect(screen.getByRole('group', { name: 'Bài đọc có thể tô sáng' })).toHaveAccessibleDescription(/mũi tên/);
    expect(screen.getByRole('toolbar', { name: 'Công cụ tô sáng' })).toBeInTheDocument();
    await user.tab(); // Highlight
    await user.tab(); // Remove
    await user.tab(); // Clear all
    await user.tab(); // first word
    expect(screen.getByText('Thủ')).toHaveFocus();
    await user.tab();
    expect(screen.getByText('Thủ')).not.toHaveFocus(); // left the passage
  });

  it('Shift+Arrow selects words and Enter highlights them; Enter again removes (toggle)', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const { container } = render(<Highlighter text={text} onChange={onChange} />);
    screen.getByText('Thủ').focus();
    await user.keyboard('{Shift>}{ArrowRight}{ArrowRight}{/Shift}');
    await user.keyboard('{Enter}');
    expect(marks(container)).toEqual(['Thủ', 'đô', 'của']);
    expect(onChange).toHaveBeenLastCalledWith([[0, 2]]);
    expect(screen.getAllByRole('status').some((s) => s.textContent === 'Đã tô sáng')).toBe(true);
    await user.keyboard('{Enter}');
    expect(marks(container)).toEqual([]);
    expect(onChange).toHaveBeenLastCalledWith([]);
  });

  it('highlights the focused word when nothing is selected, using Space; Escape clears a selection', async () => {
    const user = userEvent.setup();
    const { container } = render(<Highlighter text={text} />);
    screen.getByText('Thủ').focus();
    await user.keyboard('{ArrowRight}{ArrowRight} ');
    expect(marks(container)).toEqual(['của']);
    await user.keyboard('{Shift>}{ArrowRight}{/Shift}{Escape}');
    await user.keyboard('{ArrowRight}');
    await user.keyboard('{Enter}');
    expect(marks(container)).toEqual(['của', 'Nam']);
  });

  it('toolbar buttons work: highlight the current word, remove it, clear all; a second paragraph is reachable', async () => {
    const user = userEvent.setup();
    const { container } = render(<Highlighter text={text} defaultHighlights={[[8, 9]]} />);
    expect(marks(container)).toEqual(['Đoạn', 'hai']);
    screen.getByText('Thủ').focus();
    await user.click(screen.getByRole('button', { name: 'Tô sáng' }));
    expect(marks(container)).toContain('Thủ');
    screen.getByText('Thủ').focus();
    await user.click(screen.getByRole('button', { name: 'Bỏ tô sáng' }));
    expect(marks(container)).not.toContain('Thủ');
    await user.click(screen.getByRole('button', { name: 'Xoá mọi tô sáng' }));
    expect(marks(container)).toEqual([]);
  });

  it('End / Home jump across paragraphs', async () => {
    const user = userEvent.setup();
    render(<Highlighter text={text} />);
    screen.getByText('Thủ').focus();
    await user.keyboard('{End}');
    expect(screen.getByText('từ.')).toHaveFocus();
    await user.keyboard('{Home}');
    expect(screen.getByText('Thủ')).toHaveFocus();
  });

  it('mouse selection feeds the toolbar', () => {
    const { container } = render(<Highlighter text={text} />);
    const sel = { isCollapsed: false, anchorNode: screen.getByText('Việt').firstChild, focusNode: screen.getByText('Nam').firstChild };
    vi.spyOn(window, 'getSelection').mockReturnValue(sel as unknown as Selection);
    fireEvent.mouseUp(screen.getByRole('group', { name: 'Bài đọc có thể tô sáng' }));
    fireEvent.click(screen.getByRole('button', { name: 'Tô sáng' }));
    expect(marks(container)).toEqual(['Việt', 'Nam']);
    vi.restoreAllMocks();
  });
});
