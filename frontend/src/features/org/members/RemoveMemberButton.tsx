import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Dialog } from '../../../design';
import { FormAlert } from '../../auth/components';
import { orgProblemMessage } from '../problems';
import { useRemoveMember } from './queries';

/** Takes a member out of the organisation (their seat is taken back; what they did stays visible to the organisation). Confirms first. */
export function RemoveMemberButton({ orgId, userId, name }: { orgId: string; userId: string; name: string }) {
  const { t } = useTranslation('org');
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const remove = useRemoveMember(orgId);
  return (
    <>
      <Button size="sm" variant="secondary" aria-label={t('remove.of', { name })} onClick={() => setOpen(true)}>
        {t('remove.button')}
      </Button>
      {open && (
        <Dialog
          open
          onOpenChange={setOpen}
          title={t('remove.title', { name })}
          footer={
            <>
              <Button variant="secondary" onClick={() => setOpen(false)}>
                {t('members.cancel')}
              </Button>
              <Button
                loading={remove.isPending}
                onClick={() =>
                  remove.mutate(userId, { onSuccess: () => setOpen(false), onError: (err) => setError(orgProblemMessage(err, t)) })
                }
              >
                {t('remove.confirm')}
              </Button>
            </>
          }
        >
          <p>{t('remove.body')}</p>
          {error && <FormAlert>{error}</FormAlert>}
        </Dialog>
      )}
    </>
  );
}
