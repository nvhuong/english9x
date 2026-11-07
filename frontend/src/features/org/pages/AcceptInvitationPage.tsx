import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { useApi } from '../../../api/useApi';
import { useAuthStore } from '../../../app/stores/auth';
import { publicKey } from '../../../app/stores/context';
import { Button } from '../../../design';
import { AuthCard, FormAlert } from '../../auth/components';

function messageKey(err: unknown): string {
  if (err instanceof ApiProblemError) {
    if (err.type === 'urn:e9x:problem:invitation-email-mismatch') return 'accept.mismatch';
    if (err.type === 'urn:e9x:problem:email-not-verified') return 'accept.unverified';
    if (err.status === 404 || err.status === 400) return 'accept.invalid';
    return 'problem.generic';
  }
  return 'problem.network';
}

/**
 * Where the link in the invitation mail lands: /accept-invitation?token=... Nothing happens on opening it (a mail scanner or a
 * prefetch must not use up a single-use link): the person signs in with the invited address and presses the button.
 * The server decides: a different address gets "sent to another address", an unverified one is asked to verify first.
 */
export default function AcceptInvitationPage() {
  const { t } = useTranslation('org');
  const api = useApi();
  const queryClient = useQueryClient();
  const location = useLocation();
  const token = new URLSearchParams(location.search).get('token');
  const status = useAuthStore((s) => s.status);
  const user = useAuthStore((s) => s.user);
  const [error, setError] = useState<string | null>(null);
  const accept = useMutation({
    mutationFn: async (tok: string) => api.POST('/api/invitations/{token}/accept', { params: { path: { token: tok } } }),
    onSuccess: () => {
      // the roles just changed: ask again who I am and where I belong
      void queryClient.invalidateQueries({ queryKey: publicKey('me', 'context', user?.id ?? null) });
    },
    onError: (err) => setError(t(messageKey(err))),
  });

  if (!token) {
    return (
      <AuthCard title={t('accept.title')}>
        <FormAlert>{t('accept.noToken')}</FormAlert>
      </AuthCard>
    );
  }
  if (accept.isSuccess) {
    return (
      <AuthCard title={t('accept.doneTitle')} description={t('accept.doneBody')}>
        <Button asChild>
          <Link to="/">{t('accept.home')}</Link>
        </Button>
      </AuthCard>
    );
  }
  if (status !== 'authenticated') {
    const next = encodeURIComponent(location.pathname + location.search);
    return (
      <AuthCard title={t('accept.title')} description={t('accept.visitor')}>
        <div className="flex flex-wrap gap-3">
          <Button asChild>
            <Link to={`/login?next=${next}`}>{t('accept.login')}</Link>
          </Button>
          <Button asChild variant="secondary">
            <Link to={`/register`}>{t('accept.register')}</Link>
          </Button>
        </div>
      </AuthCard>
    );
  }
  return (
    <AuthCard title={t('accept.title')} description={t('accept.signedIn', { email: user?.email ?? '' })}>
      {error && <FormAlert>{error}</FormAlert>}
      <div>
        <Button
          loading={accept.isPending}
          onClick={() => {
            setError(null);
            accept.mutate(token);
          }}
        >
          {t('accept.submit')}
        </Button>
      </div>
    </AuthCard>
  );
}
