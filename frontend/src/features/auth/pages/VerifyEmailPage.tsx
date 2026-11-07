import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { useApi } from '../../../api/useApi';
import { useAuthStore } from '../../../app/stores/auth';
import { Button, Input } from '../../../design';
import { AuthCard, FormAlert, FormNotice } from '../components';
import { problemMessage } from '../problems';
import { resendSchema, type ResendValues } from '../schemas';
import { reloadUser } from '../session';

type State = 'working' | 'ok' | 'failed';

/** `/verify-email?token=...`: spends the link once, then says so. The token is removed from the address bar at once. */
export default function VerifyEmailPage() {
  const { t } = useTranslation('auth');
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const [token] = useState(() => params.get('token')); // taken once; the address bar is cleaned below
  const started = useRef(false);
  const status = useAuthStore((s) => s.status);
  const [state, setState] = useState<State>(token ? 'working' : 'failed');
  const [formError, setFormError] = useState<string | null>(null);
  const [resent, setResent] = useState(false);

  const verify = useMutation({
    mutationFn: async (value: string) => api.POST('/api/auth/verify-email', { body: { token: value } }),
    onSuccess: async () => {
      setState('ok');
      await reloadUser(); // a signed-in person stops seeing the "unverified" banner
    },
    onError: () => setState('failed'),
  });

  useEffect(() => {
    if (started.current || !token) return;
    started.current = true; // React StrictMode runs effects twice in development; the link is single use
    if (params.has('token')) setParams({}, { replace: true });
    verify.mutate(token);
  }, [params, setParams, token, verify]);

  const form = useForm<ResendValues>({ resolver: zodResolver(resendSchema), defaultValues: { email: '' } });
  const resend = useMutation({
    mutationFn: async (v: ResendValues) => api.POST('/api/auth/verify-email/resend', { body: v }),
    onSuccess: () => setResent(true),
    onError: (err) => setFormError(problemMessage(err, t)),
  });

  if (state === 'working') {
    return (
      <AuthCard title={t('verify.title')}>
        <p role="status">{t('verify.working')}</p>
      </AuthCard>
    );
  }
  if (state === 'ok') {
    return (
      <AuthCard title={t('verify.successTitle')}>
        <FormNotice>{t('verify.successBody')}</FormNotice>
        <Button asChild>
          <Link to={status === 'authenticated' ? '/' : '/login'}>{status === 'authenticated' ? t('verify.continue') : t('verify.login')}</Link>
        </Button>
      </AuthCard>
    );
  }
  return (
    <AuthCard title={t('verify.failTitle')} description={token ? t('verify.failBody') : t('verify.missingToken')}>
      {formError && <FormAlert>{formError}</FormAlert>}
      {resent && <FormNotice>{t('register.resent')}</FormNotice>}
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((v) => { setFormError(null); resend.mutate(v); })}>
        <Input
          label={t('fields.email')}
          type="email"
          autoComplete="email"
          required
          error={form.formState.errors.email && t(form.formState.errors.email.message ?? 'validation.required')}
          {...form.register('email')}
        />
        <Button type="submit" loading={resend.isPending}>
          {t('verify.resend')}
        </Button>
      </form>
    </AuthCard>
  );
}
