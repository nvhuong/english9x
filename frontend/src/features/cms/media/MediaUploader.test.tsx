import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { useContextStore } from '../../../app/stores/context';
import { refreshSession } from '../../auth/session';
import { json, me, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import { FakeXhr, installFakeXhr, LIMITS, MP3_VIEW } from './testUtils';
import { MediaUploader } from './MediaUploader';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

vi.mock('../../auth/session', async (orig) => ({ ...(await orig<typeof import('../../auth/session')>()), refreshSession: vi.fn() }));

const file = (name: string, bytes = 10, type = '') => new File([new Uint8Array(bytes)], name, { type });

function render(onUploaded = vi.fn()) {
  stubApi({ 'GET /api/cms/media/limits': () => json(200, LIMITS) });
  installFakeXhr();
  renderAt('/cms/media', '/cms/media', <MediaUploader examCode="IELTS" onUploaded={onUploaded} />);
  return onUploaded;
}

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
  useContextStore.setState({ orgId: null });
});
afterEach(() => vi.unstubAllGlobals());

describe('MediaUploader', () => {
  it('shows the server limit, sends the file with the exam, the token and the context, and reports what the server found', async () => {
    useContextStore.setState({ orgId: '22222222-2222-2222-2222-222222222222' });
    const onUploaded = render();
    expect(await screen.findByText(/Tối đa 200 MB mỗi tệp/)).toBeInTheDocument();
    await userEvent.upload(screen.getByLabelText('Tệp media'), file('part1.mp3', 10, 'audio/mpeg'));
    expect(screen.getByText('Đã chọn: part1.mp3 (10 B)')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Tải lên' }));

    const xhr = FakeXhr.last();
    expect(xhr.method).toBe('POST');
    expect(xhr.url).toBe('/api/cms/media?examCode=IELTS');
    expect(xhr.headers.Authorization).toBe('Bearer tok');
    expect(xhr.headers['X-Org-Context']).toBe('22222222-2222-2222-2222-222222222222');
    expect((xhr.body!.get('file') as File).name).toBe('part1.mp3');

    act(() => xhr.progress(25, 100));
    const bar = await screen.findByRole('progressbar', { name: 'Tiến độ tải lên' });
    expect(bar).toHaveAttribute('aria-valuenow', '25');
    expect(screen.getByRole('status')).toHaveTextContent('Đang tải lên 25%');
    act(() => xhr.progress(100, 100));
    expect(screen.getByRole('status')).toHaveTextContent('Đã gửi xong, máy chủ đang kiểm tra tệp…');

    act(() => xhr.respond(200, MP3_VIEW));
    expect(await screen.findByText(/Âm thanh · part1\.mp3/)).toBeInTheDocument();
    expect(screen.getByText(/Thời lượng 1:24/)).toBeInTheDocument();
    expect(onUploaded).toHaveBeenCalledWith(MP3_VIEW);
    await waitFor(() => expect(screen.queryByRole('progressbar')).not.toBeInTheDocument());
  });

  it('refuses a type that is not on the server list before sending a byte, and says which', async () => {
    render();
    await screen.findByText(/Tối đa 200 MB/);
    const user = userEvent.setup({ applyAccept: false });
    await user.upload(screen.getByLabelText('Tệp media'), file('virus.exe'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Đuôi tệp .exe không nằm trong danh sách được phép.');
    expect(screen.getByRole('button', { name: 'Tải lên' })).toBeDisabled();
    expect(FakeXhr.instances).toHaveLength(0);
  });

  it('refuses a file over the server limit, naming both sizes', async () => {
    render();
    await screen.findByText(/Tối đa 200 MB/);
    const big = file('big.mp4');
    Object.defineProperty(big, 'size', { value: 300 * 1024 * 1024 });
    await userEvent.upload(screen.getByLabelText('Tệp media'), big);
    expect(await screen.findByRole('alert')).toHaveTextContent('Tệp có dung lượng 300 MB, vượt quá giới hạn 200 MB.');
    expect(FakeXhr.instances).toHaveLength(0);
  });

  it('refuses an empty file', async () => {
    render();
    await screen.findByText(/Tối đa 200 MB/);
    await userEvent.upload(screen.getByLabelText('Tệp media'), file('empty.mp3', 0));
    expect(await screen.findByRole('alert')).toHaveTextContent('Tệp trống.');
  });

  it.each([
    [415, 'media-type-not-allowed', /Loại tệp này không được phép/],
    [415, 'media-extension-mismatch', /Đuôi tệp không khớp với nội dung thật/],
    [413, 'media-too-large', /vượt quá dung lượng tối đa/],
    [413, 'payload-too-large', /vượt quá dung lượng tối đa/],
    [422, 'media-unreadable', /Không đọc được nội dung âm thanh hoặc video/],
    [403, 'forbidden', /không có quyền tải tệp lên/],
    [503, 'media-storage-unavailable', /lưu trữ tệp tạm thời không dùng được/],
  ])('shows the server\'s refusal %i %s in words, not the server text', async (status, slug, text) => {
    render();
    await screen.findByText(/Tối đa 200 MB/);
    await userEvent.upload(screen.getByLabelText('Tệp media'), file('a.mp3'));
    await userEvent.click(screen.getByRole('button', { name: 'Tải lên' }));
    act(() => FakeXhr.last().respond(status, { type: `urn:e9x:problem:${slug}`, title: 'SERVER TITLE', status, detail: 'SERVER DETAIL', errors: [] }));
    expect(await screen.findByRole('alert')).toHaveTextContent(text);
    expect(screen.queryByText(/SERVER/)).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Tải lên' })).toBeEnabled());
  });

  it('says so when the connection is lost, and lets the author try again', async () => {
    render();
    await screen.findByText(/Tối đa 200 MB/);
    await userEvent.upload(screen.getByLabelText('Tệp media'), file('a.mp3'));
    await userEvent.click(screen.getByRole('button', { name: 'Tải lên' }));
    act(() => FakeXhr.last().fail());
    expect(await screen.findByRole('alert')).toHaveTextContent('Mất kết nối khi tải lên.');
    await userEvent.click(screen.getByRole('button', { name: 'Tải lên' }));
    expect(FakeXhr.instances).toHaveLength(2);
  });

  it('cancels a running upload without showing an error', async () => {
    render();
    await screen.findByText(/Tối đa 200 MB/);
    await userEvent.upload(screen.getByLabelText('Tệp media'), file('a.mp3'));
    await userEvent.click(screen.getByRole('button', { name: 'Tải lên' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Hủy' }));
    expect(FakeXhr.last().aborted).toBe(true);
    await waitFor(() => expect(screen.queryByRole('progressbar')).not.toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('refreshes the session once on a 401 and repeats the upload with the new token', async () => {
    vi.mocked(refreshSession).mockResolvedValueOnce('fresh-token');
    render();
    await screen.findByText(/Tối đa 200 MB/);
    await userEvent.upload(screen.getByLabelText('Tệp media'), file('a.mp3'));
    await userEvent.click(screen.getByRole('button', { name: 'Tải lên' }));
    act(() => FakeXhr.last().respond(401, { type: 'urn:e9x:problem:unauthorized', title: 'x', status: 401, errors: [] }));
    await waitFor(() => expect(FakeXhr.instances).toHaveLength(2));
    expect(FakeXhr.last().headers.Authorization).toBe('Bearer fresh-token');
    act(() => FakeXhr.last().respond(200, MP3_VIEW));
    expect(await screen.findByText(/Âm thanh · part1\.mp3/)).toBeInTheDocument();
  });

  it('a 401 that cannot be refreshed is final: one request, and the author is told they have no permission', async () => {
    vi.mocked(refreshSession).mockResolvedValueOnce(null);
    render();
    await screen.findByText(/Tối đa 200 MB/);
    await userEvent.upload(screen.getByLabelText('Tệp media'), file('a.mp3'));
    await userEvent.click(screen.getByRole('button', { name: 'Tải lên' }));
    act(() => FakeXhr.last().respond(401, { type: 'urn:e9x:problem:unauthorized', title: 'x', status: 401, errors: [] }));
    expect(await screen.findByRole('alert')).toHaveTextContent('không có quyền tải tệp lên');
    expect(FakeXhr.instances).toHaveLength(1);
  });
});
