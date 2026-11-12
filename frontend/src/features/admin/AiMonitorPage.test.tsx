import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import AiMonitorPage from './AiMonitorPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

describe('AiMonitorPage', () => {
  it('raises the alert for the days above the threshold and lists failed jobs and deviations', async () => {
    stubApi({
      'GET /api/admin/ai-monitor': () =>
        json(200, {
          from: '2026-11-01', to: '2026-11-02', dailyCostAlert: 500000, costAlert: true, alertDays: ['2026-11-01'], totalCostVnd: 650000, aiFailedGradings: 2,
          days: [
            { day: '2026-11-01', costVnd: 550000, tokens: 300, evaluations: 2, overThreshold: true },
            { day: '2026-11-02', costVnd: 100000, tokens: 10, evaluations: 1, overThreshold: false },
          ],
          costByDayAndPerson: [{ day: '2026-11-01', userId: 'u1', email: 'a@x.test', costVnd: 550000, inputTokens: 1, outputTokens: 1, evaluations: 2 }],
          failedJobs: [{ id: 'j1', type: 'ai_grade', attempts: 3, lastError: 'Gemini 503', failedAt: '2026-11-01T05:00:00Z' }],
          deviation: [{ typeCode: 'W1', typeName: 'Writing task 1', samples: 4, meanDeviation: 0.5, meanAbsDeviation: 0.75 }],
        }),
    });
    renderAt('/admin/ai-monitor', '/admin/ai-monitor', <AiMonitorPage />);
    expect(await screen.findByTestId('cost-alert')).toHaveTextContent('2026-11-01');
    const days = screen.getAllByTestId('cost-day');
    expect(days.map((d) => d.getAttribute('data-over'))).toEqual(['true', 'false']);
    expect(screen.getByTestId('failed-job')).toHaveTextContent('Gemini 503');
    expect(screen.getByTestId('deviation-row')).toHaveTextContent('Writing task 1');
    expect(screen.getByTestId('cost-person')).toHaveTextContent('a@x.test');
  });

  it('shows no alert below the threshold, and no access to anyone else', async () => {
    stubApi({
      'GET /api/admin/ai-monitor': () =>
        json(200, { from: 'a', to: 'b', dailyCostAlert: 500000, costAlert: false, alertDays: [], totalCostVnd: 0, aiFailedGradings: 0, days: [], costByDayAndPerson: [], failedJobs: [], deviation: [] }),
    });
    const { unmount } = renderAt('/admin/ai-monitor', '/admin/ai-monitor', <AiMonitorPage />);
    expect(await screen.findByTestId('cost-ok')).toBeInTheDocument();
    expect(screen.queryByTestId('cost-alert')).toBeNull();
    unmount();
    stubApi({ 'GET /api/admin/ai-monitor': () => problem(403, 'forbidden') });
    renderAt('/admin/ai-monitor', '/admin/ai-monitor', <AiMonitorPage />);
    expect(await screen.findByRole('heading', { level: 1, name: "Bạn chưa có quyền xem trang này" })).toBeInTheDocument();
  });
});
