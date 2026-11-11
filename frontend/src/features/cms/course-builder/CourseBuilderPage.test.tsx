import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import CourseBuilderPage from './CourseBuilderPage';

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

const LESSON = '8a1f0c5e-6b0e-4d0e-9a52-1f3a5c7e9b21';
const TEST = '5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b';
const API = {
  'GET /api/catalog/exams': () => json(200, [{ code: 'TOEIC', name: 'TOEIC', variants: [], sections: [] }]),
  'GET /api/cms/strategy-lessons': () => json(200, { items: [{ lessonId: 'a', versionId: LESSON, versionNo: 1, status: 'PUBLISHED', examCode: 'TOEIC', title: 'Verbs' }], offset: 0, limit: 50 }),
  'GET /api/cms/tests': () => json(200, { items: [{ testId: 'b', versionId: TEST, versionNo: 1, status: 'PUBLISHED', examCode: 'TOEIC', title: 'Test one', parts: 2 }], offset: 0, limit: 50 }),
};

describe('CourseBuilderPage: new', () => {
  it('builds a course from the lessons and tests of the exam and sends the structure; the server refuses by path', async () => {
    const calls = stubApi({
      ...API,
      'POST /api/cms/courses': () =>
        problem(422, 'course-invalid', { status: 422, errors: [{ field: 'code', code: 'invalid', message: 'must be exactly 6 characters' }] }),
    });
    renderAt('/cms/courses/new', '/cms/courses/new', <CourseBuilderPage />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Kỳ thi' }));
    await userEvent.click(await screen.findByRole('option', { name: 'TOEIC' }));
    await userEvent.type(screen.getByLabelText('Mã khoá học'), 'ab12');
    expect(screen.getByLabelText('Mã khoá học')).toHaveValue('AB12');
    await userEvent.type(screen.getByLabelText('Tên khoá học'), 'Part 5');
    await userEvent.click(screen.getByRole('button', { name: 'Thêm chương' }));
    const chapter = screen.getByRole('group', { name: 'Chương 1' });
    await userEvent.type(within(chapter).getByLabelText('Tên chương'), 'Verbs');
    await userEvent.click(within(chapter).getByRole('combobox', { name: 'Thêm bài học hoặc đề' }));
    await userEvent.click(await screen.findByRole('option', { name: /Verbs/ }));
    await userEvent.click(within(chapter).getByRole('combobox', { name: 'Thêm bài học hoặc đề' }));
    await userEvent.click(await screen.findByRole('option', { name: /Test one/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Tạo khoá học' }));
    expect(await screen.findByText('must be exactly 6 characters')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      examCode: 'TOEIC',
      code: 'AB12',
      title: 'Part 5',
      description: '',
      authorDisplay: '',
      unlockRule: 'COMPLETION',
      chapters: [
        {
          title: 'Verbs',
          passThreshold: 0.7,
          lessons: [
            { kind: 'LESSON', refId: LESSON, maxAttempts: null },
            { kind: 'CHAPTER_TEST', refId: TEST, maxAttempts: null },
          ],
        },
      ],
    });
  });
});

describe('CourseBuilderPage: order of filling', () => {
  it('keeps the exam when the other fields and the unlock rule are filled afterwards', async () => {
    stubApi({ ...API });
    renderAt('/cms/courses/new', '/cms/courses/new', <CourseBuilderPage />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Kỳ thi' }));
    await userEvent.click(await screen.findByRole('option', { name: 'TOEIC' }));
    await userEvent.type(screen.getByLabelText('Tên khoá học'), 'Part 5');
    await userEvent.type(screen.getByLabelText('Mô tả'), 'Grammar');
    await userEvent.click(screen.getByRole('combobox', { name: 'Luật mở khoá' }));
    await userEvent.click(await screen.findByRole('option', { name: /^Nghiêm ngặt/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Thêm chương' }));
    const chapter = screen.getByRole('group', { name: 'Chương 1' });
    expect(within(chapter).getByRole('combobox', { name: 'Thêm bài học hoặc đề' })).not.toHaveTextContent('Chọn kỳ thi trước');
    expect(screen.getByRole('combobox', { name: 'Kỳ thi' })).toHaveTextContent('TOEIC');
  });
});
