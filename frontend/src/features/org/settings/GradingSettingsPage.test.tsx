import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import { useOrgStore } from '../orgContext';
import GradingSettingsPage from './GradingSettingsPage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ORG = 'aaaaaaaa-0000-0000-0000-00000000000a';
const setup = (type: 'CENTER' | 'ENTERPRISE') => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: ORG });
  useOrgStore.setState({ orgs: [{ id: ORG, name: 'Org', type }], loaded: true });
};
afterEach(() => vi.unstubAllGlobals());

describe('GradingSettingsPage', () => {
  beforeEach(() => setup('CENTER'));

  it('a centre picks its own teachers and a mode, and the choice is sent to the server', async () => {
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/grading-settings`]: () => json(200, { grader: 'ENGLISH9X' }),
      [`PUT /api/orgs/${ORG}/grading-settings`]: () => json(200, { grader: 'CENTER', mode: 'TEACHER' }),
    });
    renderAt('/', '/', <GradingSettingsPage />);
    await userEvent.click(await screen.findByRole('combobox', { name: 'Bên chấm' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Giáo viên của trung tâm' }));
    await userEvent.click(screen.getByRole('combobox', { name: 'Cách chấm Viết và Nói' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Chỉ giáo viên' }));
    await userEvent.click(screen.getByRole('button', { name: 'Lưu cấu hình' }));
    expect(await screen.findByText('Đã lưu cấu hình chấm bài.')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ grader: 'CENTER', mode: 'TEACHER' });
  });

  it("shows the server's refusal", async () => {
    stubApi({
      [`GET /api/orgs/${ORG}/grading-settings`]: () => json(200, { grader: 'ENGLISH9X', mode: 'AI' }),
      [`PUT /api/orgs/${ORG}/grading-settings`]: () => problem(400, 'grading-not-allowed'),
    });
    renderAt('/', '/', <GradingSettingsPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Lưu cấu hình' }));
    expect(await screen.findByText(/không được chọn giáo viên trung tâm/)).toBeInTheDocument();
  });
});

describe('GradingSettingsPage: enterprise', () => {
  beforeEach(() => setup('ENTERPRISE'));

  it('locks the grader and the mode to English9x with AI, with the reason, and sends only that', async () => {
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/grading-settings`]: () => json(200, { grader: 'ENGLISH9X' }),
      [`PUT /api/orgs/${ORG}/grading-settings`]: () => json(200, { grader: 'ENGLISH9X', mode: 'AI' }),
    });
    renderAt('/', '/', <GradingSettingsPage />);
    expect(await screen.findByTestId('enterprise-lock')).toHaveTextContent('luôn do English9x chấm bằng AI');
    expect(screen.getByRole('combobox', { name: 'Cách chấm Viết và Nói' })).toBeDisabled();
    expect(screen.getByRole('combobox', { name: 'Bên chấm' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Lưu cấu hình' }));
    expect(await screen.findByText('Đã lưu cấu hình chấm bài.')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ grader: 'ENGLISH9X', mode: 'AI' });
  });
});
