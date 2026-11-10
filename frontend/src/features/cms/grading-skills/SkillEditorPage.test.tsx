import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import { TOEIC_P3 } from '../questions/testUtils';
import { DEFAULT_TEMPLATE } from './model';
import SkillEditorPage from './SkillEditorPage';
import SkillListPage from './SkillListPage';
import type { SkillVersion } from './types';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'IELTS' }] });
});
afterEach(() => {
  vi.unstubAllGlobals();
  useRoleStore.setState({ grants: null });
});

const ID = '3c7e9b21-8a1f-4c5e-9b0e-6d0e9a521f3a';
const ESSAY = { ...TOEIC_P3, code: 'IELTS_W_TASK2', examCode: 'IELTS', section: 'WRITING', kind: 'ESSAY' };
const BASE = { 'GET /api/cms/question-types': () => json(200, [TOEIC_P3, ESSAY]) };
const SKILL: SkillVersion = {
  versionId: '8a1f0c5e-6b0e-4d0e-9a52-1f3a5c7e9b21',
  skillId: ID,
  versionNo: 1,
  status: 'DRAFT',
  examCode: 'IELTS',
  questionTypeCode: 'IELTS_W_TASK2',
  rubric: 'Task response: ...',
  criteria: [{ code: 'task_response', name: 'Task response', min: 0, max: 9, step: 0.5 }],
  promptTemplate: DEFAULT_TEMPLATE,
  samples: [{ band: 6.5, text: 'An essay', commentary: 'Clear.' }],
  resultSchema: { type: 'object', properties: { task_response: { type: 'number' } } },
} as unknown as SkillVersion;

describe('SkillEditorPage: new', () => {
  it('offers only Writing and Speaking types, sends the skill, and shows the server\'s reasons next to the fields they name', async () => {
    const calls = stubApi({
      ...BASE,
      'POST /api/cms/grading-skills': () =>
        problem(422, 'grading-skill-invalid', {
          status: 422,
          errors: [
            { field: 'resultSchema.properties.task_response', code: 'invalid', message: 'must declare a property for the criterion task_response' },
            { field: 'criteria[0].step', row: 0, code: 'invalid', message: 'must divide the range min..max a whole number of times' },
          ],
        }),
    });
    renderAt('/cms/grading-skills/new', '/cms/grading-skills/new', <SkillEditorPage />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Dạng câu' }));
    expect(screen.queryByRole('option', { name: /TOEIC_P3_CONVERSATION/ })).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('option', { name: 'IELTS · IELTS_W_TASK2' }));
    await userEvent.type(await screen.findByLabelText(/^Thang chấm/), 'Band descriptors');
    const criterion = screen.getByRole('group', { name: 'Tiêu chí 1' });
    await userEvent.type(within(criterion).getByLabelText('Mã tiêu chí'), 'task_response');
    await userEvent.type(within(criterion).getByLabelText('Tên tiêu chí'), 'Task response');
    await userEvent.clear(within(criterion).getByLabelText('Bước nhảy'));
    await userEvent.type(within(criterion).getByLabelText('Bước nhảy'), '0.7');
    await userEvent.click(screen.getByRole('button', { name: 'Tạo từ các tiêu chí' }));
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('resultSchema.properties.task_response');
    expect(within(criterion).getByText(/must divide the range/)).toBeInTheDocument();
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toMatchObject({
      questionTypeCode: 'IELTS_W_TASK2',
      rubric: 'Band descriptors',
      criteria: [{ code: 'task_response', name: 'Task response', min: 0, max: 9, step: 0.7 }],
      promptTemplate: DEFAULT_TEMPLATE,
      samples: [],
    });
    expect((post.body as { resultSchema: { properties: object } }).resultSchema.properties).toHaveProperty('task_response');
  });

  it('does not send a result schema it cannot read, and says so next to the field', async () => {
    const calls = stubApi({ ...BASE });
    renderAt('/cms/grading-skills/new', '/cms/grading-skills/new', <SkillEditorPage />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Dạng câu' }));
    await userEvent.click(await screen.findByRole('option', { name: 'IELTS · IELTS_W_TASK2' }));
    await userEvent.type(await screen.findByLabelText(/^JSON Schema/), '{{"type": ');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Không đọc được JSON Schema');
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});

describe('SkillEditorPage: edit', () => {
  it('saves a draft in place, submits it, and a dry run says the AI grader is not built yet', async () => {
    const calls = stubApi({
      ...BASE,
      [`GET /api/cms/grading-skills/${ID}`]: () => json(200, SKILL),
      [`PUT /api/cms/grading-skills/${ID}`]: () => json(200, SKILL),
      [`POST /api/cms/grading-skills/${ID}/submit`]: () => json(202, { reviewId: '7d6e5f40-1b2c-4d3e-8f90-a1b2c3d4e5f6' }),
      [`POST /api/cms/grading-skills/${ID}/dry-run`]: () => problem(501, 'dry-run-unavailable', { status: 501 }),
    });
    renderAt(`/cms/grading-skills/${ID}`, '/cms/grading-skills/:id', <SkillEditorPage />);
    expect(await screen.findByText('Bản nháp')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Thang chấm/)).toHaveValue('Task response: ...');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(await screen.findByText('Đã lưu bản nháp.')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'PUT')!.body).toMatchObject({ questionTypeCode: 'IELTS_W_TASK2', criteria: SKILL.criteria, samples: SKILL.samples, resultSchema: SKILL.resultSchema });
    await userEvent.click(screen.getByRole('button', { name: 'Gửi duyệt' }));
    expect(await screen.findByText(/Đã gửi duyệt/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Bài làm mẫu để chạy thử'), 'Some people believe...');
    await userEvent.click(screen.getByRole('button', { name: 'Chạy thử' }));
    expect(await screen.findByText(/Chạy thử cần bộ chấm AI, chưa có/)).toBeInTheDocument();
    expect(calls.find((c) => c.path.endsWith('/dry-run'))!.body).toEqual({ response: 'Some people believe...' });
  });

  it('explains that a published version is not changed, and locks one under review', async () => {
    stubApi({ ...BASE, [`GET /api/cms/grading-skills/${ID}`]: () => json(200, { ...SKILL, status: 'PUBLISHED' }) });
    const first = renderAt(`/cms/grading-skills/${ID}`, '/cms/grading-skills/:id', <SkillEditorPage />);
    expect(await screen.findByText(/Lưu thay đổi sẽ tạo bản nháp mới \(phiên bản 2\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Gửi duyệt' })).not.toBeInTheDocument();
    first.unmount();
    vi.unstubAllGlobals();
    stubApi({ ...BASE, [`GET /api/cms/grading-skills/${ID}`]: () => json(200, { ...SKILL, status: 'IN_REVIEW' }) });
    renderAt(`/cms/grading-skills/${ID}`, '/cms/grading-skills/:id', <SkillEditorPage />);
    expect(await screen.findByText('Bản này đang chờ duyệt nên bị khoá.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Lưu/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/^Thang chấm/)).toBeDisabled();
  });

  it('shows the same answer for a skill that is not there and one the person may not open', async () => {
    stubApi({ ...BASE, [`GET /api/cms/grading-skills/${ID}`]: () => problem(404, 'not-found', { status: 404 }) });
    renderAt(`/cms/grading-skills/${ID}`, '/cms/grading-skills/:id', <SkillEditorPage />);
    expect(await screen.findByText('Không tìm thấy skill')).toBeInTheDocument();
  });
});

describe('SkillListPage', () => {
  it('lists the latest version of each skill with its status', async () => {
    stubApi({
      ...BASE,
      'GET /api/cms/grading-skills': () =>
        json(200, { items: [{ skillId: ID, versionId: SKILL.versionId, versionNo: 2, status: 'DRAFT', examCode: 'IELTS', questionTypeCode: 'IELTS_W_TASK2' }], total: 1, offset: 0, limit: 50 }),
    });
    renderAt('/cms/grading-skills', '/cms/grading-skills', <SkillListPage />);
    expect(await screen.findByRole('link', { name: 'IELTS_W_TASK2' })).toHaveAttribute('href', `/cms/grading-skills/${ID}`);
    expect(screen.getByText('Bản nháp')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Skill mới' })).toBeInTheDocument();
  });
});
