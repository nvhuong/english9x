import { describe, expect, it } from 'vitest';
import { ApiProblemError } from '../../../api/problem';
import i18n from '../../../i18n';
import { formatBytes, formatDuration, extensionOf } from './format';
import { mediaProblemText } from './problems';
import { UploadNetworkError } from './upload';

describe('media helpers', () => {
  it('formats lengths and sizes', () => {
    expect(formatDuration(1567)).toBe('0:02');
    expect(formatDuration(84_000)).toBe('1:24');
    expect(formatDuration(3_600_000)).toBe('60:00');
    expect(formatBytes(3361)).toBe('3.3 KB');
    expect(formatBytes(200 * 1024 * 1024)).toBe('200 MB');
    expect(extensionOf('Part 1.FINAL.Mp3')).toBe('mp3');
    expect(extensionOf('noext')).toBe('');
  });

  it('chooses the words from the problem type and the status, in both languages', async () => {
    const t = i18n.getFixedT('en', 'media') as never;
    expect(mediaProblemText(t, new ApiProblemError(415, { type: 'urn:e9x:problem:media-extension-mismatch', title: 'x' }))).toMatch(/does not match/);
    expect(mediaProblemText(t, new ApiProblemError(404, { title: 'x' }))).toMatch(/not found/);
    expect(mediaProblemText(t, new ApiProblemError(413, { title: 'x' }))).toMatch(/larger than/);
    expect(mediaProblemText(t, new UploadNetworkError())).toMatch(/connection was lost/);
    expect(mediaProblemText(t, new Error('boom'))).toMatch(/upload failed/);
    const vi_ = i18n.getFixedT('vi', 'media') as never;
    expect(mediaProblemText(vi_, new ApiProblemError(422, { type: 'urn:e9x:problem:media-unreadable', title: 'x' }))).toMatch(/Không đọc được/);
  });
});
