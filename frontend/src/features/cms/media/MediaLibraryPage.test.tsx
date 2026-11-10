import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { json, me, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import MediaLibraryPage from './MediaLibraryPage';
import { FakeXhr, installFakeXhr, LIMITS, MP3_VIEW } from './testUtils';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
  installFakeXhr();
});
afterEach(() => {
  vi.unstubAllGlobals();
  useRoleStore.setState({ grants: null });
});

describe('MediaLibraryPage', () => {
  it('tells an account that is no author of any exam in this context that it cannot upload yet', () => {
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: '33333333-3333-3333-3333-333333333333', exam: 'IELTS' }] });
    stubApi({ 'GET /api/cms/media/limits': () => json(200, LIMITS) });
    renderAt('/cms/media', '/cms/media', <MediaLibraryPage />);
    expect(screen.getByRole('heading', { name: 'Chưa thể tải tệp lên' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Tệp media')).not.toBeInTheDocument();
  });

  it('uploads for the exam the person authors, lists the file and previews it through its signed link', async () => {
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'IELTS' }, { role: 'REVIEWER', org: null, exam: 'TOEIC' }] });
    stubApi({
      'GET /api/cms/media/limits': () => json(200, LIMITS),
      [`GET /api/media/${MP3_VIEW.id}/url`]: () => json(200, { url: `https://localhost/e9x-media/${MP3_VIEW.id}?X-Amz-Signature=s`, expiresAt: new Date(Date.now() + 600_000).toISOString() }),
    });
    renderAt('/cms/media', '/cms/media', <MediaLibraryPage />);
    expect(await screen.findByText('Chưa có tệp nào được tải lên trong phiên này.')).toBeInTheDocument();
    await userEvent.upload(await screen.findByLabelText('Tệp media'), new File([new Uint8Array(5)], 'part1.mp3'));
    await userEvent.click(screen.getByRole('button', { name: 'Tải lên' }));
    expect(FakeXhr.last().url).toBe('/api/cms/media?examCode=IELTS');
    act(() => FakeXhr.last().respond(200, MP3_VIEW));
    expect(await screen.findByRole('button', { name: 'Xem trước' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Xem trước' }));
    expect(await screen.findByLabelText('Nghe thử part1.mp3')).toHaveAttribute('src', expect.stringContaining('X-Amz-Signature=s'));
    await userEvent.click(screen.getByRole('button', { name: 'Ẩn xem trước' }));
    expect(screen.queryByLabelText('Nghe thử part1.mp3')).not.toBeInTheDocument();
  });
});
