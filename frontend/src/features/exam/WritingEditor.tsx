import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { countWords } from './kinds';

export interface WritingEditorProps {
  value: string;
  onChange: (value: string) => void;
  minWords?: number;
  label?: string;
}

/** Essay box: no spell-check, auto-correct or autocomplete (it is an exam), with a live word count. */
export function WritingEditor({ value, onChange, minWords, label }: WritingEditorProps) {
  const { t } = useTranslation('exam');
  const id = useId();
  const words = countWords(value);
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-sm font-semibold text-ink">{label ?? t('writing.yourAnswer')}</label>
      <textarea
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        spellCheck={false}
        autoCorrect="off"
        autoComplete="off"
        autoCapitalize="off"
        rows={14}
        aria-describedby={`${id}-count`}
        className="w-full rounded-md border border-edge bg-surface p-3 text-ink"
      />
      <p id={`${id}-count`} className="text-sm text-muted">
        <span data-testid="word-count">{t('writing.words', { count: words })}</span>
        {minWords ? ` · ${t('writing.minWords', { count: minWords })}` : ''}
        {minWords && words < minWords ? ` · ${t('writing.short', { count: minWords - words })}` : ''}
      </p>
    </div>
  );
}
