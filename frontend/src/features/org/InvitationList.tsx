import { useTranslation } from 'react-i18next';
import { Badge, Button, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow, type BadgeTone } from '../../design';
import { problemFromResponseSafe } from './util';
import { useInvitations, useRevokeInvitation } from './queries';
import { roleLabel } from './roles';

const tone: Record<string, BadgeTone> = { PENDING: 'info', ACCEPTED: 'success', REVOKED: 'neutral', EXPIRED: 'warning', VOIDED: 'warning' };

/** Invitations of an organisation (or of English9x staff when `orgId` is null), newest first, with revoke for the pending ones. */
export function InvitationList({ orgId }: { orgId: string | null }) {
  const { t, i18n } = useTranslation('org');
  const list = useInvitations(orgId);
  const revoke = useRevokeInvitation(orgId);
  if (list.isPending) return <p role="status" className="text-muted">{t('invitations.loading')}</p>;
  if (list.isError) return <p role="alert" className="text-danger">{problemFromResponseSafe(list.error, t)}</p>;
  if (list.data.length === 0) return <p className="text-muted">{t('invitations.empty')}</p>;
  const fmt = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });
  return (
    <Table caption={t('invitations.title')} dense>
      <TableHead>
        <TableRow>
          <TableHeaderCell>{t('invitations.email')}</TableHeaderCell>
          <TableHeaderCell>{t('invitations.roles')}</TableHeaderCell>
          <TableHeaderCell>{t('invitations.status')}</TableHeaderCell>
          <TableHeaderCell>{t('invitations.expires')}</TableHeaderCell>
          <TableHeaderCell>
            <span className="sr-only">{t('members.actions')}</span>
          </TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {list.data.map((inv) => (
          <TableRow key={inv.id}>
            <TableCell>{inv.email}</TableCell>
            <TableCell>
              <div className="flex flex-wrap gap-1">
                {inv.roles.map((r) => (
                  <Badge key={`${r.role}:${r.examCode ?? ''}`}>{roleLabel(t, r.role, r.examCode)}</Badge>
                ))}
              </div>
            </TableCell>
            <TableCell>
              <Badge tone={tone[inv.status] ?? 'neutral'}>{t(`status.${inv.status}`)}</Badge>
            </TableCell>
            <TableCell>{fmt.format(new Date(inv.expiresAt))}</TableCell>
            <TableCell>
              {inv.status === 'PENDING' && (
                <Button size="sm" variant="secondary" aria-label={t('invitations.revokeOf', { email: inv.email })} loading={revoke.isPending && revoke.variables === inv.id} onClick={() => revoke.mutate(inv.id)}>
                  {t('invitations.revoke')}
                </Button>
              )}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
