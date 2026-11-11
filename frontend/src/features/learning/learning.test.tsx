import { render, renderHook, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ChapterMap } from './ChapterMap';
import { ChapterTestForm } from './ChapterTestPage';
import { PracticeScore, usePractice } from './PracticeBlock';
import type { ChapterMapData, ChapterTestData } from './types';

const map: ChapterMapData = {
  courseId: 'c1',
  title: 'Khoá TOEIC',
  unlockRule: 'STRICT',
  streakDays: 3,
  todayMinutes: 12,
  chapters: [
    {
      title: 'Part 5',
      passThreshold: 0.7,
      lessons: [
        { id: 'l1', kind: 'LESSON', title: 'Bài một', completed: true, locked: false },
        { id: 'l2', kind: 'CHAPTER_TEST', title: 'Kiểm tra', completed: true, bestScore: 0.8, locked: false },
        { id: 'l3', kind: 'LESSON', title: 'Bài hai', completed: false, locked: true },
      ],
    },
  ],
};

describe('ChapterMap', () => {
  it('links open lessons to their page, the chapter test to its own, and only names a locked one', () => {
    render(<MemoryRouter><ChapterMap map={map} /></MemoryRouter>);
    expect(screen.getByRole('link', { name: 'Bài một' })).toHaveAttribute('href', '/learn/lessons/l1');
    expect(screen.getByRole('link', { name: 'Kiểm tra' })).toHaveAttribute('href', '/learn/chapter-tests/l2');
    expect(screen.queryByRole('link', { name: 'Bài hai' })).toBeNull();
    expect(screen.getByText('Đang khoá')).toBeInTheDocument();
    expect(screen.getByText('Cao nhất 80%')).toBeInTheDocument();
    expect(screen.getByTestId('streak')).toHaveTextContent('Chuỗi 3 ngày');
    expect(screen.getByTestId('today')).toHaveTextContent('Hôm nay: 12 phút');
  });
});

describe('usePractice', () => {
  it('maps the server verdict to feedback and keeps a tally', async () => {
    const check = vi.fn().mockResolvedValueOnce({ correct: true, earned: 1, explanation: 'Vì sao', correctAnswer: 'D' }).mockResolvedValueOnce({ correct: false, earned: 0, explanation: null, correctAnswer: 'D' });
    const { result } = renderHook(() => usePractice('lesson-1', check));
    let first: Awaited<ReturnType<typeof result.current.checkAnswer>> | undefined;
    await act(async () => {
      first = await result.current.checkAnswer('q1', 'D');
    });
    expect(check).toHaveBeenCalledWith('q1', 'D');
    expect(first).toEqual({ correct: true, correctAnswer: 'D', explanation: 'Vì sao' });
    await act(async () => {
      await result.current.checkAnswer('q1', 'A');
    });
    expect(result.current.tally).toEqual({ answered: 2, correct: 1 });
  });

  it('PracticeScore says nothing for a lesson without questions and counts the answered ones', () => {
    const { rerender, container } = render(<PracticeScore tally={{ answered: 0, correct: 0 }} total={0} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<PracticeScore tally={{ answered: 2, correct: 1 }} total={3} />);
    expect(screen.getByRole('status')).toHaveTextContent('đúng 1/2');
  });
});

const test: ChapterTestData = {
  lessonId: 'l2',
  courseId: 'c1',
  title: 'Kiểm tra chương',
  passThreshold: 0.7,
  maxAttempts: 2,
  attemptsUsed: 0,
  canRetake: true,
  groups: [{ questions: [{ versionId: 'q1', typeCode: 'T', points: 1, content: { stem: 'Câu hỏi?', options: [{ key: 'A', text: 'x' }, { key: 'B', text: 'y' }] } }] }],
};

describe('ChapterTestForm', () => {
  it('submits only the questions that were answered, by question version', async () => {
    const onSubmit = vi.fn();
    render(<ChapterTestForm test={test} onSubmit={onSubmit} />);
    await userEvent.click(screen.getByRole('radio', { name: /B\. y/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Nộp bài' }));
    expect(onSubmit).toHaveBeenCalledWith([{ questionVersionId: 'q1', answer: 'B' }]);
  });

  it('shows no questions and no submit when the attempts are used up', () => {
    render(<ChapterTestForm test={{ ...test, canRetake: false, attemptsUsed: 2 }} onSubmit={vi.fn()} />);
    expect(screen.getByRole('alert')).toHaveTextContent('hết số lần làm');
    expect(screen.queryByRole('button', { name: 'Nộp bài' })).toBeNull();
    expect(screen.queryByRole('radio')).toBeNull();
  });

  it('shows the score, never an answer, after the attempt', () => {
    render(<ChapterTestForm test={test} onSubmit={vi.fn()} result={{ attemptNo: 1, score: 0.5, raw: 1, rawMax: 2, passed: false, bestScore: 0.5, attemptsUsed: 1, attemptsLeft: 1 }} />);
    const r = screen.getByTestId('attempt-result');
    expect(r).toHaveTextContent('Chưa đạt');
    expect(r).toHaveTextContent('Điểm 50%');
    expect(screen.queryByRole('radio')).toBeNull();
  });
});
