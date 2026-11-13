import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiProblemError } from '../../../api/problem';
import { useContextStore } from '../../../app/stores/context';
import { hasRole, useRoleStore } from '../../../app/stores/roles';
import { Badge, Button } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { useCourseMutations } from './queries';
import type { CourseVersion } from './types';

const TONE = { NONE: 'neutral', PENDING: 'info', APPROVED: 'success', REJECTED: 'danger' } as const;

/**
 * A centre offers its PUBLISHED course to the English9x shared catalogue (F21): NONE -> PENDING (share), PENDING -> NONE (withdraw), REJECTED -> PENDING (offer again).
 * Only in an organisation's context; the buttons follow the roles for convenience, the server decides (AUTHOR of the exam or ORG_ADMIN of the owner).
 */
export function SharedCatalogPanel({ version }: { version: CourseVersion }) {
  const { t } = useTranslation('sharedcatalog');
  const ctx = useContextStore((s) => s.orgId);
  const grants = useRoleStore((s) => s.grants);
  const { share, withdrawShare } = useCourseMutations();
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (!ctx || version.status !== 'PUBLISHED') return null;
  const state = version.sharedStatus ?? 'NONE';
  const canAct = hasRole(grants, ctx, 'ORG_ADMIN') || hasRole(grants, ctx, 'AUTHOR', version.examCode);

  const fail = (e: unknown) => {
    setNotice(null);
    setError(
      e instanceof ApiProblemError && e.status === 409 ? t('share.errors.conflict') : e instanceof ApiProblemError && (e.status === 403 || e.status === 404) ? t('share.errors.forbidden') : t('share.errors.failed'),
    );
  };

  return (
    <section aria-labelledby="share-h" className="flex max-w-3xl flex-col gap-3 rounded-md border border-edge p-4" data-testid="shared-panel">
      <h2 id="share-h" className="font-serif text-xl font-bold text-ink">
        {t('share.heading')}
      </h2>
      <p className="text-muted">{t('share.description')}</p>
      <p className="flex items-center gap-2 text-sm">
        <span>{t('share.status')}</span>
        <Badge tone={TONE[state]}>{t(`share.state.${state}`)}</Badge>
      </p>
      {state === 'REJECTED' && <FormNotice tone="info">{t('share.rejectedHint')}</FormNotice>}
      {notice && <FormNotice>{notice}</FormNotice>}
      {error && <FormAlert>{error}</FormAlert>}
      {canAct && (
        <div className="flex flex-wrap gap-3">
          {(state === 'NONE' || state === 'REJECTED') && (
            <Button
              type="button"
              loading={share.isPending}
              onClick={() => {
                setError(null);
                share.mutate(version.courseId, { onSuccess: () => setNotice(t('share.submitted')), onError: fail });
              }}
            >
              {state === 'REJECTED' ? t('share.resubmit') : t('share.submit')}
            </Button>
          )}
          {state === 'PENDING' && (
            <Button
              type="button"
              variant="secondary"
              loading={withdrawShare.isPending}
              onClick={() => {
                setError(null);
                withdrawShare.mutate(version.courseId, { onSuccess: () => setNotice(t('share.withdrawn')), onError: fail });
              }}
            >
              {t('share.withdraw')}
            </Button>
          )}
        </div>
      )}
    </section>
  );
}
