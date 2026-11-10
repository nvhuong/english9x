import { describe, expect, it } from 'vitest';
import { emptyBlock, videoEmbedSrc } from './blocks';
import { blockPath, issuesByBlock, move } from './model';
import { BLOCK_TYPES } from './types';

describe('videoEmbedSrc', () => {
  it.each([
    ['youtube', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'https://www.youtube.com/embed/dQw4w9WgXcQ'],
    ['youtube', 'https://youtu.be/dQw4w9WgXcQ', 'https://www.youtube.com/embed/dQw4w9WgXcQ'],
    ['youtube', 'https://www.youtube.com/embed/dQw4w9WgXcQ', 'https://www.youtube.com/embed/dQw4w9WgXcQ'],
    ['vimeo', 'https://vimeo.com/76979871', 'https://player.vimeo.com/video/76979871'],
    ['vimeo', 'https://player.vimeo.com/video/76979871', 'https://player.vimeo.com/video/76979871'],
  ] as const)('%s %s', (provider, url, expected) => {
    expect(videoEmbedSrc(provider, url)).toBe(expected);
  });

  it.each([
    ['youtube', 'http://www.youtube.com/watch?v=dQw4w9WgXcQ'],
    ['youtube', 'https://www.youtube.com.evil.example/watch?v=dQw4w9WgXcQ'],
    ['youtube', 'https://user@www.youtube.com/watch?v=dQw4w9WgXcQ'],
    ['youtube', 'https://www.youtube.com/watch?v=<script>'],
    ['vimeo', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'],
    ['youtube', 'https://vimeo.com/76979871'],
    ['vimeo', 'https://vimeo.com/not-a-number'],
    ['youtube', 'not a url'],
  ] as const)('refuses %s %s', (provider, url) => {
    expect(videoEmbedSrc(provider, url)).toBeNull();
  });
});

describe('model helpers', () => {
  it('has an empty block for every type, carrying its own type', () => {
    expect(BLOCK_TYPES.map((t) => emptyBlock(t).type)).toEqual(BLOCK_TYPES);
  });
  it('reads the server paths', () => {
    expect(blockPath('blocks[3].url')).toEqual({ index: 3, field: 'url' });
    expect(blockPath('title')).toBeNull();
    const m = issuesByBlock([{ field: 'blocks[1].steps[0]', message: 'too short' }, { field: 'blocks[1].url', message: 'bad' }, { field: 'title', message: 'x' }]);
    expect(m.get(1)).toEqual({ steps: 'too short', url: 'bad' });
  });
  it('moves within bounds only', () => {
    expect(move([1, 2, 3], 0, 1)).toEqual([2, 1, 3]);
    expect(move([1, 2, 3], 0, -1)).toEqual([1, 2, 3]);
  });
});
