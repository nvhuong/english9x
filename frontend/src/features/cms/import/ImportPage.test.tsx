import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import { FakeXhr, installFakeXhr } from '../media/testUtils';
import ImportPage from './ImportPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const JOB = '0b8e7f3a-4c1d-4e2b-9a55-3f1d2c4b6a70';
const TYPES = [
  { code: 'TOEIC_P5_INCOMPLETE_SENTENCE', examCode: 'TOEIC', section: 'R', kind: 'MCQ_SINGLE', contentSchema: {}, answerKeySchema: {} },
  { code: 'IELTS_R_MATCHING_HEADINGS', examCode: 'IELTS', section: 'R', kind: 'MATCHING', contentSchema: {}, answerKeySchema: {} },
];
const view = (over: Record<string, unknown>) => ({
  id: JOB, typeCode: 'TOEIC_P5_INCOMPLETE_SENTENCE', examCode: 'TOEIC', status: 'RUNNING', sourceName: 'fill.xlsx', hasMedia: false, totalRows: 40, processedRows: 0,
  errorCount: 0, createdCount: 0, hasReport: false, createdAt: '2026-11-11T00:00:00Z', ...over,
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'TOEIC' }] });
  installFakeXhr();
});
afterEach(() => {
  vi.unstubAllGlobals();
  useRoleStore.setState({ grants: null });
});

async function startUpload() {
  await userEvent.upload(await screen.findByLabelText('File Excel đã điền (.xlsx)'), new File([new Uint8Array(5)], 'fill.xlsx'));
  await userEvent.click(screen.getByRole('button', { name: 'Bắt đầu import' }));
  const xhr = FakeXhr.last();
  act(() => xhr.respond(202, { jobId: JOB, status: 'QUEUED' }));
  return xhr;
}

describe('ImportPage', () => {
  it('offers only the types of exams the person authors, and sends the type in the query, the files as parts, the token and the context', async () => {
    useContextStore.setState({ orgId: '22222222-2222-2222-2222-222222222222' });
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: '22222222-2222-2222-2222-222222222222', exam: 'TOEIC' }] });
    stubApi({
      'GET /api/cms/question-types': () => json(200, TYPES),
      [`GET /api/cms/import/${JOB}`]: () => json(200, { job: view({}), errors: [], versionIds: [] }),
    });
    renderAt('/cms/import', '/cms/import', <ImportPage />);
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Dạng câu hỏi' })).toHaveTextContent('TOEIC_P5_INCOMPLETE_SENTENCE'));
    await userEvent.upload(screen.getByLabelText('File zip media (tuỳ chọn)'), new File([new Uint8Array(3)], 'media.zip'));
    const xhr = await startUpload();
    expect(xhr.url).toBe('/api/cms/import?typeCode=TOEIC_P5_INCOMPLETE_SENTENCE');
    expect(xhr.headers.Authorization).toBe('Bearer tok');
    expect(xhr.headers['X-Org-Context']).toBe('22222222-2222-2222-2222-222222222222');
    expect((xhr.body!.get('file') as File).name).toBe('fill.xlsx');
    expect((xhr.body!.get('mediaZip') as File).name).toBe('media.zip');
    expect(await screen.findByText(/Máy chủ đang kiểm tra file/)).toBeInTheDocument();
  });

  it('lists the errors by row and column when the file is rejected and says nothing was created', async () => {
    stubApi({
      'GET /api/cms/question-types': () => json(200, TYPES),
      [`GET /api/cms/import/${JOB}`]: () =>
        json(200, {
          job: view({ status: 'REJECTED', errorCount: 1, hasReport: true }),
          errors: [{ row: 37, column: 'answer.slots.1', message: 'thiếu đáp án' }],
          versionIds: [],
        }),
    });
    renderAt('/cms/import', '/cms/import', <ImportPage />);
    await startUpload();
    expect(await screen.findByText('File bị từ chối: 1 lỗi.')).toBeInTheDocument();
    expect(screen.getByText('Không có câu hỏi nào được tạo. Sửa các dòng lỗi rồi tải lại.')).toBeInTheDocument();
    const row = screen.getByRole('cell', { name: '37' }).closest('tr')!;
    expect(row).toHaveTextContent('answer.slots.1');
    expect(row).toHaveTextContent('thiếu đáp án');
    expect(screen.getByRole('button', { name: 'Tải file Excel tô đỏ ô lỗi' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Gửi duyệt cả lô' })).not.toBeInTheDocument();
  });

  it('offers to submit the whole lot after a successful import, and does so once', async () => {
    const calls = stubApi({
      'GET /api/cms/question-types': () => json(200, TYPES),
      [`GET /api/cms/import/${JOB}`]: () => json(200, { job: view({ status: 'SUCCEEDED', createdCount: 40, processedRows: 40 }), errors: [], versionIds: ['a'] }),
      [`POST /api/cms/import/${JOB}/submit`]: () => json(202, { bundleId: '33333333-3333-3333-3333-333333333333' }),
    });
    renderAt('/cms/import', '/cms/import', <ImportPage />);
    await startUpload();
    expect(await screen.findByText('Đã tạo 40 bản nháp.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Gửi duyệt cả lô' }));
    await waitFor(() => expect(screen.getByText('Đã gửi duyệt cả lô.')).toBeInTheDocument());
    expect(calls.filter((c) => c.method === 'POST' && c.path.endsWith('/submit'))).toHaveLength(1);
  });

  it('tells an account that is no author of any exam here that it cannot import', async () => {
    useRoleStore.setState({ grants: [{ role: 'REVIEWER', org: null, exam: 'TOEIC' }] });
    stubApi({ 'GET /api/cms/question-types': () => json(200, TYPES) });
    renderAt('/cms/import', '/cms/import', <ImportPage />);
    expect(await screen.findByRole('heading', { name: 'Chưa thể import' })).toBeInTheDocument();
  });
});
