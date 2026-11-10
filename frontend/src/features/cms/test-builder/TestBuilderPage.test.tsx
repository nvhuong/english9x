import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import TestBuilderPage from './TestBuilderPage';
import TestListPage from './TestListPage';
import type { TestVersion } from './types';

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

const ID = '3c7e9b21-8a1f-4c5e-9b0e-6d0e9a521f3a';
const G1 = '8a1f0c5e-6b0e-4d0e-9a52-1f3a5c7e9b21';
const G2 = '5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b';
const SECTIONS = [
  { code: 'P1', skill: 'LISTENING', position: 1, itemCount: 6 },
  { code: 'P5', skill: 'READING', position: 5, itemCount: 30 },
];
const EXAMS = {
  'GET /api/catalog/exams': () =>
    json(200, [
      { code: 'TOEIC', name: 'TOEIC', variants: [], scoreSkills: ['LISTENING', 'READING'], sections: SECTIONS },
      { code: 'IELTS', name: 'IELTS', variants: ['AC', 'GT'], scoreSkills: ['LISTENING'], sections: [] },
    ]),
};
const GROUPS = {
  'GET /api/cms/question-groups': () =>
    json(200, {
      items: [
        { groupId: 'a', versionId: G1, versionNo: 1, status: 'PUBLISHED', examCode: 'TOEIC', title: 'Photos', members: 6 },
        { groupId: 'b', versionId: G2, versionNo: 1, status: 'DRAFT', examCode: 'TOEIC', title: 'Sentences', members: 30 },
      ],
      offset: 0,
      limit: 100,
    }),
};
const TEST: TestVersion = {
  versionId: '9d8c7b6a-5e4f-4a3b-8c2d-1e0f9a8b7c6d',
  testId: ID,
  versionNo: 1,
  status: 'DRAFT',
  examCode: 'TOEIC',
  variant: null,
  title: 'Mock 1',
  maxResumes: null,
  parts: [{ position: 1, sectionCode: 'P1', skill: 'LISTENING', durationSec: 2700, questions: 6, groups: [{ groupVersionId: G1, groupId: 'a', title: 'Photos', questions: 6 }] }],
  sections: [
    { sectionCode: 'P1', skill: 'LISTENING', expected: 6, actual: 6, state: 'OK' },
    { sectionCode: 'P5', skill: 'READING', expected: 30, actual: 0, state: 'MISSING' },
  ],
  warnings: [{ sectionCode: 'P5', skill: 'READING', code: 'section-missing', message: 'P5 has no questions; it needs 30' }],
  blockers: [{ sectionCode: null, skill: 'READING', code: 'skill-missing', message: 'the test has no READING questions' }],
  scoreTableOverrides: [],
};

describe('TestBuilderPage: new', () => {
  it('asks for the variant only of an exam that has some, and shows what the server refuses', async () => {
    const calls = stubApi({
      ...EXAMS,
      'POST /api/cms/tests': () => problem(422, 'test-invalid', { status: 422, errors: [{ field: 'title', code: 'invalid', message: 'is required' }] }),
    });
    renderAt('/cms/tests/new', '/cms/tests/new', <TestBuilderPage />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Kỳ thi' }));
    await userEvent.click(await screen.findByRole('option', { name: 'IELTS' }));
    await userEvent.click(await screen.findByRole('combobox', { name: 'Dạng đề' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Academic' }));
    await userEvent.click(screen.getByRole('button', { name: 'Tạo đề' }));
    expect(await screen.findByText('is required')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ examCode: 'IELTS', variant: 'AC', title: '' });
  });
});

describe('TestBuilderPage: edit', () => {
  it('shows the check against the exam, saves the structure with seconds and group ids, and says what blocks a review', async () => {
    const calls = stubApi({
      ...EXAMS,
      ...GROUPS,
      [`GET /api/cms/tests/${ID}`]: () => json(200, TEST),
      [`PUT /api/cms/tests/${ID}/structure`]: () => json(200, TEST),
      [`POST /api/cms/tests/${ID}/submit`]: () =>
        problem(422, 'test-skill-missing', { status: 422, errors: [{ field: 'READING', code: 'skill-missing', message: 'the test has no READING questions' }] }),
    });
    renderAt(`/cms/tests/${ID}`, '/cms/tests/:id', <TestBuilderPage />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Mock 1' })).toBeInTheDocument();
    const check = screen.getByRole('table', { name: 'Số câu của đề so với kỳ thi' });
    expect(within(check).getAllByRole('row')).toHaveLength(3);
    expect(within(check).getByText('Thiếu')).toBeInTheDocument();
    expect(screen.getByText(/Chưa thể gửi duyệt, thiếu kỹ năng:/)).toHaveTextContent('READING');
    expect(screen.getByLabelText('Thời gian (phút)')).toHaveValue(45);

    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(await screen.findByText('Đã lưu bản nháp.')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ title: 'Mock 1', maxResumes: null, parts: [{ sectionCode: 'P1', durationSec: 2700, groups: [G1] }] });

    await userEvent.click(screen.getByRole('button', { name: 'Gửi duyệt' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('đề cần có câu hỏi ở mọi kỹ năng');
  });

  it('adds a part and a group from the groups of the exam', async () => {
    const calls = stubApi({
      ...EXAMS,
      ...GROUPS,
      [`GET /api/cms/tests/${ID}`]: () => json(200, TEST),
      [`PUT /api/cms/tests/${ID}/structure`]: () => json(200, TEST),
    });
    renderAt(`/cms/tests/${ID}`, '/cms/tests/:id', <TestBuilderPage />);
    await screen.findByRole('heading', { level: 1, name: 'Mock 1' });
    await userEvent.click(screen.getByRole('button', { name: 'Thêm phần' }));
    const second = screen.getByRole('group', { name: 'Phần 2' });
    expect(within(second).getByRole('combobox', { name: 'Phần của kỳ thi' })).toHaveTextContent('P5');
    await userEvent.click(within(second).getByRole('combobox', { name: 'Thêm nhóm câu' }));
    // the group already in part 1 is not offered again
    expect(screen.queryByRole('option', { name: /Photos/ })).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('option', { name: /Sentences/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    await screen.findByText('Đã lưu bản nháp.');
    expect(calls.find((c) => c.method === 'PUT')!.body).toMatchObject({
      parts: [
        { sectionCode: 'P1', durationSec: 2700, groups: [G1] },
        { sectionCode: 'P5', durationSec: 1800, groups: [G2] },
      ],
    });
  });

  it('says a published test is not changed, and locks one under review', async () => {
    stubApi({ ...EXAMS, ...GROUPS, [`GET /api/cms/tests/${ID}`]: () => json(200, { ...TEST, status: 'PUBLISHED', blockers: [] }) });
    const first = renderAt(`/cms/tests/${ID}`, '/cms/tests/:id', <TestBuilderPage />);
    expect(await screen.findByText(/Lưu thay đổi sẽ tạo bản nháp mới \(phiên bản 2\)/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lưu thành phiên bản mới' })).toBeInTheDocument();
    first.unmount();
    vi.unstubAllGlobals();
    stubApi({ ...EXAMS, ...GROUPS, [`GET /api/cms/tests/${ID}`]: () => json(200, { ...TEST, status: 'IN_REVIEW' }) });
    renderAt(`/cms/tests/${ID}`, '/cms/tests/:id', <TestBuilderPage />);
    expect(await screen.findByText('Bản này đang chờ duyệt nên bị khoá.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Lưu/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thêm phần' })).not.toBeInTheDocument();
  });

  it('shows the same answer for a test that is not there and one the person may not open', async () => {
    stubApi({ ...EXAMS, [`GET /api/cms/tests/${ID}`]: () => problem(404, 'not-found', { status: 404 }) });
    renderAt(`/cms/tests/${ID}`, '/cms/tests/:id', <TestBuilderPage />);
    expect(await screen.findByText('Không tìm thấy đề')).toBeInTheDocument();
  });
});

describe('TestListPage', () => {
  it('lists the latest version of each test with its status and parts', async () => {
    stubApi({
      ...EXAMS,
      'GET /api/cms/tests': () =>
        json(200, { items: [{ testId: ID, versionId: TEST.versionId, versionNo: 2, status: 'DRAFT', examCode: 'IELTS', variant: 'AC', title: 'Cambridge 18', parts: 4 }], offset: 0, limit: 50 }),
    });
    renderAt('/cms/tests', '/cms/tests', <TestListPage />);
    expect(await screen.findByRole('link', { name: 'Cambridge 18' })).toHaveAttribute('href', `/cms/tests/${ID}`);
    expect(screen.getByText('IELTS AC')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Đề mới' })).toBeInTheDocument();
  });
});
