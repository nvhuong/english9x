import type { Block, BlockType, Provider } from './types';

/** A new block of a type, with the fields the schema requires present and empty. */
export function emptyBlock(type: BlockType): Block {
  switch (type) {
    case 'heading':
      return { type, level: 2, text: '' };
    case 'paragraph':
      return { type, text: '' };
    case 'table':
      return { type, header: ['', ''], rows: [['', '']] };
    case 'image':
      return { type, imageMediaId: '', alt: '' };
    case 'audio':
      return { type, audioMediaId: '' };
    case 'video-upload':
      return { type, videoMediaId: '' };
    case 'video-embed':
      return { type, provider: 'youtube', url: '' };
    case 'callout':
      return { type, kind: 'tip', text: '' };
    case 'embedded-question':
      return { type, questionVersionId: '' };
    case 'worked-example':
      return { type, title: '', problem: '', steps: [''] };
  }
}

/** The only hosts a frame may show; the page's CSP frame-src names the same two origins, and the server checks the host against e9x.content.strategy.video-hosts. */
const YOUTUBE_HOSTS = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtu.be']);
const VIMEO_HOSTS = new Set(['vimeo.com', 'www.vimeo.com', 'player.vimeo.com']);

/**
 * The address to put in the frame for a video-embed block, or null when it is not a YouTube / Vimeo https address of that provider. Never the author's
 * string: the id is read out and the frame address is rebuilt on the provider's own embed origin.
 */
export function videoEmbedSrc(provider: Provider, url: string): string | null {
  let u: URL;
  try {
    u = new URL(url.trim());
  } catch {
    return null;
  }
  if (u.protocol !== 'https:' || u.username || u.password) return null;
  const host = u.hostname.toLowerCase();
  if (provider === 'youtube') {
    if (!YOUTUBE_HOSTS.has(host)) return null;
    let id: string | null = null;
    if (host === 'youtu.be') id = u.pathname.slice(1);
    else if (u.pathname.startsWith('/embed/')) id = u.pathname.slice('/embed/'.length);
    else if (u.pathname === '/watch') id = u.searchParams.get('v');
    return id && /^[A-Za-z0-9_-]{6,20}$/.test(id) ? `https://www.youtube.com/embed/${id}` : null;
  }
  if (!VIMEO_HOSTS.has(host)) return null;
  const id = u.pathname.slice(u.pathname.lastIndexOf('/') + 1);
  return /^[0-9]{4,12}$/.test(id) ? `https://player.vimeo.com/video/${id}` : null;
}
