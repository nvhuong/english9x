import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../../design';
import { diffVersions, show } from './diff';

/**
 * The version under review against the earlier published one, field by field. A changed field is marked (`data-changed`) and named in words, not by colour
 * alone; unchanged fields can be hidden. With no earlier version every field is new.
 */
export function VersionDiff({ before, after }: { before?: unknown; after: unknown }) {
  const { t } = useTranslation('review');
  const [onlyChanged, setOnlyChanged] = useState(false);
  const rows = diffVersions(before, after);
  const shown = onlyChanged ? rows.filter((r) => r.status !== 'same') : rows;
  const changes = rows.filter((r) => r.status !== 'same').length;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p role="status" className="text-sm text-muted">
          {before == null ? t('diff.noPrevious') : t('diff.summary', { count: changes })}
        </p>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={onlyChanged} onChange={(e) => setOnlyChanged(e.target.checked)} />
          {t('diff.onlyChanged')}
        </label>
      </div>
      <div className="overflow-x-auto rounded-lg border border-line bg-surface">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">{t('diff.caption')}</caption>
          <thead>
            <tr className="border-b border-line bg-sunken text-muted">
              <th scope="col" className="px-3 py-2">{t('diff.field')}</th>
              <th scope="col" className="px-3 py-2">{t('diff.before')}</th>
              <th scope="col" className="px-3 py-2">{t('diff.after')}</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((r) => (
              <tr
                key={r.path}
                data-testid="diff-row"
                data-changed={r.status !== 'same'}
                data-status={r.status}
                className={cn('border-b border-line align-top last:border-b-0', r.status !== 'same' && 'bg-warning-bg')}
              >
                <th scope="row" className="px-3 py-2 font-mono text-xs font-normal">
                  <span data-testid="diff-path">{r.path}</span>
                  {r.status !== 'same' && <span className="ml-2 rounded bg-ink px-1.5 py-0.5 text-[10px] font-semibold uppercase text-surface">{t(`diff.status.${r.status}`)}</span>}
                </th>
                <td className="whitespace-pre-wrap break-words px-3 py-2">{r.status === 'added' ? '' : show(r.before)}</td>
                <td className="whitespace-pre-wrap break-words px-3 py-2">{r.status === 'removed' ? '' : show(r.after)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
