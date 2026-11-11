import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import ReviewPage from './ReviewPage';
import ReviewQueuePage from './ReviewQueuePage';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const review = { id: 'r1', type: 'question', versionId: 'v2', examCode: 'IELTS', authorId: 'a', submittedBy: 'a', submittedAt: '2026-11-10T00:00:00Z', status: 'IN_REVIEW' };
const detail = (over: Record<string, unknown> = {}) => ({
  review, authorName: 'Biên Soạn', ownVersion: false,
  version: { versionId: 'v2', versionNo: 2, status: 'IN_REVIEW', content: { content: { stem: 'New' } } },
  previous: { versionId: 'v1', versionNo: 1, status: 'PUBLISHED', content: { content: { stem: 'Old' } } },
  ...over,
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
});
afterEach(() => vi.unstubAllGlobals());

describe('review screens', () => {
  it('lists the queue with the author name and links a bundle to its own page', async () => {
    stubApi({
      'GET /api/cms/reviews': () => json(200, [
        { reviewId: 'r1', size: 1, type: 'question', examCode: 'IELTS', submittedBy: 'a', submittedAt: '2026-11-10T00:00:00Z', authorName: 'Biên Soạn' },
        { bundleId: 'b1', size: 12, type: 'question', examCode: 'TOEIC', submittedBy: 'a', submittedAt: '2026-11-10T00:00:00Z' },
      ]),
    });
    renderAt('/cms/reviews', '/cms/reviews', <ReviewQueuePage />);
    expect(await screen.findByRole('link', { name: 'Câu hỏi' })).toHaveAttribute('href', '/cms/reviews/r1');
    expect(screen.getByText(/Người soạn: Biên Soạn/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Cả cụm 12 bản' })).toHaveAttribute('href', '/cms/reviews/bundles/b1');
  });

  it('shows the diff and approves', async () => {
    const calls = stubApi({
      'GET /api/cms/reviews/r1': () => json(200, detail()),
      'POST /api/cms/reviews/r1/approve': () => new Response(null, { status: 204 }),
    });
    renderAt('/cms/reviews/r1', '/cms/reviews/:id', <ReviewPage />);
    expect(await screen.findByText('Old')).toBeInTheDocument();
    expect(screen.getByText('New')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Duyệt' }));
    expect(await screen.findByText('Đã duyệt và xuất bản.')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/cms/reviews/r1/approve')).toBe(true);
  });

  it('needs a comment to request changes and sends it', async () => {
    const calls = stubApi({
      'GET /api/cms/reviews/r1': () => json(200, detail()),
      'POST /api/cms/reviews/r1/request-changes': () => new Response(null, { status: 204 }),
    });
    renderAt('/cms/reviews/r1', '/cms/reviews/:id', <ReviewPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Yêu cầu sửa' }));
    const send = screen.getByRole('button', { name: 'Gửi yêu cầu sửa' });
    expect(send).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Nhận xét'), 'Sửa đáp án');
    await userEvent.click(send);
    await waitFor(() => expect(screen.getByText('Đã gửi yêu cầu sửa cho người soạn.')).toBeInTheDocument());
    expect(calls.find((c) => c.path.endsWith('/request-changes'))?.body).toEqual({ comment: 'Sửa đáp án' });
  });

  it('does not offer the decision on a version the person wrote, and shows the refusal of the server when it comes anyway', async () => {
    stubApi({ 'GET /api/cms/reviews/r1': () => json(200, detail({ ownVersion: true })) });
    renderAt('/cms/reviews/r1', '/cms/reviews/:id', <ReviewPage />);
    expect(await screen.findByText(/Bạn đã soạn hoặc gửi bản này/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Duyệt' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Yêu cầu sửa' })).toBeDisabled();
  });

  it('explains an unpublished dependency', async () => {
    stubApi({
      'GET /api/cms/reviews/r1': () => json(200, detail()),
      'POST /api/cms/reviews/r1/approve': () => problem(409, 'unpublished-dependencies'),
    });
    renderAt('/cms/reviews/r1', '/cms/reviews/:id', <ReviewPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Duyệt' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('chưa được xuất bản');
  });
});
