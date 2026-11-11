import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Dialog } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { reviewProblemText } from './problems';
import { useDecide } from './queries';

/**
 * Approve / request changes for one review or a whole bundle. The server decides: a person who wrote or submitted the version is told so up front
 * (`ownVersion`, buttons disabled) and refused by the server all the same. A comment is required to send a version back.
 */
export function DecisionBar({ id, bundle, ownVersion, decided }: { id: string; bundle: boolean; ownVersion: boolean; decided: boolean }) {
  const { t } = useTranslation('review');
  const decide = useDecide(id, bundle);
  const [open, setOpen] = useState(false);
  const [comment, setComment] = useState('');
  const [done, setDone] = useState<'approve' | 'changes' | null>(null);
  const field = useId();
  const blocked = ownVersion || decided || done !== null;

  const approve = () => decide.mutate({ kind: 'approve' }, { onSuccess: () => setDone('approve') });
  const send = () =>
    decide.mutate({ kind: 'changes', comment }, { onSuccess: () => { setOpen(false); setDone('changes'); } });

  return (
    <section aria-label={t('decision.label')} className="flex flex-col gap-3">
      {ownVersion && <FormNotice tone="info">{t('decision.own')}</FormNotice>}
      {done && <FormNotice>{t(done === 'approve' ? 'decision.approved' : 'decision.sentBack')}</FormNotice>}
      {decide.isError && !open && <FormAlert>{reviewProblemText(t, decide.error, ownVersion)}</FormAlert>}
      <div className="flex flex-wrap gap-3">
        <Button onClick={approve} disabled={blocked || decide.isPending}>
          {bundle ? t('decision.approveBundle') : t('decision.approve')}
        </Button>
        <Dialog
          open={open}
          onOpenChange={(o) => { setOpen(o); if (!o) decide.reset(); }}
          title={t('decision.dialogTitle')}
          description={t('decision.dialogDescription')}
          trigger={<Button variant="secondary" disabled={blocked}>{bundle ? t('decision.changesBundle') : t('decision.changes')}</Button>}
          footer={
            <>
              <Button variant="ghost" onClick={() => setOpen(false)}>{t('decision.cancel')}</Button>
              <Button onClick={send} disabled={comment.trim() === '' || decide.isPending}>{t('decision.send')}</Button>
            </>
          }
        >
          <label htmlFor={field} className="mb-1 block text-sm font-medium">{t('decision.comment')}</label>
          <textarea id={field} value={comment} onChange={(e) => setComment(e.target.value)} rows={5} maxLength={4000}
            className="w-full rounded-md border border-line bg-surface p-2 text-ink" />
          {decide.isError && <div className="mt-3"><FormAlert>{reviewProblemText(t, decide.error, ownVersion)}</FormAlert></div>}
        </Dialog>
      </div>
    </section>
  );
}
