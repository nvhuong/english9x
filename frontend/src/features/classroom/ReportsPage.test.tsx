import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import ReportsPage from './ReportsPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ORG = 'aaaaaaaa-0000-0000-0000-00000000000a';
const CLS = 'cccccccc-0000-0000-0000-00000000000c';
const learner = (over: Record<string, unknown>) => ({
  userId: 'u1', displayName: 'An Chăm Chỉ', email: null, progressPercent: 100, courses: [{ courseId: 'k1', code: 'C1', title: 'Khoá 1', percent: 100 }],
  assignments: { total: 2, submitted: 1, late: 1, missing: 0, pending: 0 }, latestScore: { value: 450, kind: 'OFFICIAL', examCode: 'TOEIC' },
  bestScore: { value: 600, kind: 'OFFICIAL', examCode: 'TOEIC' }, weakTypes: [{ typeCode: 'P5', typeName: 'Part 5', correct: 0, total: 2, rate: 0 }],
  attendance: { present: 1, late: 1, absent: 0, excused: 0, rate: 1 }, studyMinutes: 25, ...over,
});
const report = (learners: unknown[]) => ({ classId: CLS, className: 'Lớp 10A', kind: 'CLASS', generatedAt: '2026-11-12T10:00:00Z', learners });

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: ORG });
});
afterEach(() => vi.unstubAllGlobals());

describe('ReportsPage', () => {
  it('shows one row per learner with the figures of the report', async () => {
    stubApi({
      [`GET /api/orgs/${ORG}/classes/${CLS}/report`]: () =>
        json(200, report([learner({}), learner({ userId: 'u2', displayName: 'Bảo Lười', progressPercent: null, courses: [], latestScore: null, bestScore: null, weakTypes: [], attendance: { present: 0, late: 0, absent: 0, excused: 0, rate: null }, studyMinutes: 0, assignments: { total: 0, submitted: 0, late: 0, missing: 0, pending: 0 } })])),
    });
    renderAt(`/org/classes/${CLS}/report`, '/org/classes/:classId/report', <ReportsPage />);
    const rows = await screen.findAllByTestId('report-row');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByText('An Chăm Chỉ')).toBeInTheDocument();
    expect(within(rows[0]!).getByTestId('report-progress')).toHaveTextContent('100%');
    expect(within(rows[0]!).getByTestId('report-assignments')).toHaveTextContent('1 đúng hạn · 1 trễ · 0 chưa nộp · 0 chưa đến hạn');
    expect(within(rows[0]!).getByTestId('report-latest')).toHaveTextContent('450 (chính thức)');
    expect(within(rows[0]!).getByTestId('report-best')).toHaveTextContent('600 (chính thức)');
    expect(within(rows[0]!).getByTestId('report-weak')).toHaveTextContent('Part 5 (0/2)');
    expect(within(rows[0]!).getByTestId('report-attendance')).toHaveTextContent('100%');
    expect(within(rows[0]!).getByTestId('report-minutes')).toHaveTextContent('25');
    expect(within(rows[1]!).getByTestId('report-progress')).toHaveTextContent('Chưa có khoá');
    expect(within(rows[1]!).getByTestId('report-attendance')).toHaveTextContent('–');
    expect(within(rows[1]!).getByTestId('report-assignments')).toHaveTextContent('Chưa giao bài');
    expect(screen.getByRole('button', { name: 'Tải Excel' })).toBeInTheDocument();
  });

  it('asks for the Excel file with the language of the page', async () => {
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/classes/${CLS}/report`]: () => json(200, report([learner({})])),
      [`GET /api/orgs/${ORG}/classes/${CLS}/report.xlsx`]: () => new Response(new Uint8Array([80, 75]), { status: 200, headers: { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' } }),
    });
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => {} }));
    renderAt(`/org/classes/${CLS}/report`, '/org/classes/:classId/report', <ReportsPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Tải Excel' }));
    await vi.waitFor(() => expect(calls.some((c) => c.path.endsWith('/report.xlsx') && c.search.includes('lang=vi'))).toBe(true));
  });

  it('says not found for a class of another organisation and forbidden without the role', async () => {
    stubApi({ [`GET /api/orgs/${ORG}/classes/${CLS}/report`]: () => problem(404, 'not-found') });
    const { unmount } = renderAt(`/org/classes/${CLS}/report`, '/org/classes/:classId/report', <ReportsPage />);
    expect(await screen.findByRole('heading', { name: /không tìm thấy/i })).toBeInTheDocument();
    unmount();
    stubApi({ [`GET /api/orgs/${ORG}/classes/${CLS}/report`]: () => problem(403, 'forbidden') });
    renderAt(`/org/classes/${CLS}/report`, '/org/classes/:classId/report', <ReportsPage />);
    expect(await screen.findByRole('heading', { name: /chưa có quyền/i })).toBeInTheDocument();
  });
});
