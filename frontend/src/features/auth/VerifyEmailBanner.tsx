import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../api/useApi';
import { useAuthStore } from '../../app/stores/auth';
import { Button } from '../../design';

/**
 * Shown to a signed-in person whose email is not verified. They can study; buying a plan needs a verified address
 * (the server refuses it: identity.api.UserDirectory#requireVerified). The banner is only information.
 */
export function VerifyEmailBanner() {
  const { t } = useTranslation('auth');
  const api = useApi();
  const user = useAuthStore((s) => s.user);
  const [sent, setSent] = useState(false);
  const resend = useMutation({
    mutationFn: async (email: string) => api.POST('/api/auth/verify-email/resend', { body: { email } }),
    onSuccess: () => setSent(true),
  });
  if (!user || user.emailVerified) return null;
  return (
    <div role="status" data-testid="verify-email-banner" className="border-b border-line bg-sunken">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-2 text-sm text-ink">
        <p>{sent ? t('banner.resent') : t('banner.unverified')}</p>
        {!sent && (
          <Button size="sm" variant="secondary" loading={resend.isPending} onClick={() => resend.mutate(user.email)}>
            {t('banner.resend')}
          </Button>
        )}
      </div>
    </div>
  );
}
