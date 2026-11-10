import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../../app/stores/auth';
import { json, me, problem, renderAt, resetAuth, stubApi } from '../../auth/testUtils';
import { MediaPreview } from './MediaPreview';

vi.mock('../../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const ID = '0b8e7f3a-4c1d-4e2b-9a55-3f1d2c4b6a70';
const link = (path: string) => json(200, { url: `https://localhost/e9x-media/${ID}?X-Amz-Signature=abc${path}`, expiresAt: new Date(Date.now() + 600_000).toISOString() });

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

describe('MediaPreview', () => {
  it('plays audio from the signed link with the browser controls', async () => {
    const calls = stubApi({ 'GET /api/media/0b8e7f3a-4c1d-4e2b-9a55-3f1d2c4b6a70/url': () => link('') });
    const { container } = renderAt('/x', '/x', <MediaPreview mediaId={ID} kind="AUDIO" name="part1.mp3" />);
    const audio = await screen.findByLabelText('Nghe thử part1.mp3');
    expect(audio.tagName).toBe('AUDIO');
    expect(audio).toHaveAttribute('src', expect.stringContaining('X-Amz-Signature=abc'));
    expect(audio).toHaveAttribute('controls');
    expect(container.querySelector('audio')).toBe(audio);
    expect(calls.filter((c) => c.path.endsWith('/url'))).toHaveLength(1);
  });

  it('shows an image with its name as the alternative text, and a video with controls', async () => {
    stubApi({ 'GET /api/media/0b8e7f3a-4c1d-4e2b-9a55-3f1d2c4b6a70/url': () => link('') });
    const { unmount } = renderAt('/x', '/x', <MediaPreview mediaId={ID} kind="IMAGE" name="map.png" />);
    expect(await screen.findByRole('img', { name: 'map.png' })).toBeInTheDocument();
    unmount();
    renderAt('/x', '/x', <MediaPreview mediaId={ID} kind="VIDEO" name="clip.mp4" />);
    expect((await screen.findByLabelText('Xem thử clip.mp4')).tagName).toBe('VIDEO');
  });

  it('says so, without server text, when the link is refused', async () => {
    stubApi({ 'GET /api/media/0b8e7f3a-4c1d-4e2b-9a55-3f1d2c4b6a70/url': () => problem(404, 'media-not-found') });
    renderAt('/x', '/x', <MediaPreview mediaId={ID} kind="AUDIO" name="a.mp3" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy tệp, hoặc bạn không có quyền mở.');
    expect(document.querySelector('audio')).toBeNull();
  });

  it('asks for a new link when the element fails after the link ran out, and not before (a new address would only restart a recording)', async () => {
    let n = 0;
    const calls = stubApi({
      'GET /api/media/0b8e7f3a-4c1d-4e2b-9a55-3f1d2c4b6a70/url': () => {
        n += 1;
        const expires = n === 1 ? Date.now() - 1000 : Date.now() + 600_000;
        return json(200, { url: `https://localhost/e9x-media/${ID}?X-Amz-Signature=v${n}`, expiresAt: new Date(expires).toISOString() });
      },
    });
    renderAt('/x', '/x', <MediaPreview mediaId={ID} kind="AUDIO" name="a.mp3" />);
    const audio = await screen.findByLabelText('Nghe thử a.mp3');
    expect(audio).toHaveAttribute('src', expect.stringContaining('v1'));
    fireEvent.error(audio);
    await waitFor(() => expect(screen.getByLabelText('Nghe thử a.mp3')).toHaveAttribute('src', expect.stringContaining('v2')));
    fireEvent.error(screen.getByLabelText('Nghe thử a.mp3'));
    await new Promise((r) => setTimeout(r, 50));
    expect(calls.filter((c) => c.path.endsWith('/url'))).toHaveLength(2);
  });
});
