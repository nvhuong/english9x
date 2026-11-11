import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AudioPlayer } from '../../design';
import { ExamHttpError, examApi } from './examApi';

type Link = { state: 'loading' } | { state: 'ready'; url: string } | { state: 'gone' } | { state: 'error' };

// One link per (attempt, file) for the life of the page: the server gives it out once, and a re-render (or a second mount) must not use it up.
const links = new Map<string, Promise<Link>>();
// Files whose playback has started in this page: a second mount (navigating away and back) must not start them again.
const played = new Set<string>();
export function resetAudioLinks() {
  links.clear();
  played.clear();
}
function linkFor(attemptId: string, mediaId: string): Promise<Link> {
  const key = `${attemptId}/${mediaId}`;
  let p = links.get(key);
  if (!p) {
    p = examApi.audio(attemptId, mediaId).then(
      (l): Link => ({ state: 'ready', url: l.url }),
      (e: unknown): Link => (e instanceof ExamHttpError && e.status === 410 ? { state: 'gone' } : { state: 'error' }),
    );
    links.set(key, p);
  }
  return p;
}

const SESSION_ACTIONS = ['pause', 'seekbackward', 'seekforward', 'seekto', 'previoustrack', 'nexttrack'] as const;

/** Takes the pause / seek keys of headphones and the lock screen away from the page (Media Session). */
export function lockMediaSession(): void {
  if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
  for (const a of SESSION_ACTIONS) {
    try {
      navigator.mediaSession.setActionHandler(a as MediaSessionAction, null);
    } catch {
      /* action not supported by this browser */
    }
  }
}

export interface ListeningPlayerProps {
  attemptId: string;
  mediaId: string;
  onStart?: () => void;
  onEnded?: () => void;
}

/**
 * Listening audio of the exam room: plays once, with no pause, seek, replay or speed control (the design-system AudioPlayer has only volume). The server gives
 * the file out ONCE (410 afterwards: after a reload the recording is not offered again, and the page says so).
 */
export function ListeningPlayer({ attemptId, mediaId, onStart, onEnded }: ListeningPlayerProps) {
  const { t } = useTranslation('exam');
  const id = `${attemptId}/${mediaId}`;
  // decided once, at mount: playback that starts now must not turn the player into this message on the next render
  const [alreadyPlayed] = useState(() => played.has(id));
  const [loaded, setLoaded] = useState<{ id: string; link: Link } | null>(null);
  const link = useMemo<Link>(() => (loaded?.id === id ? loaded.link : { state: 'loading' }), [loaded, id]);
  useEffect(() => {
    let live = true;
    void linkFor(attemptId, mediaId).then((l) => live && setLoaded({ id, link: l }));
    return () => {
      live = false;
    };
  }, [attemptId, mediaId, id]);
  useEffect(() => {
    lockMediaSession();
  }, [link]);
  useEffect(() => {
    lockMediaSession();
  }, []);

  if (alreadyPlayed) return <p role="status" className="rounded-md border border-edge bg-sunken p-3 text-ink">{t('audio.gone')}</p>;
  if (link.state === 'loading') return <p role="status" className="text-muted">{t('audio.loading')}</p>;
  if (link.state === 'gone') return <p role="status" className="rounded-md border border-edge bg-sunken p-3 text-ink">{t('audio.gone')}</p>;
  if (link.state === 'error') return <p role="alert" className="text-danger">{t('audio.error')}</p>;
  // key: a different file is a new player (its "already played" memory starts again)
  return <AudioPlayer key={link.url} src={link.url} onStart={() => {
      played.add(id);
      lockMediaSession(); // some browsers register their handlers again when playback starts
      onStart?.();
    }} onEnded={onEnded} />;
}
