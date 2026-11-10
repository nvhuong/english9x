import type { FieldProps } from '@rjsf/utils';
import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../../design';
import i18n from '../../../i18n';
import type { Json, JsonObject } from '../questions/types';

type Column = { name: string; numeric: boolean };

/** The columns of a list of flat records (options, choices, items: a key and a text): every property that is a string or a number. */
export function columnsOf(itemSchema: unknown): Column[] | null {
  const props = (itemSchema as { type?: unknown; properties?: Record<string, { type?: unknown }> } | undefined)?.properties;
  if (!props || (itemSchema as { type?: unknown }).type !== 'object') return null;
  const cols = Object.entries(props).map(([name, s]) => ({ name, type: s?.type }));
  if (cols.length === 0 || cols.some((c) => c.type !== 'string' && c.type !== 'number' && c.type !== 'integer')) return null;
  return cols.map((c) => ({ name: c.name, numeric: c.type !== 'string' }));
}

/**
 * The field of a list of flat records, shown as a table: one row per record, one input per column, a button to add a row and one to remove it.
 * Nothing is checked here (the server holds the rules): a cell left empty is simply not in the record.
 */
export function RowsTableField({ formData, schema, uiSchema, onChange, disabled, readonly, fieldPathId, errorSchema }: FieldProps) {
  const { t } = useTranslation('questions');
  const captionId = useId();
  const cols = columnsOf((schema as { items?: unknown }).items) ?? [];
  const rows = (Array.isArray(formData) ? formData : []) as JsonObject[];
  const locked = Boolean(disabled || readonly);
  const title = (uiSchema?.['ui:title'] as string | undefined) ?? schema.title ?? '';
  const columnLabel = (name: string) => (i18n.exists(`questions:fields.${name}`) ? t(`fields.${name}`) : name);

  const commit = (next: JsonObject[]) => onChange(next as unknown as Json[], fieldPathId.path);
  const errorsAt = (r: number, col: string): string[] => ((errorSchema as Record<string, Record<string, { __errors?: string[] }>> | undefined)?.[r]?.[col]?.__errors ?? []);
  const setCell = (r: number, col: Column, raw: string) => {
    const next = rows.map((row) => ({ ...row }));
    const row = next[r]!;
    if (raw === '') delete row[col.name];
    else row[col.name] = (col.numeric ? Number(raw) : raw) as Json;
    commit(next);
  };

  return (
    <div id={fieldPathId.$id} className="flex flex-col gap-2">
      <table aria-label={title} aria-describedby={captionId} className="w-full border-collapse text-sm">
        <thead>
          <tr>
            <th scope="col" className="w-10 px-1 py-1 text-left font-semibold text-muted">
              #
            </th>
            {cols.map((c) => (
              <th key={c.name} scope="col" className="px-1 py-1 text-left font-semibold text-ink">
                {columnLabel(c.name)}
              </th>
            ))}
            <th scope="col" className="w-24 px-1 py-1">
              <span className="sr-only">{t('rows.actions')}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, r) => (
            <tr key={r}>
              <td className="px-1 py-1 text-muted">{r + 1}</td>
              {cols.map((c) => {
                const cellId = `${fieldPathId.$id}_${r}_${c.name}`;
                const errors = errorsAt(r, c.name);
                return (
                  <td key={c.name} className="px-1 py-1">
                    <input
                      id={cellId}
                      aria-invalid={errors.length > 0 ? true : undefined}
                      aria-describedby={errors.length > 0 ? `${cellId}-error` : undefined}
                      type={c.numeric ? 'number' : 'text'}
                      value={row[c.name] === undefined || row[c.name] === null ? '' : String(row[c.name])}
                      disabled={locked}
                      aria-label={t('rows.cell', { column: columnLabel(c.name), n: r + 1 })}
                      onChange={(e) => setCell(r, c, e.target.value)}
                      className="min-h-9 w-full rounded-md border border-edge bg-surface px-2 text-ink"
                    />
                    {errors.length > 0 && (
                      <span id={`${cellId}-error`} className="text-xs text-danger">
                        {errors.join(' ')}
                      </span>
                    )}
                  </td>
                );
              })}
              <td className="px-1 py-1">
                {!locked && (
                  <Button variant="secondary" size="sm" aria-label={t('rows.removeRow', { n: r + 1 })} onClick={() => commit(rows.filter((_, i) => i !== r))}>
                    {t('rows.remove')}
                  </Button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p id={captionId} className="text-sm text-muted">
        {rows.length === 0 ? t('rjsf.emptyList') : t('rows.count', { count: rows.length })}
      </p>
      {!locked && (
        <Button variant="secondary" size="sm" className="self-start" onClick={() => commit([...rows, {}])}>
          {t('rows.add')}
        </Button>
      )}
    </div>
  );
}
