import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import OverrideAttachPage from './OverrideAttachPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants: [{ role: 'SYS_ADMIN', org: null, exam: null }] });
});
afterEach(() => {
  vi.unstubAllGlobals();
  useRoleStore.setState({ grants: null });
});

const TEST = '5b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d';
const TABLE = '3c7e9b21-8a1f-4c5e-9b0e-6d0e9a521f3a';

describe('OverrideAttachPage', () => {
  it('attaches the override of a skill to the test by its id and lists it', async () => {
    let list: unknown[] = [];
    const calls = stubApi({
      'GET /api/catalog/exams': () => json(200, [{ code: 'IELTS', name: 'IELTS', variants: [], scoreSkills: ['LISTENING', 'OVERALL'], sections: [] }]),
      'GET /api/cms/score-tables/overrides': () => json(200, list),
      'POST /api/cms/score-tables/overrides': () => {
        list = [{ tableId: TABLE, testId: TEST, examCode: 'IELTS', skill: 'LISTENING', versionId: null, versionNo: null, status: null }];
        return json(201, list[0]);
      },
    });
    renderAt('/admin/score-overrides', '/admin/score-overrides', <OverrideAttachPage />);
    const button = await screen.findByRole('button', { name: 'Gắn bảng ghi đè' });
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/^Mã đề/), TEST);
    await userEvent.click(screen.getByRole('combobox', { name: 'Kỳ thi' }));
    await userEvent.click(await screen.findByRole('option', { name: 'IELTS' }));
    await userEvent.click(screen.getByRole('combobox', { name: 'Điểm cần ghi đè' }));
    await userEvent.click(await screen.findByRole('option', { name: 'LISTENING' }));
    await userEvent.click(button);
    expect(await screen.findByText(/Đã gắn bảng ghi đè LISTENING/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ testId: TEST, skill: 'LISTENING' });
    expect(await screen.findByText('Chưa có dòng nào')).toBeInTheDocument();
  });
});
