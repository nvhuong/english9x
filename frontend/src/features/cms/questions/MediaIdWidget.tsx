import type { WidgetProps } from '@rjsf/utils';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../../design';
import { MediaPreview, MediaUploader, type MediaKind } from '../media';

/**
 * The widget of every field that holds the id of a stored file ({@code audioMediaId}, {@code imageMediaId}, ...): the id itself (paste one, or
 * leave it empty), a preview of the file it names, and the media uploader, which fills the field with the new file's id. The exam comes from the
 * form's context: a question can only point at files of its own exam, and the server checks that again.
 */
export function MediaIdWidget({ id, value, onChange, onBlur, onFocus, disabled, readonly, options, registry, rawErrors }: WidgetProps) {
  const { t } = useTranslation('questions');
  const [preview, setPreview] = useState(false);
  const examCode = (registry.formContext as { examCode: string }).examCode;
  const kind = ((options.kind as MediaKind | undefined) ?? 'AUDIO') as MediaKind;
  const id_ = typeof value === 'string' ? value : '';
  const locked = disabled || readonly;
  return (
    <div className="flex flex-col gap-2">
      <input
        id={id}
        type="text"
        value={id_}
        disabled={locked}
        aria-invalid={rawErrors && rawErrors.length > 0 ? true : undefined}
        aria-describedby={`${id}-help`}
        autoComplete="off"
        spellCheck={false}
        onChange={(e) => onChange(e.target.value === '' ? undefined : e.target.value.trim())}
        onBlur={() => onBlur(id, value)}
        onFocus={() => onFocus(id, value)}
        className="min-h-10 rounded-md border border-edge bg-surface px-3 font-mono text-sm text-ink"
      />
      <p id={`${id}-help`} className="text-sm text-muted">
        {t(`media.help.${kind}`)}
      </p>
      {id_ && (
        <div className="flex flex-col gap-2">
          <Button variant="secondary" size="sm" className="self-start" aria-expanded={preview} onClick={() => setPreview(!preview)}>
            {preview ? t('media.hidePreview') : t('media.showPreview')}
          </Button>
          {preview && <MediaPreview mediaId={id_} kind={kind} name={id_} />}
        </div>
      )}
      {!locked && (
        <details className="rounded-md border border-line bg-surface p-2">
          <summary className="cursor-pointer text-sm font-semibold text-ink">{t('media.uploadNew')}</summary>
          <div className="mt-2">
            <MediaUploader examCode={examCode} onUploaded={(m) => m.id && onChange(m.id)} />
          </div>
        </details>
      )}
    </div>
  );
}
