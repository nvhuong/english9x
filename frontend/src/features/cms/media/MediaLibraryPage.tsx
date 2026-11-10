import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { Button, EmptyState, Select } from '../../../design';
import { formatBytes, formatDuration } from './format';
import { MediaPreview } from './MediaPreview';
import { MediaUploader } from './MediaUploader';
import type { MediaView } from './types';

/** /cms/media: upload a file for an exam you author, and open the ones uploaded in this session. (There is no listing endpoint: content points at files by id.) */
export default function MediaLibraryPage() {
  const { t } = useTranslation('media');
  const grants = useRoleStore((s) => s.grants);
  const ctx = useContextStore((s) => s.orgId);
  const exams = useMemo(
    () => [...new Set((grants ?? []).filter((g) => g.role === 'AUTHOR' && g.exam && (g.org === null || g.org === ctx)).map((g) => g.exam as string))].sort(),
    [grants, ctx],
  );
  const [chosen, setChosen] = useState<string | undefined>();
  const exam = chosen && exams.includes(chosen) ? chosen : exams[0];
  const [uploaded, setUploaded] = useState<MediaView[]>([]);
  const [open, setOpen] = useState<string | null>(null);

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('description')}</p>
      </header>

      {!exam ? (
        <EmptyState title={t('exam.noneTitle')} description={t('exam.none')} />
      ) : (
        <>
          {exams.length > 1 && (
            <Select label={t('exam.label')} options={exams.map((e) => ({ value: e, label: e }))} value={exam} onValueChange={setChosen} className="max-w-xs" />
          )}
          <MediaUploader examCode={exam} onUploaded={(m) => setUploaded((list) => [m, ...list])} />
        </>
      )}

      <section aria-labelledby="media-session" className="flex flex-col gap-3">
        <h2 id="media-session" className="font-serif text-xl font-semibold text-ink">
          {t('session.title')}
        </h2>
        {uploaded.length === 0 ? (
          <p className="text-muted">{t('session.empty')}</p>
        ) : (
          <ul className="flex flex-col gap-3">
            {uploaded.map((m) => (
              <li key={m.id} className="rounded-lg border border-line bg-surface p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="text-ink">
                    <span className="font-semibold">{m.originalFilename}</span>{' '}
                    <span className="text-sm text-muted">
                      {t(`kind.${m.kind ?? 'AUDIO'}`)}
                      {m.durationMs ? ` · ${formatDuration(m.durationMs)}` : ''}
                      {m.sizeBytes ? ` · ${formatBytes(m.sizeBytes)}` : ''}
                    </span>
                  </p>
                  <Button variant="secondary" size="sm" aria-expanded={open === m.id} onClick={() => setOpen(open === m.id ? null : (m.id ?? null))}>
                    {open === m.id ? t('preview.hide') : t('preview.show')}
                  </Button>
                </div>
                <p className="mt-1 break-all text-xs text-muted">
                  {t('done.id')}: <code>{m.id}</code>
                </p>
                {open === m.id && m.id && m.kind && (
                  <div className="mt-3">
                    <MediaPreview mediaId={m.id} kind={m.kind} name={m.originalFilename ?? ''} />
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
