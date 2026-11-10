import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import WorkQueuePage from './WorkQueuePage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ME = me().id as string;
const item = (over: Record<string, unknown>) => ({ versionNo: 1, points: 1, versionId: `v-${over.questionId}`, typeCode: 'T', examCode: 'TOEIC', ...over });
const page = (items: unknown[]) => json(200, { items, total: items.length, offset: 0, limit: 100 });

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
});
afterEach(() => {
  vi.unstubAllGlobals();
  useRoleStore.setState({ grants: null });
});

describe('WorkQueuePage', () => {
  it('lists my drafts, my changes-requested items and what waits for my review, asking the server per exam and status', async () => {
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'TOEIC' }, { role: 'REVIEWER', org: null, exam: 'IELTS' }] });
    const calls = stubApi({
      'GET /api/cms/questions': (c) => {
        if (c.search.includes('status=DRAFT')) return page([item({ questionId: 'q1', status: 'DRAFT', createdBy: ME, title: 'Mine draft' }), item({ questionId: 'q2', status: 'DRAFT', createdBy: 'x', title: 'Not mine' })]);
        if (c.search.includes('status=CHANGES_REQUESTED')) return page([item({ questionId: 'q3', status: 'CHANGES_REQUESTED', createdBy: ME, title: 'Sent back' })]);
        return page([item({ questionId: 'q4', status: 'IN_REVIEW', createdBy: 'x', examCode: 'IELTS', title: 'To review' }), item({ questionId: 'q5', status: 'IN_REVIEW', createdBy: ME, examCode: 'IELTS', title: 'My own in review' })]);
      },
    });
    renderAt('/cms/work-queue', '/cms/work-queue', <WorkQueuePage />);
    const drafts = await screen.findByRole('region', { name: /Bản nháp của tôi/ });
    expect(within(drafts).getByRole('link', { name: 'Mine draft' })).toHaveAttribute('href', '/cms/questions/q1');
    expect(within(drafts).queryByText('Not mine')).not.toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: /Bị yêu cầu sửa/ })).getByText('Sent back')).toBeInTheDocument();
    const review = screen.getByRole('region', { name: /Chờ tôi duyệt/ });
    expect(within(review).getByText('To review')).toBeInTheDocument();
    expect(within(review).queryByText('My own in review')).not.toBeInTheDocument();
    const asked = calls.filter((c) => c.path === '/api/cms/questions').map((c) => c.search).sort();
    expect(asked).toHaveLength(3);
    expect(asked.some((s) => s.includes('exam=TOEIC') && s.includes('status=DRAFT'))).toBe(true);
    expect(asked.some((s) => s.includes('exam=TOEIC') && s.includes('status=CHANGES_REQUESTED'))).toBe(true);
    expect(asked.some((s) => s.includes('exam=IELTS') && s.includes('status=IN_REVIEW'))).toBe(true);
  });

  it('says there is nothing assigned when the person has neither role in this context', async () => {
    useRoleStore.setState({ grants: [{ role: 'LEARNER', org: null, exam: null }] });
    stubApi({});
    renderAt('/cms/work-queue', '/cms/work-queue', <WorkQueuePage />);
    expect(await screen.findByText('Chưa có việc nào được giao')).toBeInTheDocument();
  });

  it('says the queue is empty when the server has nothing for the person', async () => {
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'TOEIC' }] });
    stubApi({ 'GET /api/cms/questions': () => page([]) });
    renderAt('/cms/work-queue', '/cms/work-queue', <WorkQueuePage />);
    expect(await screen.findByText('Hàng việc trống')).toBeInTheDocument();
  });

  it('offers a reload when a list cannot be loaded', async () => {
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'TOEIC' }] });
    stubApi({ 'GET /api/cms/questions': () => json(500, {}) });
    renderAt('/cms/work-queue', '/cms/work-queue', <WorkQueuePage />);
    expect(await screen.findByRole('button', { name: 'Tải lại' })).toBeInTheDocument();
  });
});
