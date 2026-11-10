import { useMutation } from '@tanstack/react-query';
import { useId, useRef, useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { extensionOf, formatBytes, formatDuration } from './format';
import { mediaProblemText } from './problems';
import { useMediaLimits } from './queries';
import type { MediaView } from './types';
import { uploadMedia } from './upload';

export interface MediaUploaderProps {
  /** The exam the file is for. The caller must be an author of it; the server checks, the form only asks. */
  examCode: string;
  onUploaded?: (media: MediaView) => void;
}

/**
 * Upload form for one media file (F12): choose, check type and size against the server's own limits, send with progress, show what the server found
 * (kind, length). Type and size are checked here only to save a long upload; the server decides again from the bytes and answers with a reason.
 * Used by the media page and, later, by the question, group and lesson editors.
 */
export function MediaUploader({ examCode, onUploaded }: MediaUploaderProps) {
  const { t } = useTranslation('media');
  const limits = useMediaLimits();
  const inputId = useId();
  const hintId = useId();
  const input = useRef<HTMLInputElement>(null);
  const abort = useRef<AbortController | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [result, setResult] = useState<MediaView | null>(null);

  const send = useMutation({
    mutationFn: async (f: File) => {
      abort.current = new AbortController();
      return uploadMedia({ examCode, file: f, onProgress: setProgress, signal: abort.current.signal });
    },
    onSuccess: (media) => {
      setResult(media);
      setFile(null);
      if (input.current) input.current.value = '';
      onUploaded?.(media);
    },
    onError: (err) => {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      setProblem(mediaProblemText(t, err));
    },
  });

  const max = limits.data?.maxUploadBytes;

  const choose = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0] ?? null;
    setResult(null);
    setProblem(null);
    setProgress(0);
    setFile(null);
    if (!f) return;
    const ext = extensionOf(f.name);
    if (f.size === 0) setProblem(t('errors.empty'));
    else if (limits.data?.extensions && !limits.data.extensions.includes(ext)) setProblem(t('errors.extensionClient', { ext: ext ? `.${ext}` : f.name }));
    else if (max !== undefined && f.size > max) setProblem(t('errors.tooLarge', { size: formatBytes(f.size), max: formatBytes(max) }));
    else setFile(f);
  };

  const percent = Math.round(progress * 100);
  const uploading = send.isPending;

  return (
    <section aria-labelledby={`${inputId}-title`} className="flex flex-col gap-3 rounded-lg border border-line bg-surface p-4">
      <h2 id={`${inputId}-title`} className="font-serif text-xl font-semibold text-ink">
        {t('uploader.title')}
      </h2>
      <div className="flex flex-col gap-1">
        <label htmlFor={inputId} className="text-sm font-semibold text-ink">
          {t('uploader.chooseLabel')}
        </label>
        <input
          ref={input}
          id={inputId}
          type="file"
          accept={limits.data?.extensions?.map((x) => `.${x}`).join(',')}
          disabled={uploading}
          onChange={choose}
          aria-describedby={hintId}
          className="text-base text-ink file:mr-3 file:min-h-10 file:rounded-md file:border file:border-edge file:bg-surface file:px-4 file:text-ink"
        />
        <p id={hintId} className="text-sm text-muted">
          {max !== undefined ? t('uploader.hint', { max: formatBytes(max) }) : t('uploader.hintLoading')}
        </p>
      </div>

      {file ? (
        <p className="text-sm text-ink">{t('uploader.selected', { name: file.name, size: formatBytes(file.size) })}</p>
      ) : (
        !result && <p className="text-sm text-muted">{t('uploader.none')}</p>
      )}

      {problem && <FormAlert>{problem}</FormAlert>}

      {uploading && (
        <div className="flex flex-col gap-1">
          <div
            role="progressbar"
            aria-label={t('progress.label')}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent}
            aria-valuetext={t('progress.text', { percent })}
            className="h-2 w-full overflow-hidden rounded-full bg-sunken"
          >
            <div className="h-full bg-primary transition-[width]" style={{ width: `${percent}%` }} />
          </div>
          <p role="status" className="text-sm text-muted">
            {percent >= 100 ? t('progress.processing') : t('progress.text', { percent })}
          </p>
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <Button
          disabled={!file || uploading}
          loading={uploading}
          onClick={() => {
            if (!file) return;
            setProblem(null);
            setResult(null);
            setProgress(0);
            send.mutate(file);
          }}
        >
          {t('uploader.upload')}
        </Button>
        {uploading && (
          <Button variant="secondary" onClick={() => abort.current?.abort()}>
            {t('uploader.cancel')}
          </Button>
        )}
      </div>

      {result && (
        <FormNotice>
          <span className="font-semibold">{t('done.title')}</span>{' '}
          {t('done.summary', { kind: t(`kind.${result.kind ?? 'AUDIO'}`), name: result.originalFilename })}
          {result.durationMs ? ` · ${t('done.duration', { duration: formatDuration(result.durationMs) })}` : ''}
          {result.sizeBytes ? ` · ${t('done.size', { size: formatBytes(result.sizeBytes) })}` : ''}
        </FormNotice>
      )}
    </section>
  );
}
