import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import ScoreTableEditorPage from './ScoreTableEditorPage';
import { TestOverrides } from './TestOverrides';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
});
afterEach(() => {
  vi.unstubAllGlobals();
  useRoleStore.setState({ grants: null });
});

const TEST = '5b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d';
const TABLE = '3c7e9b21-8a1f-4c5e-9b0e-6d0e9a521f3a';
const EXAMS = { 'GET /api/catalog/exams': () => json(200, [{ code: 'IELTS', name: 'IELTS', variants: ['AC', 'GT'], scoreSkills: ['LISTENING', 'READING_AC', 'READING_GT', 'OVERALL'], sections: [] }]) };
const attached = { tableId: TABLE, testId: TEST, examCode: 'IELTS', skill: 'LISTENING', versionId: null, versionNo: null, status: null };

describe('TestOverrides', () => {
  it('lets the administrator attach the override of a skill, offering only the skills of the variant that are not taken', async () => {
    useRoleStore.setState({ grants: [{ role: 'SYS_ADMIN', org: null, exam: null }] });
    let list: unknown[] = [];
    const calls = stubApi({
      ...EXAMS,
      'GET /api/cms/score-tables/overrides': () => json(200, list),
      'POST /api/cms/score-tables/overrides': () => {
        list = [attached];
        return json(201, attached);
      },
    });
    renderAt('/x', '/x', <TestOverrides testId={TEST} examCode="IELTS" variant="AC" />);
    expect(await screen.findByText('Đề này chưa có bảng ghi đè.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('combobox', { name: 'Điểm cần ghi đè' }));
    expect(screen.queryByRole('option', { name: 'READING_GT' })).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('option', { name: 'LISTENING' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gắn bảng ghi đè' }));
    expect(await screen.findByRole('link', { name: 'Mở bảng ghi đè LISTENING' })).toHaveAttribute('href', `/cms/score-tables/${TABLE}?test=${TEST}`);
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ testId: TEST, skill: 'LISTENING' });
    expect(screen.getByText('Chưa có dòng nào')).toBeInTheDocument();
  });

  it('says why when the server refuses, and shows nothing to an author when there is no override', async () => {
    useRoleStore.setState({ grants: [{ role: 'SYS_ADMIN', org: null, exam: null }] });
    stubApi({
      ...EXAMS,
      'GET /api/cms/score-tables/overrides': () => json(200, []),
      'POST /api/cms/score-tables/overrides': () => problem(409, 'score-table-exists', { status: 409 }),
    });
    const first = renderAt('/x', '/x', <TestOverrides testId={TEST} examCode="IELTS" variant={null} />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Điểm cần ghi đè' }));
    await userEvent.click(await screen.findByRole('option', { name: 'OVERALL' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gắn bảng ghi đè' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/đã có bảng/);
    first.unmount();
    vi.unstubAllGlobals();
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'IELTS' }] });
    stubApi({ ...EXAMS, 'GET /api/cms/score-tables/overrides': () => json(200, []) });
    renderAt('/x', '/x', <TestOverrides testId={TEST} examCode="IELTS" variant={null} />);
    expect(screen.queryByRole('combobox', { name: 'Điểm cần ghi đè' })).not.toBeInTheDocument();
  });
});

describe('ScoreTableEditorPage: override without rows', () => {
  it('writes version 1 with PUT, scope TEST and the test id, then opens the table', async () => {
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'IELTS' }] });
    let written = false;
    const calls = stubApi({
      ...EXAMS,
      [`GET /api/cms/score-tables/${TABLE}`]: () =>
        written
          ? json(200, { tableId: TABLE, versionId: 'v1', versionNo: 1, status: 'DRAFT', examCode: 'IELTS', skill: 'LISTENING', scope: 'TEST', testId: TEST, function: 'TABLE', rows: [{ raw: 0, scaled: 0 }] })
          : problem(404, 'not-found', { status: 404 }),
      'GET /api/cms/score-tables/overrides': () => json(200, [attached]),
      [`PUT /api/cms/score-tables/${TABLE}`]: () => {
        written = true;
        return json(201, { tableId: TABLE, versionId: 'v1', versionNo: 1, status: 'DRAFT', examCode: 'IELTS', skill: 'LISTENING', scope: 'TEST', testId: TEST, function: 'TABLE', rows: [{ raw: 0, scaled: 0 }] });
      },
    });
    renderAt(`/cms/score-tables/${TABLE}?test=${TEST}`, '/cms/score-tables/:id', <ScoreTableEditorPage />);
    await userEvent.type(await screen.findByLabelText(/^Các dòng của bảng/, {}, { timeout: 10000 }), '0 0');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(await screen.findByText('Bảng ghi đè của một đề')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ examCode: 'IELTS', skill: 'LISTENING', scope: 'TEST', testId: TEST, rows: [{ raw: 0, scaled: 0 }] });
  });
});
