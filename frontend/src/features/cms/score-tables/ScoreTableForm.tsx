import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../../design';
import { TextArea } from '../lesson-editor/fields';
import type { FieldIssue } from './problems';
import { parseRows } from './rows';

export interface ScoreTableFormProps {
  /** The rows as typed: one line each, `raw scaled`. */
  text: string;
  onChange: (text: string) => void;
  onSave: () => void;
  issues: FieldIssue[];
  locked?: boolean;
  saving?: boolean;
  saveLabel: string;
  actions?: ReactNode;
}

/** The conversion table: a text area (type or paste from a sheet), the table as it will be sent, and the server's reasons per line. */
export function ScoreTableForm({ text, onChange, onSave, issues, locked = false, saving = false, saveLabel, actions }: ScoreTableFormProps) {
  const { t } = useTranslation('scoring');
  const previewId = useId();
  const rows = parseRows(text);
  const rowsIssue = issues.filter((i) => i.field === 'rows').map((i) => i.message).join(' ');

  return (
    <form
      className="flex flex-col gap-6"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        onSave();
      }}
    >
      <TextArea
        label={t('rows.label')}
        hint={t('rows.hint')}
        value={text}
        onValue={onChange}
        disabled={locked}
        rows={12}
        spellCheck={false}
        error={rowsIssue || undefined}
        className="max-w-xl font-mono"
      />
      <section aria-labelledby={previewId} className="flex flex-col gap-2">
        <h2 id={previewId} className="font-serif text-xl font-bold text-ink">
          {t('rows.preview', { count: rows.length })}
        </h2>
        {rows.length === 0 ? (
          <p className="text-muted">{t('rows.empty')}</p>
        ) : (
          <div className="max-h-96 max-w-xl overflow-auto">
            <Table caption={t('rows.caption')}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t('rows.colRaw')}</TableHeaderCell>
                  <TableHeaderCell>{t('rows.colScaled')}</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((r, i) => (
                  <TableRow key={i}>
                    <TableCell>{r.raw ?? '?'}</TableCell>
                    <TableCell>{r.scaled ?? '?'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </section>

      {issues.length > 0 && (
        <div role="alert" className="rounded-md border border-danger bg-danger-bg px-3 py-2 text-sm text-ink">
          <p className="font-semibold text-danger">{t('errors.invalid')}</p>
          <ul className="mt-1 list-disc pl-5">
            {issues.map((i, n) => (
              <li key={n}>
                <code className="text-xs">{i.field}</code>: {i.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {!locked && (
        <div className="flex flex-wrap gap-3">
          <Button type="submit" loading={saving}>
            {saveLabel}
          </Button>
          {actions}
        </div>
      )}
      {locked && actions}
    </form>
  );
}
