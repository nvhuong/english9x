import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { json, me, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import WeaknessPage from './WeaknessPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

describe('WeaknessPage', () => {
  it('lists the question types worst first and the method lessons to open', async () => {
    stubApi({
      'GET /api/results/weakness': () =>
        json(200, {
          exam: null, attempts: 2,
          types: [
            { typeCode: 'P5', typeName: 'Incomplete sentences', correct: 0, total: 2, rate: 0 },
            { typeCode: 'P1', typeName: 'Photographs', correct: 1, total: 2, rate: 0.5 },
          ],
          recommendations: [{ typeCode: 'P5', typeName: 'Incomplete sentences', rate: 0, lessons: [{ courseId: 'c1', courseTitle: 'TOEIC 600', lessonId: 'l1', title: 'Gap method' }] }],
        }),
    });
    renderAt('/results/weakness', '/results/weakness', <WeaknessPage />);
    const rows = await screen.findAllByTestId('type-rate');
    expect(rows.map((r) => r.getAttribute('data-type'))).toEqual(['P5', 'P1']);
    expect(within(rows[0]!).getByText('0%')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('50%')).toBeInTheDocument();
    const rec = screen.getByTestId('recommendation');
    expect(within(rec).getByRole('link', { name: 'Mở: Gap method' })).toHaveAttribute('href', '/learn/lessons/l1');
  });

  it('asks for one exam when the address names it', async () => {
    const seen: string[] = [];
    stubApi({ 'GET /api/results/weakness': (c) => { seen.push(c.search); return json(200, { exam: 'TOEIC', attempts: 0, types: [], recommendations: [] }); } });
    renderAt('/results/weakness?exam=TOEIC', '/results/weakness', <WeaknessPage />);
    expect(await screen.findByText('Chưa có bài thi nào được chấm')).toBeInTheDocument();
    expect(seen[0]).toContain('exam=TOEIC');
  });

  it('says so when no lesson of the person covers the weak types', async () => {
    stubApi({ 'GET /api/results/weakness': () => json(200, { exam: null, attempts: 1, types: [{ typeCode: 'P5', typeName: 'Incomplete sentences', correct: 0, total: 1, rate: 0 }], recommendations: [] }) });
    renderAt('/results/weakness', '/results/weakness', <WeaknessPage />);
    expect(await screen.findByText('Chưa có bài phương pháp nào trong khoá của bạn cho các dạng câu yếu nhất.')).toBeInTheDocument();
  });
});
