import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { json, me, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import MyAssignmentsPage from './MyAssignmentsPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ORG = 'aaaaaaaa-0000-0000-0000-00000000000a';
const row = (over: Record<string, unknown>) => ({
  id: '1', classId: 'c', className: 'IELTS 6.5', testVersionId: 'tv1', title: 'Mock test 1', dueAt: '2026-11-20T10:00:00Z', status: 'PENDING', ...over,
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: ORG });
});
afterEach(() => vi.unstubAllGlobals());

describe('MyAssignmentsPage', () => {
  it('shows each assignment with its status, and a link only while there is something left to do', async () => {
    stubApi({
      'GET /api/me/assignments': () => json(200, [row({ id: '1' }), row({ id: '2', title: 'Mock test 2', status: 'LATE' }), row({ id: '3', title: 'Mock test 3', status: 'MISSING' })]),
    });
    renderAt('/', '/', <MyAssignmentsPage />);
    const items = await screen.findAllByTestId('my-assignment');
    expect(items).toHaveLength(3);
    expect(within(items[0]!).getByRole('link', { name: 'Mock test 1' })).toHaveAttribute('href', '/exam/start/tv1');
    expect(within(items[0]!).getByText('Chưa đến hạn')).toBeInTheDocument();
    expect(within(items[1]!).queryByRole('link')).toBeNull();
    expect(within(items[1]!).getByText('Nộp trễ')).toBeInTheDocument();
    expect(within(items[2]!).getByText('Chưa nộp')).toBeInTheDocument();
  });

  it('says so when nothing was assigned', async () => {
    stubApi({ 'GET /api/me/assignments': () => json(200, []) });
    renderAt('/', '/', <MyAssignmentsPage />);
    expect(await screen.findByText('Chưa có bài nào được giao.')).toBeInTheDocument();
  });
});
