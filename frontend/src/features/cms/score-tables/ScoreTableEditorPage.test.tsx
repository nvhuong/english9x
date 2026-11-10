import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import ScoreTableEditorPage from './ScoreTableEditorPage';
import ScoreTableListPage from './ScoreTableListPage';
import type { ScoreTableVersion } from './types';

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
const TABLE: ScoreTableVersion = {
  versionId: '8a1f0c5e-6b0e-4d0e-9a52-1f3a5c7e9b21',
  tableId: ID,
  versionNo: 1,
  status: 'DRAFT',
  examCode: 'IELTS',
  skill: 'LISTENING',
  scope: 'EXAM',
  function: 'TABLE',
  rows: [
    { raw: 0, scaled: 0 },
    { raw: 1, scaled: 4.5 },
  ],
} as ScoreTableVersion;
const EXAMS = {
  'GET /api/catalog/exams': () => json(200, [{ code: 'IELTS', name: 'IELTS', variants: [], scoreSkills: ['LISTENING', 'READING_AC'], sections: [] }]),
};

describe('ScoreTableEditorPage: new', () => {
  it('sends the rows as typed, scope EXAM, and shows the server\'s reasons for them', async () => {
    const calls = stubApi({
      ...EXAMS,
      'POST /api/cms/score-tables': () =>
        problem(422, 'score-table-invalid', { status: 422, errors: [{ field: 'rows', code: 'invalid', message: 'keys must be contiguous from 0, missing 1' }] }),
    });
    renderAt('/cms/score-tables/new', '/cms/score-tables/new', <ScoreTableEditorPage />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Kỳ thi' }));
    await userEvent.click(await screen.findByRole('option', { name: 'IELTS' }));
    await userEvent.click(screen.getByRole('combobox', { name: 'Điểm cần quy đổi' }));
    await userEvent.click(await screen.findByRole('option', { name: 'LISTENING' }));
    await userEvent.type(await screen.findByLabelText(/^Các dòng của bảng/), '0 1{enter}2 2');
    expect(screen.getByRole('table', { name: 'Bảng quy đổi sẽ lưu' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('keys must be contiguous from 0, missing 1');
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toEqual({
      examCode: 'IELTS',
      skill: 'LISTENING',
      scope: 'EXAM',
      rows: [
        { raw: 0, scaled: 1 },
        { raw: 2, scaled: 2 },
      ],
    });
  });
});

describe('ScoreTableEditorPage: edit', () => {
  it('saves a draft in place and submits it', async () => {
    const calls = stubApi({
      [`GET /api/cms/score-tables/${ID}`]: () => json(200, TABLE),
      [`PUT /api/cms/score-tables/${ID}`]: () => json(200, TABLE),
      [`POST /api/cms/score-tables/${ID}/submit`]: () => json(202, { reviewId: '7d6e5f40-1b2c-4d3e-8f90-a1b2c3d4e5f6' }),
    });
    renderAt(`/cms/score-tables/${ID}`, '/cms/score-tables/:id', <ScoreTableEditorPage />);
    expect(await screen.findByText('Bản nháp')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Các dòng của bảng/)).toHaveValue('0\t0\n1\t4.5');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(await screen.findByText('Đã lưu bản nháp.')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ examCode: 'IELTS', skill: 'LISTENING', scope: 'EXAM', rows: TABLE.rows });
    await userEvent.click(screen.getByRole('button', { name: 'Gửi duyệt' }));
    expect(await screen.findByText(/Đã gửi duyệt/)).toBeInTheDocument();
  });

  it('says a published table is not changed, and locks one under review', async () => {
    stubApi({ [`GET /api/cms/score-tables/${ID}`]: () => json(200, { ...TABLE, status: 'PUBLISHED' }) });
    const first = renderAt(`/cms/score-tables/${ID}`, '/cms/score-tables/:id', <ScoreTableEditorPage />);
    expect(await screen.findByText(/Lưu thay đổi sẽ tạo bản nháp mới \(phiên bản 2\)/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lưu thành phiên bản mới' })).toBeInTheDocument();
    first.unmount();
    vi.unstubAllGlobals();
    stubApi({ [`GET /api/cms/score-tables/${ID}`]: () => json(200, { ...TABLE, status: 'IN_REVIEW' }) });
    renderAt(`/cms/score-tables/${ID}`, '/cms/score-tables/:id', <ScoreTableEditorPage />);
    expect(await screen.findByText('Bản này đang chờ duyệt nên bị khoá.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Lưu/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/^Các dòng của bảng/)).toBeDisabled();
  });

  it('shows no table to edit for a score that a function computes', async () => {
    stubApi({ [`GET /api/cms/score-tables/${ID}`]: () => json(200, { ...TABLE, skill: 'OVERALL', function: 'IELTS_OVERALL', status: 'PUBLISHED', rows: [] }) });
    renderAt(`/cms/score-tables/${ID}`, '/cms/score-tables/:id', <ScoreTableEditorPage />);
    expect(await screen.findByText(/tính bằng hàm IELTS_OVERALL/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Lưu/ })).not.toBeInTheDocument();
  });

  it('shows the same answer for a table that is not there and one the person may not open', async () => {
    stubApi({ [`GET /api/cms/score-tables/${ID}`]: () => problem(404, 'not-found', { status: 404 }) });
    renderAt(`/cms/score-tables/${ID}`, '/cms/score-tables/:id', <ScoreTableEditorPage />);
    expect(await screen.findByText('Không tìm thấy bảng')).toBeInTheDocument();
  });
});

describe('ScoreTableListPage', () => {
  it('lists the latest version of each table with its status and size', async () => {
    stubApi({
      ...EXAMS,
      'GET /api/cms/score-tables': () =>
        json(200, {
          items: [
            { tableId: ID, versionId: TABLE.versionId, versionNo: 2, status: 'DRAFT', examCode: 'IELTS', skill: 'LISTENING', scope: 'EXAM', function: 'TABLE', rowCount: 41 },
            { tableId: '5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b', versionId: TABLE.versionId, versionNo: 1, status: 'PUBLISHED', examCode: 'IELTS', skill: 'OVERALL', scope: 'EXAM', function: 'IELTS_OVERALL', rowCount: 0 },
          ],
          total: 2,
          offset: 0,
          limit: 50,
        }),
    });
    renderAt('/cms/score-tables', '/cms/score-tables', <ScoreTableListPage />);
    expect(await screen.findByRole('link', { name: 'LISTENING' })).toHaveAttribute('href', `/cms/score-tables/${ID}`);
    expect(screen.getByText('41')).toBeInTheDocument();
    expect(screen.getByText('tính bằng IELTS_OVERALL')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Bảng mới' })).toBeInTheDocument();
  });
});
