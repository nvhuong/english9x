import type { Meta, StoryObj } from '@storybook/react-vite';
import { MemoryRouter } from 'react-router-dom';
import { ChapterMap } from './ChapterMap';
import { AttemptResult, ChapterTestForm } from './ChapterTestPage';
import type { ChapterMapData, ChapterTestData } from './types';

const map: ChapterMapData = {
  courseId: 'c1',
  title: 'TOEIC Reading 600',
  unlockRule: 'STRICT',
  streakDays: 4,
  todayMinutes: 12,
  chapters: [
    {
      title: 'Part 5',
      passThreshold: 0.7,
      lessons: [
        { id: 'l1', kind: 'LESSON', title: 'Chọn từ loại', completed: true, locked: false },
        { id: 'l2', kind: 'CHAPTER_TEST', title: 'Kiểm tra chương 1', completed: true, bestScore: 0.8, locked: false },
      ],
    },
    { title: 'Part 6', passThreshold: 0.7, lessons: [{ id: 'l3', kind: 'LESSON', title: 'Điền đoạn văn', completed: false, locked: true }] },
  ],
};

const test: ChapterTestData = {
  lessonId: 'l2',
  courseId: 'c1',
  title: 'Kiểm tra chương 1',
  passThreshold: 0.7,
  maxAttempts: 3,
  attemptsUsed: 1,
  canRetake: true,
  bestScore: 0.5,
  groups: [
    {
      content: { title: 'Part 5' },
      questions: [
        { versionId: 'q1', typeCode: 'TOEIC_P5_INCOMPLETE_SENTENCE', points: 1, content: { stem: 'The manager ___ the report.', options: [{ key: 'A', text: 'write' }, { key: 'B', text: 'wrote' }] } },
        { versionId: 'q2', typeCode: 'IELTS_R_SHORT_ANSWER', points: 1, content: { stem: 'Name the capital of Vietnam.' } },
      ],
    },
  ],
};

const meta = { title: 'Learning/Learner', decorators: [(Story) => <MemoryRouter><Story /></MemoryRouter>] } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const Map: S = { render: () => <ChapterMap map={map} /> };
export const Test: S = { render: () => <ChapterTestForm test={test} onSubmit={() => {}} /> };
export const TestExhausted: S = { render: () => <ChapterTestForm test={{ ...test, canRetake: false, attemptsUsed: 3 }} onSubmit={() => {}} /> };
export const Result: S = { render: () => <AttemptResult threshold={0.7} result={{ attemptNo: 2, score: 0.5, raw: 1, rawMax: 2, passed: false, bestScore: 0.5, attemptsUsed: 2, attemptsLeft: 1 }} /> };
