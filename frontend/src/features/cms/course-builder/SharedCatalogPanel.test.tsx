import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import { SharedCatalogPanel } from './SharedCatalogPanel';
import type { CourseVersion } from './types';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ORG = 'aaaaaaaa-0000-0000-0000-00000000000a';
const ID = 'c1111111-1111-1111-1111-111111111111';
const version = (sharedStatus: CourseVersion['sharedStatus']) => ({ courseId: ID, status: 'PUBLISHED', examCode: 'TOEIC', sharedStatus }) as CourseVersion;

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: ORG });
  useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: ORG, exam: 'TOEIC' }] });
});
afterEach(() => {
  vi.unstubAllGlobals();
  useRoleStore.setState({ grants: null });
});

describe('SharedCatalogPanel', () => {
  it('offers a course that was not offered yet', async () => {
    const calls = stubApi({ [`POST /api/cms/courses/${ID}/share`]: () => json(200, { courseId: ID, sharedStatus: 'PENDING' }) });
    renderAt('/', '/', <SharedCatalogPanel version={version('NONE')} />);
    await userEvent.click(screen.getByRole('button', { name: 'Gửi lên kho chung' }));
    expect(await screen.findByText(/đang chờ duyệt/)).toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it('withdraws a pending request', async () => {
    const calls = stubApi({ [`POST /api/cms/courses/${ID}/share/withdraw`]: () => json(200, { courseId: ID, sharedStatus: 'NONE' }) });
    renderAt('/', '/', <SharedCatalogPanel version={version('PENDING')} />);
    expect(screen.queryByRole('button', { name: 'Gửi lên kho chung' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Rút lại yêu cầu' }));
    expect(await screen.findByText('Đã rút yêu cầu.')).toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it('offers a rejected course again, and shows the 409 reason', async () => {
    stubApi({ [`POST /api/cms/courses/${ID}/share`]: () => problem(409, 'conflict') });
    renderAt('/', '/', <SharedCatalogPanel version={version('REJECTED')} />);
    expect(screen.getByText(/đã từ chối khoá này/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Gửi lại' }));
    expect(await screen.findByText(/Không gửi được/)).toBeInTheDocument();
  });

  it('has no buttons for an approved course, nor for a person who may not offer it', () => {
    const { unmount } = renderAt('/', '/', <SharedCatalogPanel version={version('APPROVED')} />);
    expect(screen.queryByRole('button')).toBeNull();
    unmount();
    useRoleStore.setState({ grants: [{ role: 'LEARNER', org: ORG, exam: null }] });
    renderAt('/', '/', <SharedCatalogPanel version={version('NONE')} />);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('is absent outside an organisation', () => {
    useContextStore.setState({ orgId: null });
    renderAt('/', '/', <SharedCatalogPanel version={version('NONE')} />);
    expect(screen.queryByTestId('shared-panel')).toBeNull();
  });
});
