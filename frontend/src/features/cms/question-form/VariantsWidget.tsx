import type { WidgetProps } from '@rjsf/utils';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

const parse = (text: string): string[] => text.split('\n').map((l) => l.trim()).filter((l) => l !== '');
const same = (a: string[], b: string[]) => a.length === b.length && a.every((x, i) => x === b[i]);

/**
 * The widget of the accepted answers of one gap: every spelling that counts as right, one per line. (The answer key of a gap is a list of
 * variants; the server's scorer compares a learner's answer with each.) The text being typed is kept here, so a new empty line stays while the
 * author writes on it; the form gets the lines that are not empty.
 */
export function VariantsWidget({ id, value, onChange, disabled, readonly, rawErrors }: WidgetProps) {
  const { t } = useTranslation('questions');
  const list = Array.isArray(value) ? (value as unknown[]).filter((v): v is string => typeof v === 'string') : [];
  const [text, setText] = useState(list.join('\n'));
  // somebody else changed the value (the JSON text, a reload): show it; what is being typed parses to the same list and stays as it is
  if (!same(parse(text), list)) setText(list.join('\n'));
  const slot = Number(/_(\d+)$/.exec(id)?.[1] ?? 0) + 1;
  return (
    <div className="flex flex-col gap-1">
      <textarea
        id={id}
        rows={Math.max(2, Math.min(8, list.length + 1))}
        value={text}
        disabled={disabled || readonly}
        spellCheck={false}
        aria-label={t('variants.label', { n: slot })}
        aria-describedby={`${id}-help`}
        aria-invalid={rawErrors && rawErrors.length > 0 ? true : undefined}
        onChange={(e) => {
          setText(e.target.value);
          onChange(parse(e.target.value));
        }}
        className="w-full rounded-md border border-edge bg-surface px-3 py-2 font-mono text-sm text-ink"
      />
      <p id={`${id}-help`} className="text-sm text-muted">
        {t('variants.help')}
      </p>
    </div>
  );
}
