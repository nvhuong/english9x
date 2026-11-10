import { useMutation } from '@tanstack/react-query';
import { useId, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { Badge, Button, EmptyState, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow, type BadgeTone } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { importProblemText } from './problems';
import { useDownload, useImportJob, useSubmitImport } from './queries';
import { useQuestionTypeForms } from '../questions/queries';
import { isRunning } from './types';
import { startImport } from './upload';

const TONE: Record<string, BadgeTone> = { QUEUED: 'info', RUNNING: 'info', SUCCEEDED: 'success', REJECTED: 'danger', FAILED: 'danger' };

/**
 * /cms/import (F11): download the template of a question type, fill it in, upload it (and the zip of the media files its rows name). A background job checks
 * EVERY row; one error rejects the whole file and nothing is created: the errors are listed by row and column, and the workbook comes back with the failed
 * cells red. When every row is fine all questions are made as drafts, and the whole lot can then be sent for review in one go.
 */
export default function ImportPage() {
  const { t } = useTranslation('import');
  const grants = useRoleStore((s) => s.grants);
  const ctx = useContextStore((s) => s.orgId);
  const types = useQuestionTypeForms();
  const exams = useMemo(
    () => new Set((grants ?? []).filter((g) => g.role === 'AUTHOR' && g.exam && (g.org === null || g.org === ctx)).map((g) => g.exam as string)),
    [grants, ctx],
  );
  const options = useMemo(() => (types.data ?? []).filter((x) => exams.has(x.examCode)).map((x) => ({ value: x.code, label: x.code })), [types.data, exams]);

  const [chosen, setChosen] = useState<string | undefined>();
  const typeCode = chosen && options.some((o) => o.value === chosen) ? chosen : options[0]?.value;
  const xlsxId = useId();
  const zipId = useId();
  const xlsxInput = useRef<HTMLInputElement>(null);
  const zipInput = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [zip, setZip] = useState<File | null>(null);
  const [progress, setProgress] = useState(0);
  const [problem, setProblem] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);

  const download = useDownload();
  const job = useImportJob(jobId);
  const submit = useSubmitImport();

  const send = useMutation({
    mutationFn: async () => {
      abort.current = new AbortController();
      return startImport({ typeCode: typeCode!, file: file!, mediaZip: zip, onProgress: setProgress, signal: abort.current.signal });
    },
    onSuccess: (id) => {
      setJobId(id);
      setFile(null);
      setZip(null);
      if (xlsxInput.current) xlsxInput.current.value = '';
      if (zipInput.current) zipInput.current.value = '';
    },
    onError: (err) => {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      setProblem(importProblemText(t, err));
    },
  });

  const run = (fn: () => Promise<unknown>) => {
    setProblem(null);
    fn().catch((e) => setProblem(importProblemText(t, e)));
  };

  const detail = job.data;
  const status = detail?.job?.status;
  const errors = detail?.errors ?? [];
  const busy = send.isPending;

  if (types.isSuccess && options.length === 0) {
    return (
      <div className="flex flex-col gap-6">
        <h1 className="font-serif text-3xl font-bold text-ink">{t('title')}</h1>
        <EmptyState title={t('none.title')} description={t('none.description')} />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('description')}</p>
      </header>

      <section aria-labelledby="import-step1" className="flex flex-col gap-3 rounded-lg border border-line bg-surface p-4">
        <h2 id="import-step1" className="font-serif text-xl font-semibold text-ink">
          {t('step1.title')}
        </h2>
        <Select label={t('type.label')} options={options} value={typeCode} onValueChange={setChosen} className="max-w-xl" />
        <div>
          <Button variant="secondary" disabled={!typeCode} onClick={() => run(() => download.template(typeCode!))}>
            {t('step1.download')}
          </Button>
        </div>
      </section>

      <section aria-labelledby="import-step2" className="flex flex-col gap-3 rounded-lg border border-line bg-surface p-4">
        <h2 id="import-step2" className="font-serif text-xl font-semibold text-ink">
          {t('step2.title')}
        </h2>
        <div className="flex flex-col gap-1">
          <label htmlFor={xlsxId} className="text-sm font-semibold text-ink">
            {t('step2.fileLabel')}
          </label>
          <input
            ref={xlsxInput}
            id={xlsxId}
            type="file"
            accept=".xlsx"
            disabled={busy}
            onChange={(e) => {
              setProblem(null);
              setFile(e.target.files?.[0] ?? null);
            }}
            className="text-base text-ink file:mr-3 file:min-h-10 file:rounded-md file:border file:border-edge file:bg-surface file:px-4 file:text-ink"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor={zipId} className="text-sm font-semibold text-ink">
            {t('step2.zipLabel')}
          </label>
          <input
            ref={zipInput}
            id={zipId}
            type="file"
            accept=".zip"
            disabled={busy}
            onChange={(e) => setZip(e.target.files?.[0] ?? null)}
            className="text-base text-ink file:mr-3 file:min-h-10 file:rounded-md file:border file:border-edge file:bg-surface file:px-4 file:text-ink"
          />
          <p className="text-sm text-muted">{t('step2.zipHint')}</p>
        </div>
        {problem && <FormAlert>{problem}</FormAlert>}
        {busy && (
          <div
            role="progressbar"
            aria-label={t('progress.label')}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(progress * 100)}
            className="h-2 w-full overflow-hidden rounded-full bg-sunken"
          >
            <div className="h-full bg-primary transition-[width]" style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
        )}
        <div>
          <Button
            disabled={!file || !typeCode || busy}
            loading={busy}
            onClick={() => {
              setProblem(null);
              setJobId(null);
              setProgress(0);
              send.mutate();
            }}
          >
            {t('step2.start')}
          </Button>
        </div>
      </section>

      {jobId && (
        <section aria-labelledby="import-result" className="flex flex-col gap-3 rounded-lg border border-line bg-surface p-4">
          <h2 id="import-result" className="font-serif text-xl font-semibold text-ink">
            {t('result.title')}
          </h2>
          {!detail || isRunning(status) ? (
            <p role="status" className="text-ink">
              {t('result.running', { done: detail?.job?.processedRows ?? 0, total: detail?.job?.totalRows ?? 0 })}
            </p>
          ) : (
            <>
              <p className="flex items-center gap-2 text-ink">
                <Badge tone={TONE[status ?? ''] ?? 'neutral'}>{t(`status.${status}`)}</Badge>
                <span role="status">{t(`result.${status}`, { count: detail.job?.createdCount ?? 0, errors: detail.job?.errorCount ?? 0 })}</span>
              </p>
              {status === 'SUCCEEDED' && (
                <>
                  {submit.isSuccess ? (
                    <FormNotice>{t('result.submitted')}</FormNotice>
                  ) : (
                    <div>
                      <Button loading={submit.isPending} onClick={() => run(() => submit.mutateAsync(jobId))}>
                        {t('result.submit')}
                      </Button>
                    </div>
                  )}
                </>
              )}
              {status === 'REJECTED' && (
                <>
                  <p className="text-muted">{t('result.nothingCreated')}</p>
                  {detail.job?.hasReport && (
                    <div>
                      <Button variant="secondary" onClick={() => run(() => download.report(jobId, `loi-${detail.job?.sourceName ?? 'import.xlsx'}`))}>
                        {t('result.downloadReport')}
                      </Button>
                    </div>
                  )}
                  <Table caption={t('errors.table')} dense>
                    <TableHead>
                      <TableRow>
                        <TableHeaderCell>{t('errors.row')}</TableHeaderCell>
                        <TableHeaderCell>{t('errors.column')}</TableHeaderCell>
                        <TableHeaderCell>{t('errors.message')}</TableHeaderCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {errors.map((e, i) => (
                        <TableRow key={`${e.row}-${e.column ?? ''}-${i}`}>
                          <TableCell>{e.row === 0 ? t('errors.wholeFile') : e.row}</TableCell>
                          <TableCell>{e.column ?? ''}</TableCell>
                          <TableCell>{e.message}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                  {(detail.job?.errorCount ?? 0) > errors.length && <p className="text-sm text-muted">{t('errors.truncated', { shown: errors.length, total: detail.job?.errorCount })}</p>}
                </>
              )}
              {status === 'FAILED' && <FormAlert>{t('result.failedHint')}</FormAlert>}
            </>
          )}
        </section>
      )}
    </div>
  );
}
