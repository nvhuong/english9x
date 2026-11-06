import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useApi } from '../../../api/useApi';
import { Button, Input } from '../../../design';
import i18n from '../../../i18n';
import { AuthCard, CheckboxField, FormAlert, FormNotice } from '../components';
import { applyFieldErrors, problemMessage } from '../problems';
import { registerSchema, type RegisterValues } from '../schemas';
import { useAuthConfig } from './LoginPage';

export default function RegisterPage() {
  const { t } = useTranslation('auth');
  const api = useApi();
  const [formError, setFormError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [resent, setResent] = useState(false);
  const config = useAuthConfig();

  const form = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { email: '', displayName: '', password: '', consent: false },
  });
  const register = useMutation({
    mutationFn: async (v: RegisterValues) =>
      (await api.POST('/api/auth/register', { body: { ...v, locale: i18n.language === 'en' ? 'en' : 'vi' } })).data!,
    onSuccess: (_data, v) => setSentTo(v.email),
    onError: (err) => {
      if (!applyFieldErrors(err, ['email', 'password', 'displayName', 'consent'], form.setError, t)) setFormError(problemMessage(err, t));
    },
  });
  const resend = useMutation({
    mutationFn: async (email: string) => api.POST('/api/auth/verify-email/resend', { body: { email } }),
    onSuccess: () => setResent(true),
    onError: (err) => setFormError(problemMessage(err, t)),
  });

  if (sentTo) {
    return (
      <AuthCard title={t('register.sentTitle')} footer={<Link to="/login" className="font-semibold text-primary">{t('common.backToLogin')}</Link>}>
        <p>{t('register.sentBody', { email: sentTo })}</p>
        {formError && <FormAlert>{formError}</FormAlert>}
        {resent && <FormNotice>{t('register.resent')}</FormNotice>}
        <Button variant="secondary" loading={resend.isPending} onClick={() => { setFormError(null); resend.mutate(sentTo); }}>
          {t('register.resend')}
        </Button>
      </AuthCard>
    );
  }

  const err = form.formState.errors;
  return (
    <AuthCard
      title={t('register.title')}
      description={t('register.description')}
      footer={
        <p>
          {t('register.haveAccount')}{' '}
          <Link to="/login" className="font-semibold text-primary">
            {t('register.loginLink')}
          </Link>
        </p>
      }
    >
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={form.handleSubmit((v) => {
          setFormError(null);
          register.mutate(v);
        })}
      >
        {formError && <FormAlert>{formError}</FormAlert>}
        <Input label={t('fields.displayName')} autoComplete="name" required error={err.displayName && t(err.displayName.message ?? 'validation.required')} {...form.register('displayName')} />
        <Input label={t('fields.email')} type="email" autoComplete="email" required error={err.email && t(err.email.message ?? 'validation.required')} {...form.register('email')} />
        <Input
          label={t('fields.password')}
          type="password"
          autoComplete="new-password"
          hint={t('hints.passwordPolicy')}
          required
          error={err.password && t(err.password.message ?? 'validation.required')}
          {...form.register('password')}
        />
        <CheckboxField label={t('register.consent')} error={err.consent && t(err.consent.message ?? 'validation.consent')} {...form.register('consent')} />
        <Button type="submit" loading={register.isPending}>
          {t('register.submit')}
        </Button>
      </form>
      {config.data?.googleEnabled && (
        <div className="flex flex-col gap-2 border-t border-line pt-4">
          <p className="text-center text-sm text-muted">{t('common.or')}</p>
          <Button asChild variant="secondary">
            <a href="/api/auth/oauth2/google">{t('login.google')}</a>
          </Button>
          <p className="text-xs text-muted">{t('login.googleConsent')}</p>
        </div>
      )}
    </AuthCard>
  );
}
