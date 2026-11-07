import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { NotFoundPage } from '../../../app/pages/NotFoundPage';
import { ForbiddenPage } from '../../../app/pages/ForbiddenPage';
import { InvitationList } from '../InvitationList';
import { InviteForm } from '../InviteForm';
import { MembersPanel } from '../MembersPanel';
import { useAllOrganizations } from '../queries';
import { pageState } from '../util';

/** SYS_ADMIN: one organisation's members and invitations, from the English9x context (a SYS_ADMIN is not a member). */
export default function OrganizationDetailPage() {
  const { t } = useTranslation('org');
  const { orgId } = useParams<{ orgId: string }>();
  const list = useAllOrganizations();
  if (!orgId) return <NotFoundPage />;
  if (list.isError && pageState(list.error) === 'forbidden') return <ForbiddenPage />;
  if (list.isPending) return <p role="status" className="text-muted">{t('members.loading')}</p>;
  const org = list.data?.find((o) => o.id === orgId);
  if (!org) return <NotFoundPage />;
  return (
    <div className="flex flex-col gap-8">
      <div>
        <Link to="/admin/organizations" className="text-sm font-semibold text-primary">
          ← {t('detail.back')}
        </Link>
        <h1 className="mt-2 font-serif text-3xl font-bold text-ink">{t('orgPage.title', { name: org.name })}</h1>
        <p className="text-muted">{t('orgPage.type', { type: t(`types.${org.type}`) })}</p>
      </div>
      <section aria-labelledby="members" className="flex flex-col gap-3">
        <h2 id="members" className="font-serif text-xl font-semibold">
          {t('members.title')}
        </h2>
        <MembersPanel orgId={org.id} type={org.type} />
      </section>
      <section aria-labelledby="invite" className="flex flex-col gap-3">
        <h2 id="invite" className="font-serif text-xl font-semibold">
          {t('detail.invite')}
        </h2>
        <p className="max-w-prose text-muted">{t('invite.description')}</p>
        <InviteForm scope={org.type} orgId={org.id} />
        <InvitationList orgId={org.id} />
      </section>
    </div>
  );
}
