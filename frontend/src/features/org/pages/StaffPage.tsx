import { useTranslation } from 'react-i18next';
import { InvitationList } from '../InvitationList';
import { InviteForm } from '../InviteForm';

/** SYS_ADMIN: invite English9x staff (F04): authors, reviewers, teachers per exam, or system admins. */
export default function StaffPage() {
  const { t } = useTranslation('org');
  return (
    <div className="flex flex-col gap-8">
      <div>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('staff.title')}</h1>
        <p className="mt-1 max-w-prose text-muted">{t('staff.description')}</p>
      </div>
      <section aria-labelledby="invite" className="flex flex-col gap-3">
        <h2 id="invite" className="font-serif text-xl font-semibold">
          {t('invite.title')}
        </h2>
        <InviteForm scope="staff" orgId={null} />
      </section>
      <section aria-labelledby="sent" className="flex flex-col gap-3">
        <h2 id="sent" className="font-serif text-xl font-semibold">
          {t('invitations.title')}
        </h2>
        <InvitationList orgId={null} />
      </section>
    </div>
  );
}
