import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import GradingPage from './GradingPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const detail = {
  id: 'i1',
  examCode: 'IELTS',
  state: 'CLAIMED',
  claimDueAt: '2026-11-16T00:00:00Z',
  skills: [
    {
      skill: 'WRITING',
      aiBand: 6.5,
      review: null,
      criteria: [
        { code: 'TA', name: 'Task', min: 0, max: 9, step: 0.5 },
        { code: 'CC', name: 'Coherence', min: 0, max: 9, step: 0.5 },
      ],
      tasks: [{ questionId: 'q1', typeCode: 'IELTS_W_TASK2', prompt: 'Discuss both views.', text: 'My essay about cities.', hasRecording: false, ai: { band: 6.5, result: { TA: 6, feedback: 'Fine.' } } }],
    },
  ],
};

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
});
afterEach(() => vi.unstubAllGlobals());

describe('grading screen', () => {
  it('shows the work and the AI result, and sends the criteria scores with the comment', async () => {
    const calls = stubApi({
      'GET /api/teacher/queue/i1': () => json(200, detail),
      'POST /api/teacher/queue/i1/review': () => json(200, { skill: 'WRITING', band: 7, done: true, remaining: [] }),
    });
    renderAt('/cms/grading-queue/i1', '/cms/grading-queue/:id', <GradingPage />);
    expect(await screen.findByText('My essay about cities.')).toBeInTheDocument();
    expect(screen.getByText('Discuss both views.')).toBeInTheDocument();
    expect(screen.getByText('AI: 6.5', { selector: 'span' })).toBeInTheDocument();
    const submit = screen.getByRole('button', { name: 'Lưu điểm kỹ năng này' });
    expect(submit).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Task (TA)'), '7');
    expect(submit).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Coherence (CC)'), '7');
    await userEvent.type(screen.getByLabelText('Nhận xét cho học viên'), 'Well organised.');
    await userEvent.click(submit);
    expect(await screen.findByText(/Đã chấm xong/)).toBeInTheDocument();
    const post = calls.find((c) => c.method === 'POST' && c.path === '/api/teacher/queue/i1/review');
    expect(post?.body).toEqual({ skill: 'WRITING', criteria: { TA: 7, CC: 7 }, comment: 'Well organised.' });
  });

  it('tells a teacher who does not hold the submission to claim it first', async () => {
    stubApi({ 'GET /api/teacher/queue/i1': () => problem(409, 'not-claimed-by-you') });
    renderAt('/cms/grading-queue/i1', '/cms/grading-queue/:id', <GradingPage />);
    expect(await screen.findByText(/chưa nhận bài này/)).toBeInTheDocument();
  });
});
