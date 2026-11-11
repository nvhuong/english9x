import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { examApi } from './examApi';

/** An image of the question (plan, map, chart). A learner may not be offered the file yet (media access for learners): then a note stands in. */
export function ExamImage({ mediaId }: { mediaId: string }) {
  const { t } = useTranslation('exam');
  const [url, setUrl] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    let live = true;
    void examApi.mediaUrl(mediaId).then((u) => live && setUrl(u), () => live && setUrl(null));
    return () => {
      live = false;
    };
  }, [mediaId]);
  if (url === undefined) return null;
  if (url === null) return <p className="text-sm text-muted">{t('image.unavailable')}</p>;
  return <img src={url} alt={t('image.alt')} className="max-h-80 max-w-full rounded-md border border-line" />;
}
