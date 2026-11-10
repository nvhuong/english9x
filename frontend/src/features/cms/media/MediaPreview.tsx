import { useTranslation } from 'react-i18next';
import { mediaProblemText } from './problems';
import { useMediaLink } from './queries';
import type { MediaKind } from './types';

export interface MediaPreviewProps {
  mediaId: string;
  kind: MediaKind;
  /** Shown to assistive technology and as the image's alternative text. */
  name: string;
}

/**
 * Plays or shows a stored file through its signed link (same origin, about ten minutes; asked for again if the element fails after the link ran out). The CMS preview
 * uses the browser's own controls; the exam room's play-once player is a different component (design/AudioPlayer).
 */
export function MediaPreview({ mediaId, kind, name }: MediaPreviewProps) {
  const { t } = useTranslation('media');
  const link = useMediaLink(mediaId);
  if (link.isPending) {
    return (
      <p role="status" className="text-sm text-muted">
        {t('preview.loading')}
      </p>
    );
  }
  if (link.isError || !link.data?.url) {
    return (
      <p role="alert" className="text-sm font-medium text-danger">
        {mediaProblemText(t, link.error)}
      </p>
    );
  }
  const src = link.data.url;
  // The element failed: if the link has run out (a page left open), get a new one; if it has not, asking again would give the same answer.
  const onError = () => {
    const at = link.data?.expiresAt ? Date.parse(link.data.expiresAt) : 0;
    if (Date.now() >= at - 2_000) void link.refetch();
  };
  if (kind === 'AUDIO') return <audio controls preload="metadata" src={src} onError={onError} aria-label={t('preview.audio', { name })} className="w-full max-w-md" />;
  if (kind === 'VIDEO') return <video controls preload="metadata" src={src} onError={onError} aria-label={t('preview.video', { name })} className="max-h-72 w-full max-w-md rounded-md bg-sunken" />;
  return <img src={src} onError={onError} alt={t('preview.image', { name })} className="max-h-72 max-w-full rounded-md border border-line" />;
}
