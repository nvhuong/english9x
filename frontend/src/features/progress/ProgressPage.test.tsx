import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { json, me, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import ProgressPage from './ProgressPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

const course = (over: Record<string, unknown>) => ({
  courseId: 'c1', code: 'ABC123', title: 'TOEIC 600', percent: 50, lessonsDone: 1, lessonsTotal: 2, chaptersDone: 0, chaptersTotal: 1, chapterTestsDone: 0, chapterTestsTotal: 0,
  examsTaken: 1, latestScore: { value: 600, kind: 'OFFICIAL', source: 'AUTO', examCode: 'TOEIC' }, bestScore: null, pendingAssignments: 2, resumeLessonId: 'l2', lastActivity: null, ...over,
});
const plan = (over: Record<string, unknown>) => ({
  source: 'ORDER', id: 'o1', name: 'Gói 3 tháng', organisation: null, activatedAt: '2026-11-01T00:00:00Z', validUntil: '2026-12-01T00:00:00Z', active: true, readOnly: false, daysLeft: 19,
  coursesUsed: 1, coursesMax: 3, courses: [course({})], ...over,
});

describe('ProgressPage', () => {
  it('shows a card per plan with its courses, the expired plans apart and read only', async () => {
    stubApi({
      'GET /api/me/progress': () =>
        json(200, {
          plans: [plan({}), plan({ source: 'SEAT', id: 's1', name: null, organisation: 'Trung tâm A', coursesMax: 1, courses: [course({ courseId: 'c2', title: 'IELTS 6.5', percent: 0, resumeLessonId: 'l9', pendingAssignments: 0, latestScore: null })] })],
          expired: [plan({ id: 'o0', name: 'Gói cũ', active: false, readOnly: true, daysLeft: 0, courses: [course({ courseId: 'c3', title: 'Old course', resumeLessonId: null })] })],
          scoreSeries: [{ attemptId: 'a1', examCode: 'TOEIC', at: '2026-11-05T00:00:00Z', score: 600, kind: 'OFFICIAL', predicted: 600 }],
          weeklyMinutes: [{ weekStart: '2026-11-09', minutes: 45 }],
          streak: { currentDays: 3, longestDays: 5, lastDay: '2026-11-11' },
          pendingAssignments: [{ id: 'x', className: 'Lớp 1', dueAt: '2026-11-20T00:00:00Z', overdue: false, courseId: 'c1' }],
        }),
    });
    renderAt('/progress', '/progress', <ProgressPage />);
    const cards = await screen.findAllByTestId('plan-card');
    expect(cards.map((c) => c.getAttribute('data-source'))).toEqual(['ORDER', 'SEAT', 'ORDER']);
    expect(cards.map((c) => c.getAttribute('data-expired'))).toEqual(['false', 'false', 'true']);
    expect(within(cards[0]!).getByTestId('course-percent')).toHaveTextContent('50%');
    expect(within(cards[0]!).getByRole('link', { name: 'Học tiếp' })).toHaveAttribute('href', '/learn/lessons/l2');
    expect(within(cards[0]!).getByTestId('course-pending')).toHaveTextContent('2');
    expect(within(cards[0]!).getByText('1 / 3')).toBeInTheDocument();
    expect(within(cards[1]!).getByText('Suất học của Trung tâm A')).toBeInTheDocument();
    expect(within(cards[2]!).queryByRole('link', { name: 'Học tiếp' })).toBeNull();
    expect(within(cards[2]!).getByText('Chỉ đọc')).toBeInTheDocument();
    expect(screen.getByTestId('streak')).toHaveTextContent('3');
    expect(screen.getByTestId('score-series')).toHaveTextContent('600');
  });

  it('says so when there is no plan', async () => {
    stubApi({ 'GET /api/me/progress': () => json(200, { plans: [], expired: [], scoreSeries: [], weeklyMinutes: [], streak: { currentDays: 0, longestDays: 0, lastDay: null }, pendingAssignments: [] }) });
    renderAt('/progress', '/progress', <ProgressPage />);
    expect(await screen.findByText('Bạn chưa có gói học nào')).toBeInTheDocument();
  });
});
