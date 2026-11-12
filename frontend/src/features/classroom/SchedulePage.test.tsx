import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { json, me, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import SchedulePage from './SchedulePage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ORG = 'aaaaaaaa-0000-0000-0000-00000000000a';
const at = (days: number, hour: number) => {
  const d = new Date();
  d.setDate(d.getDate() + days);
  d.setHours(hour, 0, 0, 0);
  return d.toISOString();
};
const item = (over: Record<string, unknown>) => ({
  id: '1', classId: 'c', className: 'IELTS 6.5', mode: 'OFFLINE', startsAt: at(1, 9), endsAt: at(1, 11), room: 'P301', role: 'LEARNER', ...over,
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: ORG });
});
afterEach(() => vi.unstubAllGlobals());

describe('SchedulePage', () => {
  it('shows a room for an offline session and a link for an online one, only if it is https; the learner sees their mark', async () => {
    stubApi({
      'GET /api/me/schedule': () =>
        json(200, [
          item({ id: '1' }),
          item({ id: '2', mode: 'ONLINE', room: undefined, meetingUrl: 'https://meet.example.org/x', startsAt: at(2, 9), endsAt: at(2, 10), attendance: 'LATE' }),
          item({ id: '3', mode: 'ONLINE', room: undefined, meetingUrl: 'javascript:alert(1)', startsAt: at(3, 9), endsAt: at(3, 10), role: 'TEACHER' }),
        ]),
    });
    renderAt('/', '/', <SchedulePage />);
    const items = await screen.findAllByTestId('schedule-item');
    expect(items).toHaveLength(3);
    expect(within(items[0]!).getByText('Phòng: P301')).toBeInTheDocument();
    expect(within(items[1]!).getByRole('link', { name: 'Vào buổi học' })).toHaveAttribute('href', 'https://meet.example.org/x');
    expect(within(items[1]!).getByText('Điểm danh: Đi trễ')).toBeInTheDocument();
    expect(within(items[2]!).queryByRole('link')).toBeNull();
    expect(within(items[2]!).getByText('Giáo viên')).toBeInTheDocument();
  });

  it('says so when there is no session', async () => {
    stubApi({ 'GET /api/me/schedule': () => json(200, []) });
    renderAt('/', '/', <SchedulePage />);
    expect(await screen.findByText('Chưa có buổi học nào.')).toBeInTheDocument();
  });
});
