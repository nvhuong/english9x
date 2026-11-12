import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import ResultPage from './ResultPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ID = '11111111-2222-3333-4444-555555555555';
const question = (over: Record<string, unknown>) => ({
  questionId: 'q1', sectionCode: 'P1', skill: 'LISTENING', typeCode: 'TOEIC_P1_PHOTO', typeName: 'Photographs', scored: true, correct: true, earned: 1, max: 1,
  answer: 'A', correctAnswer: [['A']], explanation: null, transcript: null, ...over,
});
const ready = (over: Record<string, unknown> = {}) => ({
  attemptId: ID, state: 'READY', examCode: 'TOEIC', interrupted: false, submittedAt: '2026-11-12T10:00:00Z',
  skills: [
    { skill: 'LISTENING', aggregate: false, score: 400, raw: 80, rawMax: 100, label: 'OFFICIAL', source: 'AUTO' },
    { skill: 'WRITING', aggregate: false, score: 6, raw: 6, rawMax: null, label: 'PRELIMINARY', source: 'AI' },
    { skill: 'SPEAKING', aggregate: false, score: null, raw: null, rawMax: null, label: 'PENDING', source: null },
  ],
  questions: [
    question({}),
    question({ questionId: 'q2', typeCode: 'TOEIC_P5_INCOMPLETE_SENTENCE', typeName: 'Incomplete sentences', correct: false, earned: 0, answer: 'C', correctAnswer: [['D']], explanation: 'The verb needs the past tense.', transcript: 'Script of the recording.' }),
    question({ questionId: 'q3', typeName: 'Photographs', answer: null, correct: false, earned: 0, correctAnswer: [['B']] }),
  ],
  feedback: [{ skill: 'WRITING', ai: [{ questionId: 'w1', band: 6, result: { criteria: { TA: 6, CC: 5.5 }, feedback: 'Develop your ideas.' } }], teacher: null }],
  ...over,
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

describe('ResultPage', () => {
  it('labels each skill preliminary / official / pending and shows the questions with the key and the explanation', async () => {
    stubApi({ [`GET /api/results/attempts/${ID}`]: () => json(200, ready()) });
    renderAt(`/results/attempts/${ID}`, '/results/attempts/:attemptId', <ResultPage />);
    const scores = await screen.findAllByTestId('skill-score');
    expect(scores.map((s) => s.getAttribute('data-label'))).toEqual(['OFFICIAL', 'PRELIMINARY', 'PENDING']);
    expect(within(scores[0]!).getByText('Chính thức')).toBeInTheDocument();
    expect(within(scores[1]!).getByText('Sơ bộ')).toBeInTheDocument();
    expect(within(scores[2]!).getByText('Đang chờ')).toBeInTheDocument();

    const rows = screen.getAllByTestId('question-result');
    expect(rows.map((r) => r.getAttribute('data-correct'))).toEqual(['true', 'false', 'false']);
    expect(within(rows[1]!).getByText('The verb needs the past tense.')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('Script of the recording.')).toBeInTheDocument();
    expect(within(rows[1]!).getByText(/D$/)).toBeInTheDocument();
    expect(within(rows[2]!).getByText('Không trả lời')).toBeInTheDocument();
    expect(screen.queryByTestId('interrupted-warning')).toBeNull();

    // the AI feedback of Writing: criteria and comment
    const fb = screen.getByTestId('feedback');
    expect(within(fb).getByText('Develop your ideas.')).toBeInTheDocument();
    expect(within(fb).getByText('TA')).toBeInTheDocument();
  });

  it('can show only the wrong or blank answers', async () => {
    stubApi({ [`GET /api/results/attempts/${ID}`]: () => json(200, ready()) });
    renderAt(`/results/attempts/${ID}`, '/results/attempts/:attemptId', <ResultPage />);
    await screen.findAllByTestId('question-result');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Sai hoặc bỏ trống' }));
    expect(screen.getAllByTestId('question-result')).toHaveLength(2);
  });

  it('warns when the attempt was interrupted', async () => {
    stubApi({ [`GET /api/results/attempts/${ID}`]: () => json(200, ready({ interrupted: true })) });
    renderAt(`/results/attempts/${ID}`, '/results/attempts/:attemptId', <ResultPage />);
    expect(await screen.findByTestId('interrupted-warning')).toBeInTheDocument();
  });

  it('while the attempt is still being scored there is no score and no answer on the page', async () => {
    stubApi({ [`GET /api/results/attempts/${ID}`]: () => json(200, { attemptId: ID, state: 'SCORING', examCode: 'TOEIC', interrupted: false, submittedAt: null, skills: [], questions: [], feedback: [] }) });
    renderAt(`/results/attempts/${ID}`, '/results/attempts/:attemptId', <ResultPage />);
    expect(await screen.findByTestId('scoring')).toBeInTheDocument();
    expect(screen.queryByTestId('question-result')).toBeNull();
    expect(screen.queryByTestId('skill-score')).toBeNull();
  });

  it('says so for an attempt that is not submitted (409) and for one that is not mine (404)', async () => {
    stubApi({ [`GET /api/results/attempts/${ID}`]: () => problem(409, 'results-attempt-not-submitted') });
    const first = renderAt(`/results/attempts/${ID}`, '/results/attempts/:attemptId', <ResultPage />);
    expect(await screen.findByText('Kết quả sẽ có sau khi bạn nộp bài.')).toBeInTheDocument();
    first.unmount();
    stubApi({ [`GET /api/results/attempts/${ID}`]: () => problem(404, 'results-not-found') });
    renderAt(`/results/attempts/${ID}`, '/results/attempts/:attemptId', <ResultPage />);
    expect(await screen.findByText('Không có kết quả này, hoặc đây không phải bài của bạn.')).toBeInTheDocument();
  });
});
