import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import QueuePage from './QueuePage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const open = { id: 'i1', examCode: 'IELTS', state: 'OPEN', mine: false, createdAt: '2026-11-15T01:00:00Z', claimDueAt: null };
const mine = { id: 'i2', examCode: 'IELTS', state: 'CLAIMED', mine: true, createdAt: '2026-11-15T00:00:00Z', claimDueAt: '2026-11-16T00:00:00Z' };

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
});
afterEach(() => vi.unstubAllGlobals());

describe('grading queue', () => {
  it('lists the open submissions with a claim button and the ones I hold with a grading link', async () => {
    stubApi({ 'GET /api/teacher/queue': () => json(200, [mine, open]) });
    renderAt('/cms/grading-queue', '/cms/grading-queue', <QueuePage />);
    const items = await screen.findAllByTestId('queue-item');
    expect(items).toHaveLength(2);
    expect(screen.getByRole('link', { name: 'Chấm bài' })).toHaveAttribute('href', '/cms/grading-queue/i2');
    expect(screen.getByRole('button', { name: 'Nhận bài' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Trả lại hàng chờ' })).toBeInTheDocument();
  });

  it('claims a submission and reads the queue again', async () => {
    let claimed = false;
    const calls = stubApi({
      'GET /api/teacher/queue': () => json(200, claimed ? [{ ...open, state: 'CLAIMED', mine: true, claimDueAt: '2026-11-16T01:00:00Z' }] : [open]),
      'POST /api/teacher/queue/i1/claim': () => {
        claimed = true;
        return json(200, { ...open, state: 'CLAIMED', mine: true });
      },
    });
    renderAt('/cms/grading-queue', '/cms/grading-queue', <QueuePage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Nhận bài' }));
    expect(await screen.findByRole('link', { name: 'Chấm bài' })).toHaveAttribute('href', '/cms/grading-queue/i1');
    expect(calls.filter((c) => c.method === 'GET' && c.path === '/api/teacher/queue').length).toBeGreaterThanOrEqual(2);
  });

  it('says so when somebody else was faster, and the list is read again without the submission', async () => {
    let taken = false;
    stubApi({
      'GET /api/teacher/queue': () => json(200, taken ? [] : [open]),
      'POST /api/teacher/queue/i1/claim': () => {
        taken = true;
        return problem(409, 'already-claimed');
      },
    });
    renderAt('/cms/grading-queue', '/cms/grading-queue', <QueuePage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Nhận bài' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('đã có giáo viên khác nhận');
    await waitFor(() => expect(screen.queryByTestId('queue-item')).not.toBeInTheDocument());
  });

  it('shows an empty state', async () => {
    stubApi({ 'GET /api/teacher/queue': () => json(200, []) });
    renderAt('/cms/grading-queue', '/cms/grading-queue', <QueuePage />);
    expect(await screen.findByText('Chưa có bài nào chờ chấm')).toBeInTheDocument();
  });
});
