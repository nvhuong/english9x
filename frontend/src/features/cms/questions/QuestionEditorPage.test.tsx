import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import QuestionEditorPage from './QuestionEditorPage';
import QuestionListPage from './QuestionListPage';
import { QUESTION, TOEIC_P3 } from './testUtils';

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

const TYPES = { 'GET /api/cms/question-types': () => json(200, [TOEIC_P3]) };
const ID = QUESTION.questionId;

describe('QuestionEditorPage: new', () => {
  it('asks for a type, shows its form, and lists the server\'s reasons by JSON path when it refuses the question', async () => {
    const calls = stubApi({
      ...TYPES,
      'POST /api/cms/questions': () =>
        problem(422, 'question-invalid', {
          status: 422,
          errors: [
            { field: 'content.options', row: null, code: 'invalid', message: 'must have at least 4 items' },
            { field: 'answerKey.slots', row: null, code: 'invalid', message: 'is required' },
          ],
        }),
    });
    renderAt('/cms/questions/new', '/cms/questions/new', <QuestionEditorPage />);
    expect(await screen.findByRole('heading', { name: 'Câu hỏi mới' })).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('combobox', { name: 'Dạng câu' }));
    await userEvent.click(await screen.findByRole('option', { name: 'TOEIC · TOEIC_P3_CONVERSATION' }));
    await userEvent.type(await screen.findByLabelText(/^Câu hỏi/), 'Where?');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('content.options')).toBeInTheDocument();
    expect(within(alert).getByText(/must have at least 4 items/)).toBeInTheDocument();
    expect(within(alert).getByText('answerKey.slots')).toBeInTheDocument();
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toMatchObject({ typeCode: 'TOEIC_P3_CONVERSATION', content: { stem: 'Where?' }, points: 1 });
    expect(post.body).not.toHaveProperty('explanation');
  });

  it('says so when the person is an author of nothing in this context', async () => {
    stubApi({ 'GET /api/cms/question-types': () => json(403, {}) });
    renderAt('/cms/questions/new', '/cms/questions/new', <QuestionEditorPage />);
    expect(await screen.findByText('Chưa thể soạn câu hỏi')).toBeInTheDocument();
  });
});

describe('QuestionEditorPage: edit', () => {
  it('saves a draft in place and submits it for review', async () => {
    const calls = stubApi({
      ...TYPES,
      [`GET /api/cms/questions/${ID}`]: () => json(200, QUESTION),
      [`PUT /api/cms/questions/${ID}`]: () => json(200, QUESTION),
      [`POST /api/cms/questions/${ID}/submit`]: () => json(202, { reviewId: '7d6e5f40-1b2c-4d3e-8f90-a1b2c3d4e5f6' }),
    });
    renderAt(`/cms/questions/${ID}`, '/cms/questions/:id', <QuestionEditorPage />);
    expect(await screen.findByText('Bản nháp')).toBeInTheDocument();
    expect(screen.getByText('Phiên bản 1')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }));
    expect(await screen.findByText('Đã lưu bản nháp.')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'PUT' && (c.body as { typeCode: string }).typeCode === 'TOEIC_P3_CONVERSATION')).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: 'Gửi duyệt' }));
    expect(await screen.findByText(/Đã gửi duyệt/)).toBeInTheDocument();
  });

  it('explains that a published version is not changed: saving makes a new draft', async () => {
    stubApi({
      ...TYPES,
      [`GET /api/cms/questions/${ID}`]: () => json(200, { ...QUESTION, status: 'PUBLISHED' }),
      [`PUT /api/cms/questions/${ID}`]: () => json(201, { ...QUESTION, status: 'DRAFT', versionNo: 2, versionId: '9b2c8d10-aaaa-4bbb-8ccc-0123456789ab' }),
    });
    renderAt(`/cms/questions/${ID}`, '/cms/questions/:id', <QuestionEditorPage />);
    expect(await screen.findByText(/Lưu thay đổi sẽ tạo bản nháp mới \(phiên bản 2\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Gửi duyệt' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Lưu thành bản nháp mới' }));
    expect(await screen.findByText(/Đã tạo bản nháp mới, phiên bản 2/)).toBeInTheDocument();
  });

  it('locks a version under review', async () => {
    stubApi({ ...TYPES, [`GET /api/cms/questions/${ID}`]: () => json(200, { ...QUESTION, status: 'IN_REVIEW' }) });
    renderAt(`/cms/questions/${ID}`, '/cms/questions/:id', <QuestionEditorPage />);
    expect(await screen.findByText('Bản này đang chờ duyệt nên bị khoá.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Lưu/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/^Câu hỏi/)).toBeDisabled();
  });

  it('shows the same answer for a question that is not there and one the person may not open', async () => {
    stubApi({ ...TYPES, [`GET /api/cms/questions/${ID}`]: () => problem(404, 'not-found', { status: 404 }) });
    renderAt(`/cms/questions/${ID}`, '/cms/questions/:id', <QuestionEditorPage />);
    expect(await screen.findByText('Không tìm thấy câu hỏi')).toBeInTheDocument();
  });
});

describe('QuestionListPage', () => {
  it('lists the latest version of each question, with its status, and filters by status through the server', async () => {
    const calls = stubApi({
      ...TYPES,
      'GET /api/cms/questions': () =>
        json(200, {
          items: [{ questionId: ID, versionId: QUESTION.versionId, versionNo: 2, status: 'DRAFT', examCode: 'TOEIC', typeCode: 'TOEIC_P3_CONVERSATION', title: 'What are the speakers discussing?', points: 1 }],
          total: 1,
          offset: 0,
          limit: 50,
        }),
    });
    renderAt('/cms/questions', '/cms/questions', <QuestionListPage />);
    const row = await screen.findByRole('link', { name: 'What are the speakers discussing?' });
    expect(row).toHaveAttribute('href', `/cms/questions/${ID}`);
    expect(screen.getByText('Bản nháp')).toBeInTheDocument();
    expect(screen.getByText('1–1 trên 1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Câu hỏi mới' })).toHaveAttribute('href', '/cms/questions/new');
    await userEvent.click(screen.getByRole('combobox', { name: 'Trạng thái' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Đã xuất bản' }));
    await vi.waitFor(() => expect(calls.some((c) => c.path === '/api/cms/questions' && c.search.includes('status=PUBLISHED'))).toBe(true));
  });
});
