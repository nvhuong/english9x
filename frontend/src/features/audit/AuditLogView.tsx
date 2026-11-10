import { useTranslation } from 'react-i18next';
import { Badge, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../design';
import { actionLabel, detailLines } from './auditText';
import type { AuditEntry } from './queries';

/** Things a person does for themselves: with no organisation in context these were not done for English9x. */
const PERSONAL_ACTIONS: ReadonlySet<string> = new Set(['invitation.accept']);

/** The records as a table. Every value from the server is rendered as text. */
export function AuditLogView({ entries }: { entries: readonly AuditEntry[] }) {
  const { t, i18n } = useTranslation('audit');
  const when = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'medium' });
  return (
    <Table caption={t('table.caption')} captionHidden dense>
      <TableHead>
        <TableRow>
          <TableHeaderCell>{t('table.when')}</TableHeaderCell>
          <TableHeaderCell>{t('table.actor')}</TableHeaderCell>
          <TableHeaderCell>{t('table.context')}</TableHeaderCell>
          <TableHeaderCell>{t('table.action')}</TableHeaderCell>
          <TableHeaderCell>{t('table.target')}</TableHeaderCell>
          <TableHeaderCell>{t('table.detail')}</TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {entries.map((e) => (
          <TableRow key={e.id} data-testid="audit-row">
            <TableCell>
              <time dateTime={e.at}>{when.format(new Date(e.at))}</time>
            </TableCell>
            <TableCell>
              {e.actorKind === 'OPERATOR' ? (
                <Badge tone="warning">{t('table.operator')}</Badge>
              ) : e.actorKind === 'SYSTEM' ? (
                <Badge tone="neutral">{t('table.systemActor')}</Badge>
              ) : (
                <span className="flex flex-col">
                  <span>{e.actorName ?? e.actorEmail ?? t('table.unknownActor')}</span>
                  {e.actorEmail && e.actorName && <span className="text-xs text-muted">{e.actorEmail}</span>}
                  {e.actorId && !e.actorEmail && <span className="break-all text-xs text-muted">{e.actorId}</span>}
                </span>
              )}
            </TableCell>
            <TableCell>{e.orgId ? (e.orgName ?? e.orgId) : e.actorKind === 'OPERATOR' || e.actorKind === 'SYSTEM' ? t('table.system') : PERSONAL_ACTIONS.has(e.action) ? t('table.personal') : t('table.english9x')}</TableCell>
            <TableCell>
              <span className="flex flex-col">
                <span>{actionLabel(t, e.action)}</span>
                <code className="text-xs text-muted">{e.action}</code>
              </span>
            </TableCell>
            <TableCell>
              <span className="flex flex-col">
                <span>{e.targetType}</span>
                <code className="break-all text-xs text-muted">{e.targetId}</code>
                {e.targetType === 'invitation' && (
                  <span className="break-all text-xs">{e.invitedEmail ?? <span className="text-muted">{t('table.invitedGone')}</span>}</span>
                )}
              </span>
            </TableCell>
            <TableCell>
              <dl className="grid grid-cols-[auto_1fr] gap-x-2 text-xs">
                {detailLines(e.detail).map(([k, v]) => (
                  <div key={k} className="contents">
                    <dt className="font-semibold text-muted">{k}</dt>
                    <dd className="break-all text-ink">{v}</dd>
                  </div>
                ))}
              </dl>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
