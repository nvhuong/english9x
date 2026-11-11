import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Badge, Button, Dialog, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../design';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { NotFoundPage } from '../../app/pages/NotFoundPage';
import { FormAlert } from '../auth/components';
import { orgProblemMessage } from './problems';
import { useMembers, useSetRoles, type MemberView } from './queries';
import { RemoveMemberButton } from './members/RemoveMemberButton';
import { RolePicker } from './RolePicker';
import { roleLabel, rolesToKeys, type Scope } from './roles';
import { pageState } from './util';

/**
 * The members of one organisation and their roles; an admin edits one member's roles at a time (PUT replaces them). The page
 * shows exactly what the server answers: for an organisation the server does not show to this person it is "not found" (404)
 * or "no access" (403), never data. Hiding things here would be convenience only.
 */
export function MembersPanel({ orgId, type }: { orgId: string; type: 'CENTER' | 'ENTERPRISE' }) {
  const { t } = useTranslation('org');
  const members = useMembers(orgId);
  const [editing, setEditing] = useState<MemberView | null>(null);
  if (members.isPending) return <p role="status" className="text-muted">{t('members.loading')}</p>;
  if (members.isError) {
    const state = pageState(members.error);
    if (state === 'not-found') return <NotFoundPage />;
    if (state === 'forbidden') return <ForbiddenPage />;
    return (
      <div className="flex flex-col items-start gap-2">
        <FormAlert>{t('members.loadFailed')}</FormAlert>
        <Button variant="secondary" onClick={() => void members.refetch()}>
          {t('members.retry')}
        </Button>
      </div>
    );
  }
  return (
    <>
      <Table caption={t('members.caption')} dense>
        <TableHead>
          <TableRow>
            <TableHeaderCell>{t('members.member')}</TableHeaderCell>
            <TableHeaderCell>{t('members.roles')}</TableHeaderCell>
            <TableHeaderCell>
              <span className="sr-only">{t('members.actions')}</span>
            </TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {members.data.map((m) => (
            <TableRow key={m.userId}>
              <TableCell>
                <div className="font-semibold">{m.displayName ?? m.email}</div>
                <div className="text-muted">{m.email}</div>
              </TableCell>
              <TableCell>
                {m.roles.length === 0 ? (
                  <span className="text-muted">{t('members.noRoles')}</span>
                ) : (
                  <div className="flex flex-wrap gap-1">
                    {m.roles.map((r) => (
                      <Badge key={`${r.role}:${r.examCode ?? ''}`}>{roleLabel(t, r.role, r.examCode)}</Badge>
                    ))}
                  </div>
                )}
              </TableCell>
              <TableCell>
                <Button size="sm" variant="secondary" aria-label={t('members.editOf', { name: m.displayName ?? m.email })} onClick={() => setEditing(m)}>
                  {t('members.edit')}
                </Button>{' '}
                <RemoveMemberButton orgId={orgId} userId={m.userId} name={m.displayName ?? m.email} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {editing && <EditRoles key={editing.userId} orgId={orgId} type={type} member={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function EditRoles({ orgId, type, member, onClose }: { orgId: string; type: Scope; member: MemberView; onClose: () => void }) {
  const { t } = useTranslation('org');
  const [keys, setKeys] = useState<string[]>(rolesToKeys(member.roles.map((r) => ({ role: r.role, examCode: r.examCode ?? null }))));
  const [error, setError] = useState<string | null>(null);
  const save = useSetRoles(orgId);
  const name = member.displayName ?? member.email;
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={t('members.editTitle')}
      description={t('members.editDescription', { name })}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            {t('members.cancel')}
          </Button>
          <Button
            loading={save.isPending}
            onClick={() => {
              setError(null);
              save.mutate({ userId: member.userId, keys }, { onSuccess: onClose, onError: (err) => setError(orgProblemMessage(err, t)) });
            }}
          >
            {t('members.save')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {error && <FormAlert>{error}</FormAlert>}
        <RolePicker scope={type} value={keys} onChange={setKeys} />
      </div>
    </Dialog>
  );
}
