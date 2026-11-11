import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Badge, Button, Input, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { orgProblemMessage } from '../problems';
import { useCreateJoinCode, useJoinCodes, useRevokeJoinCode } from './queries';

function defaultExpiry(): string {
  const d = new Date(Date.now() + 30 * 24 * 3600 * 1000);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}

/** Join codes of an organisation (F44). The code is shown once, when it is created: the server keeps only a digest. */
export function JoinCodesPanel({ orgId }: { orgId: string }) {
  const { t } = useTranslation('org');
  const codes = useJoinCodes(orgId);
  const create = useCreateJoinCode(orgId);
  const revoke = useRevokeJoinCode(orgId);
  const [expiresAt, setExpiresAt] = useState(defaultExpiry);
  const [maxUses, setMaxUses] = useState('30');
  const [shown, setShown] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  return (
    <div className="flex flex-col gap-4">
      <form
        className="flex max-w-xl flex-col gap-3"
        aria-label={t('joinCodes.title')}
        onSubmit={(e) => {
          e.preventDefault();
          setError(null);
          setShown(null);
          create.mutate(
            { expiresAt: new Date(expiresAt).toISOString(), maxUses: Number(maxUses) },
            { onSuccess: (r) => setShown(r.code), onError: (err) => setError(orgProblemMessage(err, t)) },
          );
        }}
      >
        {error && <FormAlert>{error}</FormAlert>}
        <Input label={t('joinCodes.expiresAt')} type="datetime-local" required value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} />
        <Input label={t('joinCodes.maxUses')} type="number" min={1} max={100000} required value={maxUses} onChange={(e) => setMaxUses(e.target.value)} />
        <div>
          <Button type="submit" loading={create.isPending}>
            {t('joinCodes.create')}
          </Button>
        </div>
      </form>
      {shown && (
        <FormNotice>
          {t('joinCodes.shownOnce')} <strong data-testid="join-code" className="font-mono text-lg tracking-widest">{shown}</strong>
        </FormNotice>
      )}
      {codes.isPending ? (
        <p role="status" className="text-muted">{t('joinCodes.loading')}</p>
      ) : codes.isError ? (
        <FormAlert>{t('members.loadFailed')}</FormAlert>
      ) : codes.data.length === 0 ? (
        <p className="text-muted">{t('joinCodes.empty')}</p>
      ) : (
        <Table caption={t('joinCodes.caption')} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('joinCodes.status')}</TableHeaderCell>
              <TableHeaderCell>{t('joinCodes.uses')}</TableHeaderCell>
              <TableHeaderCell>{t('joinCodes.expires')}</TableHeaderCell>
              <TableHeaderCell>
                <span className="sr-only">{t('members.actions')}</span>
              </TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {codes.data.map((c) => (
              <TableRow key={c.id}>
                <TableCell>
                  <Badge>{t(`joinCodes.statuses.${c.status}`)}</Badge>
                </TableCell>
                <TableCell>{t('joinCodes.usesOf', { used: c.usedCount, max: c.maxUses })}</TableCell>
                <TableCell>{new Date(c.expiresAt).toLocaleString()}</TableCell>
                <TableCell>
                  {c.status === 'ACTIVE' && (
                    <Button size="sm" variant="secondary" loading={revoke.isPending && revoke.variables === c.id} onClick={() => revoke.mutate(c.id)}>
                      {t('joinCodes.revoke')}
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
