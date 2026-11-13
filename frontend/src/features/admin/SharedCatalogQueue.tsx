import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiProblemError } from '../../api/problem';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { useRoleStore } from '../../app/stores/roles';
import { Button, Dialog, EmptyState, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { isEnglish9xReviewer, isOwnerMember, useSharedCatalogDecision, useSharedCatalogQueue, type SharedCatalogQueueItem } from './sharedCatalog';

/** /cms/shared-catalog: the courses centres offer to the English9x shared catalogue (F21). An English9x REVIEWER of the exam approves or declines; the owner never decides on their own course. */
export default function SharedCatalogQueue() {
  const { t } = useTranslation('sharedcatalog');
  const grants = useRoleStore((s) => s.grants);
  const queue = useSharedCatalogQueue();
  const { approve, reject } = useSharedCatalogDecision();
  const [rejecting, setRejecting] = useState<SharedCatalogQueueItem | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (queue.isError && queue.error instanceof ApiProblemError && (queue.error.status === 403 || queue.error.status === 404)) return <ForbiddenPage />;

  const fail = (e: unknown) => {
    setNotice(null);
    setError(e instanceof ApiProblemError && e.status === 409 ? t('errors.alreadyDecided') : e instanceof ApiProblemError && (e.status === 403 || e.status === 404) ? t('errors.notAllowed') : t('errors.failed'));
  };

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('description')}</p>
      </header>
      {error && <FormAlert>{error}</FormAlert>}
      {notice && <FormNotice>{notice}</FormNotice>}
      {queue.isPending && (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      )}
      {queue.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('errors.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void queue.refetch()}>
            {t('retry')}
          </Button>
        </div>
      )}
      {queue.data && queue.data.length === 0 && <EmptyState icon="📚" title={t('empty')} description={t('emptyHint')} />}
      {queue.data && queue.data.length > 0 && (
        <Table caption={t('caption')} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('col.course')}</TableHeaderCell>
              <TableHeaderCell>{t('col.exam')}</TableHeaderCell>
              <TableHeaderCell>{t('col.version')}</TableHeaderCell>
              <TableHeaderCell>{t('col.center')}</TableHeaderCell>
              <TableHeaderCell>{t('col.actions')}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {queue.data.map((c) => {
              const mine = isOwnerMember(grants, c.ownerOrgId);
              const canDecide = isEnglish9xReviewer(grants, c.examCode) && !mine;
              return (
                <TableRow key={c.courseId} data-testid="shared-row">
                  <TableCell>
                    {c.title}
                    <br />
                    <span className="font-mono text-muted">{c.code}</span>
                  </TableCell>
                  <TableCell>{c.examCode}</TableCell>
                  <TableCell>{t('version', { n: c.versionNo })}</TableCell>
                  <TableCell>
                    {c.ownerName}
                    <br />
                    <span className="text-muted">{c.authorDisplay}</span>
                  </TableCell>
                  <TableCell>
                    {canDecide ? (
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          loading={approve.isPending && approve.variables === c.courseId}
                          aria-label={t('approveFor', { title: c.title })}
                          onClick={() => {
                            setError(null);
                            approve.mutate(c.courseId!, { onSuccess: () => setNotice(t('approved', { title: c.title })), onError: fail });
                          }}
                        >
                          {t('approve')}
                        </Button>
                        <Button size="sm" variant="danger" aria-label={t('rejectFor', { title: c.title })} onClick={() => { setError(null); setRejecting(c); }}>
                          {t('reject')}
                        </Button>
                      </div>
                    ) : (
                      <span className="text-sm text-muted">{mine ? t('ownCourse') : t('noPermission')}</span>
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}

      <Dialog
        open={rejecting !== null}
        onOpenChange={(open) => !open && setRejecting(null)}
        title={t('rejectTitle', { title: rejecting?.title ?? '' })}
        description={t('rejectDescription')}
        footer={
          <>
            <Button variant="secondary" onClick={() => setRejecting(null)}>
              {t('cancel')}
            </Button>
            <Button
              variant="danger"
              loading={reject.isPending}
              onClick={() => rejecting && reject.mutate(rejecting.courseId!, { onSuccess: () => { setNotice(t('rejected', { title: rejecting.title })); setRejecting(null); }, onError: (e) => { setRejecting(null); fail(e); } })}
            >
              {t('rejectSubmit')}
            </Button>
          </>
        }
      >
        <p className="text-muted">{t('rejectBody')}</p>
      </Dialog>
    </div>
  );
}
