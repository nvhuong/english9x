import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import { TOEIC_P3 } from '../questions/testUtils';
import LessonEditorPage from './LessonEditorPage';
import LessonListPage from './LessonListPage';
import type { LessonVersion } from './types';

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
const LESSON: LessonVersion = {
  versionId: '8a1f0c5e-6b0e-4d0e-9a52-1f3a5c7e9b21',
  lessonId: ID,
  versionNo: 1,
  status: 'DRAFT',
  examCode: 'TOEIC',
  title: 'Part 3 method',
  questionTypeCodes: ['TOEIC_P3_CONVERSATION'],
  blocks: [
    { type: 'paragraph', text: 'Read the questions first.' },
    { type: 'callout', kind: 'trap', text: 'Do not pick the first word you hear.' },
  ],
} as LessonVersion;
const BASE = {
  'GET /api/cms/question-types': () => json(200, [TOEIC_P3]),
  'GET /api/cms/questions': () => json(200, { items: [], total: 0, offset: 0, limit: 50 }),
};

describe('LessonEditorPage: new', () => {
  it('builds a lesson from blocks, sends them in order, and shows the server\'s reasons next to the block they name', async () => {
    const calls = stubApi({
      ...BASE,
      'POST /api/cms/strategy-lessons': () =>
        problem(422, 'strategy-lesson-invalid', {
          status: 422,
          errors: [{ field: 'blocks[0].url', row: 0, code: 'invalid', message: 'is not an address of an allowed video host' }],
        }),
    });
    renderAt('/cms/strategy-lessons/new', '/cms/strategy-lessons/new', <LessonEditorPage />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Kỳ thi' }));
    await userEvent.click(await screen.findByRole('option', { name: 'TOEIC' }));
    await userEvent.type(await screen.findByLabelText(/^Tên bài/), 'Method');
    await userEvent.click(screen.getByRole('checkbox', { name: 'TOEIC_P3_CONVERSATION' }));
    await userEvent.click(screen.getByRole('combobox', { name: 'Thêm khối' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Video YouTube/Vimeo' }));
    const block = screen.getByRole('region', { name: 'Khối 1: Video YouTube/Vimeo' });
    await userEvent.type(within(block).getByLabelText('Địa chỉ video'), 'https://evil.example/x');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));

    const alert = await screen.findAllByRole('alert');
    expect(alert.some((a) => /blocks\[0\]\.url/.test(a.textContent ?? ''))).toBe(true);
    expect(within(block).getByText('is not an address of an allowed video host')).toBeInTheDocument();
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toEqual({
      examCode: 'TOEIC',
      title: 'Method',
      questionTypeCodes: ['TOEIC_P3_CONVERSATION'],
      blocks: [{ type: 'video-embed', provider: 'youtube', url: 'https://evil.example/x' }],
    });
  });

  it('adds, moves and removes blocks', async () => {
    stubApi({ ...BASE });
    renderAt('/cms/strategy-lessons/new', '/cms/strategy-lessons/new', <LessonEditorPage />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Kỳ thi' }));
    await userEvent.click(await screen.findByRole('option', { name: 'TOEIC' }));
    // the same kind twice in a row is two blocks (a select that remembered its last choice would ignore the second)
    for (const name of ['Đoạn văn', 'Đoạn văn', 'Tiêu đề']) {
      await userEvent.click(await screen.findByRole('combobox', { name: 'Thêm khối' }));
      await userEvent.click(await screen.findByRole('option', { name }));
    }
    const order = () => screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
    expect(order()).toEqual(['1. Đoạn văn', '2. Đoạn văn', '3. Tiêu đề']);
    await userEvent.click(within(screen.getByRole('region', { name: 'Khối 3: Tiêu đề' })).getByRole('button', { name: 'Lên' }));
    expect(order()).toEqual(['1. Đoạn văn', '2. Tiêu đề', '3. Đoạn văn']);
    await userEvent.click(within(screen.getByRole('region', { name: 'Khối 2: Tiêu đề' })).getByRole('button', { name: 'Xoá khối' }));
    expect(order()).toEqual(['1. Đoạn văn', '2. Đoạn văn']);
  });
});

describe('LessonEditorPage: edit', () => {
  it('saves a draft in place and submits it', async () => {
    const calls = stubApi({
      ...BASE,
      [`GET /api/cms/strategy-lessons/${ID}`]: () => json(200, LESSON),
      [`PUT /api/cms/strategy-lessons/${ID}`]: () => json(200, LESSON),
      [`POST /api/cms/strategy-lessons/${ID}/submit`]: () => json(202, { reviewId: '7d6e5f40-1b2c-4d3e-8f90-a1b2c3d4e5f6' }),
    });
    renderAt(`/cms/strategy-lessons/${ID}`, '/cms/strategy-lessons/:id', <LessonEditorPage />);
    expect(await screen.findByText('Bản nháp')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Tên bài/)).toHaveValue('Part 3 method');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(await screen.findByText('Đã lưu bản nháp.')).toBeInTheDocument();
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.body).toMatchObject({ examCode: 'TOEIC', title: 'Part 3 method', blocks: LESSON.blocks });
    await userEvent.click(screen.getByRole('button', { name: 'Gửi duyệt' }));
    expect(await screen.findByText(/Đã gửi duyệt/)).toBeInTheDocument();
  });

  it('explains that a published version is not changed, and locks one under review', async () => {
    stubApi({ ...BASE, [`GET /api/cms/strategy-lessons/${ID}`]: () => json(200, { ...LESSON, status: 'PUBLISHED' }) });
    const first = renderAt(`/cms/strategy-lessons/${ID}`, '/cms/strategy-lessons/:id', <LessonEditorPage />);
    expect(await screen.findByText(/Lưu thay đổi sẽ tạo bản nháp mới \(phiên bản 2\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Gửi duyệt' })).not.toBeInTheDocument();
    first.unmount();
    vi.unstubAllGlobals();
    stubApi({ ...BASE, [`GET /api/cms/strategy-lessons/${ID}`]: () => json(200, { ...LESSON, status: 'IN_REVIEW' }) });
    renderAt(`/cms/strategy-lessons/${ID}`, '/cms/strategy-lessons/:id', <LessonEditorPage />);
    expect(await screen.findByText('Bản này đang chờ duyệt nên bị khoá.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Lưu/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/^Tên bài/)).toBeDisabled();
  });

  it('shows the same answer for a lesson that is not there and one the person may not open', async () => {
    stubApi({ ...BASE, [`GET /api/cms/strategy-lessons/${ID}`]: () => problem(404, 'not-found', { status: 404 }) });
    renderAt(`/cms/strategy-lessons/${ID}`, '/cms/strategy-lessons/:id', <LessonEditorPage />);
    expect(await screen.findByText('Không tìm thấy bài')).toBeInTheDocument();
  });
});

describe('LessonListPage', () => {
  it('lists the latest version of each lesson with its status', async () => {
    stubApi({
      ...BASE,
      'GET /api/cms/strategy-lessons': () =>
        json(200, { items: [{ lessonId: ID, versionId: LESSON.versionId, versionNo: 2, status: 'DRAFT', examCode: 'TOEIC', title: 'Part 3 method' }], total: 1, offset: 0, limit: 50 }),
    });
    renderAt('/cms/strategy-lessons', '/cms/strategy-lessons', <LessonListPage />);
    expect(await screen.findByRole('link', { name: 'Part 3 method' })).toHaveAttribute('href', `/cms/strategy-lessons/${ID}`);
    expect(screen.getByText('Bản nháp')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Bài mới' })).toBeInTheDocument();
  });
});
