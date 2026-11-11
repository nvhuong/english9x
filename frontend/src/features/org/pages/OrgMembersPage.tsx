import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { NotFoundPage } from '../../../app/pages/NotFoundPage';
import { useContextStore } from '../../../app/stores/context';
import { InvitationList } from '../InvitationList';
import { InviteForm } from '../InviteForm';
import { BulkInvitePanel } from '../members/BulkInvitePanel';
import { JoinCodesPanel } from '../members/JoinCodesPanel';
import { MembersPanel } from '../MembersPanel';
import { useOrgStore } from '../orgContext';

/**
 * The ORG_ADMIN's page for their organisation: /org/members (the current context) or /org/:orgId/members. The id in the address
 * is asked of the server as written, together with the context header: for an organisation that is not the current context the
 * server answers 404, and the page shows "not found" without any data. (The page never decides who may see what.)
 */
export default function OrgMembersPage() {
  const { t } = useTranslation('org');
  const params = useParams<{ orgId: string }>();
  const ctxOrgId = useContextStore((s) => s.orgId);
  const orgs = useOrgStore((s) => s.orgs);
  const orgId = params.orgId ?? ctxOrgId;
  if (!orgId) return <NotFoundPage />;
  const known = orgs.find((o) => o.id === orgId);
  // the type only decides which role boxes to draw; for an organisation not in my list there is nothing to draw and the server will say 404
  const type = known?.type ?? 'CENTER';
  return (
    <div className="flex flex-col gap-8">
      <div>
        <h1 className="font-serif text-3xl font-bold text-ink">{known ? t('orgPage.title', { name: known.name }) : t('members.title')}</h1>
        {known && <p className="text-muted">{t('orgPage.type', { type: t(`types.${known.type}`) })}</p>}
      </div>
      <section aria-labelledby="members" className="flex flex-col gap-3">
        <h2 id="members" className="font-serif text-xl font-semibold">
          {t('members.title')}
        </h2>
        <MembersPanel orgId={orgId} type={type} />
      </section>
      {known && (
        <section aria-labelledby="invite" className="flex flex-col gap-3">
          <h2 id="invite" className="font-serif text-xl font-semibold">
            {t('invite.title')}
          </h2>
          <p className="max-w-prose text-muted">{t('invite.description')}</p>
          <InviteForm scope={known.type} orgId={orgId} />
          <InvitationList orgId={orgId} />
        </section>
      )}
      {known && (
        <section aria-labelledby="bulk" className="flex flex-col gap-3">
          <h2 id="bulk" className="font-serif text-xl font-semibold">
            {t('bulk.title')}
          </h2>
          <BulkInvitePanel orgId={orgId} />
        </section>
      )}
      {known && (
        <section aria-labelledby="join-codes" className="flex flex-col gap-3">
          <h2 id="join-codes" className="font-serif text-xl font-semibold">
            {t('joinCodes.title')}
          </h2>
          <p className="max-w-prose text-muted">{t('joinCodes.description')}</p>
          <JoinCodesPanel orgId={orgId} />
        </section>
      )}
    </div>
  );
}
