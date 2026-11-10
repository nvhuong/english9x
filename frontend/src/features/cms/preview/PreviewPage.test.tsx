import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import { QUESTION, TOEIC_P3 } from '../questions/testUtils';
import PreviewPage from './PreviewPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'TOEIC' }] });
});
afterEach(() => {
  vi.unstubAllGlobals();
  useRoleStore.setState({ grants: null });
});

const ID = QUESTION.questionId;
const TYPES = { 'GET /api/cms/question-types': () => json(200, [TOEIC_P3]) };

describe('Preview.neverRendersAnswerKey (the page, with the server\'s real answer)', () => {
  it('shows the question as the learner sees it and not one word of what the server sent for the solution side', async () => {
    stubApi({
      ...TYPES,
      [`GET /api/cms/questions/${ID}`]: () =>
        json(200, { ...QUESTION, answerKey: { slots: [['KEY-B']], transcript: 'TRANSCRIPT-TEXT' }, explanation: 'EXPLANATION-TEXT' }),
    });
    const { container } = renderAt(`/cms/questions/${ID}/preview`, '/cms/questions/:id/preview', <PreviewPage />);
    expect(await screen.findByRole('heading', { name: 'Xem trước như học viên' })).toBeInTheDocument();
    expect(await screen.findByText('What are the speakers discussing?')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /two/ })).toBeInTheDocument();
    for (const secret of ['KEY-B', 'TRANSCRIPT-TEXT', 'EXPLANATION-TEXT', 'Because.', 'Script of the recording']) {
      expect(container.textContent, secret).not.toContain(secret);
    }
    expect(screen.getByRole('link', { name: 'Về câu hỏi' })).toHaveAttribute('href', `/cms/questions/${ID}`);
  });

  it('says so, without guessing, for a question that is not there or not the person\'s to open', async () => {
    stubApi({ ...TYPES, [`GET /api/cms/questions/${ID}`]: () => problem(404, 'not-found', { status: 404 }) });
    renderAt(`/cms/questions/${ID}/preview`, '/cms/questions/:id/preview', <PreviewPage />);
    expect(await screen.findByText('Không tìm thấy câu hỏi')).toBeInTheDocument();
  });
});
