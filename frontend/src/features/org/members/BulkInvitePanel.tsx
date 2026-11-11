import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiProblemError } from '../../../api/problem';
import { Button } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { orgProblemMessage } from '../problems';
import { parseEmails, useBulkInvite, useImportMembers } from './queries';

/** Invite many learners at once: a pasted list of addresses, or an Excel file (email, name, class) whose bad rows are listed and nobody is invited until it is clean. */
export function BulkInvitePanel({ orgId }: { orgId: string }) {
  const { t } = useTranslation('org');
  const invite = useBulkInvite(orgId);
  const imp = useImportMembers(orgId);
  const [text, setText] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rowErrors, setRowErrors] = useState<{ row?: number | null; field?: string | null; code?: string | null }[]>([]);
  const fileRef = useRef<HTMLInputElement>(null);

  function reset() {
    setNotice(null);
    setError(null);
    setRowErrors([]);
  }

  return (
    <div className="flex max-w-xl flex-col gap-6">
      <form
        className="flex flex-col gap-3"
        aria-label={t('bulk.listTitle')}
        onSubmit={(e) => {
          e.preventDefault();
          reset();
          const emails = parseEmails(text);
          if (emails.length === 0) return;
          invite.mutate(emails, {
            onSuccess: (r) => {
              setNotice(t('bulk.invited', { count: r.invited }));
              setText('');
            },
            onError: (err) => setError(orgProblemMessage(err, t)),
          });
        }}
      >
        <label htmlFor="bulk-emails" className="text-sm font-semibold text-ink">
          {t('bulk.listLabel')}
        </label>
        <textarea
          id="bulk-emails"
          rows={4}
          className="rounded-md border border-line bg-surface px-3 py-2 text-ink"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        <div>
          <Button type="submit" loading={invite.isPending}>
            {t('bulk.send')}
          </Button>
        </div>
      </form>
      <form
        className="flex flex-col gap-3"
        aria-label={t('bulk.fileTitle')}
        onSubmit={(e) => {
          e.preventDefault();
          reset();
          const file = fileRef.current?.files?.[0];
          if (!file) return;
          imp.mutate(file, {
            onSuccess: (r) => setNotice(t('bulk.invited', { count: r.invited })),
            onError: (err) => {
              if (err instanceof ApiProblemError && err.errors.length > 0) setRowErrors(err.errors);
              setError(err instanceof ApiProblemError && err.status === 422 ? t('bulk.hasErrors') : orgProblemMessage(err, t));
            },
          });
        }}
      >
        <label htmlFor="bulk-file" className="text-sm font-semibold text-ink">
          {t('bulk.fileLabel')}
        </label>
        <p className="text-sm text-muted">{t('bulk.fileHint')}</p>
        <input id="bulk-file" ref={fileRef} type="file" accept=".xlsx" />
        <div>
          <Button type="submit" loading={imp.isPending}>
            {t('bulk.upload')}
          </Button>
        </div>
      </form>
      {notice && <FormNotice>{notice}</FormNotice>}
      {error && <FormAlert>{error}</FormAlert>}
      {rowErrors.length > 0 && (
        <ul className="list-disc pl-5 text-sm" aria-label={t('bulk.errorsLabel')}>
          {rowErrors.map((e, i) => (
            <li key={i}>{t('bulk.rowError', { row: e.row ?? '?', field: e.field ?? '', code: e.code ?? '' })}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
