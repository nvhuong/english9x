import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { QuestionNavigator } from './QuestionNavigator';

const questions = Array.from({ length: 25 }, (_, i) => ({ id: `q${i + 1}`, number: i + 1, answered: i < 3, flagged: i === 1 }));

describe('QuestionNavigator', () => {
  it('names each button with number, answered and flagged state; marks the current one', () => {
    render(<QuestionNavigator questions={questions} currentId="q2" onSelect={() => {}} />);
    expect(screen.getByRole('group', { name: 'Danh sách câu hỏi' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Câu 1, đã trả lời' })).toBeInTheDocument();
    const cur = screen.getByRole('button', { name: 'Câu 2, đã trả lời, đã đánh dấu' });
    expect(cur).toHaveAttribute('aria-current', 'step');
    expect(screen.getByRole('button', { name: 'Câu 10, chưa trả lời' })).not.toHaveAttribute('aria-current');
  });

  it('is one tab stop; arrows, Home/End and Up/Down move focus; Enter selects', async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(<QuestionNavigator questions={questions} currentId="q3" onSelect={onSelect} columns={10} />);
    await user.tab();
    expect(screen.getByRole('button', { name: /^Câu 3,/ })).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('button', { name: /^Câu 4,/ })).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('button', { name: /^Câu 14,/ })).toHaveFocus();
    await user.keyboard('{ArrowLeft}{ArrowUp}');
    expect(screen.getByRole('button', { name: /^Câu 3,/ })).toHaveFocus();
    await user.keyboard('{End}');
    expect(screen.getByRole('button', { name: /^Câu 25,/ })).toHaveFocus();
    await user.keyboard('{Home}');
    expect(screen.getByRole('button', { name: /^Câu 1,/ })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(onSelect).toHaveBeenCalledWith('q1');
    // Roving tabindex: exactly one button is in the tab order.
    expect(screen.getAllByRole('button').filter((b) => b.tabIndex === 0)).toHaveLength(1);
  });

  it('clamps at the ends', async () => {
    const user = userEvent.setup();
    render(<QuestionNavigator questions={questions.slice(0, 3)} currentId="q1" onSelect={() => {}} />);
    await user.tab();
    await user.keyboard('{ArrowLeft}');
    expect(screen.getByRole('button', { name: /^Câu 1,/ })).toHaveFocus();
  });
});
