import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useApi } from '../../../api/useApi';
import { useAuthStore } from '../../../app/stores/auth';
import { publicKey } from '../../../app/stores/context';
import { Button, Input } from '../../../design';
import { AuthCard, FormAlert } from '../components';
import { applyFieldErrors, problemMessage, safeNext } from '../problems';
import { forgetReturn, rememberReturn, takeReturn } from '../returnTo';
import { loginSchema, type LoginValues } from '../schemas';
import { startSession } from '../session';

/** Is Google sign-in configured on the server? The button is hidden when it is not. */
export function useAuthConfig() {
  const api = useApi();
  return useQuery({
    queryKey: publicKey('auth-config'),
    queryFn: async () => (await api.GET('/api/auth/config')).data!,
    staleTime: 5 * 60_000,
  });
}

export default function LoginPage() {
  const { t } = useTranslation('auth');
  const api = useApi();
  const navigate = useNavigate();
  const location = useLocation();
  const status = useAuthStore((s) => s.status);
  const [formError, setFormError] = useState<string | null>(null);
  const config = useAuthConfig();
  const next = safeNext(location.search);
  const urlError = new URLSearchParams(location.search).get('error');

  const form = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } });
  const login = useMutation({
    mutationFn: async (values: LoginValues) => (await api.POST('/api/auth/login', { body: values })).data!,
    onSuccess: async (tokens) => {
      const user = await startSession(tokens);
      if (user) {
        // ?next= says where this sign-in was asked for; otherwise whatever a detour through the mailbox left behind
        const target = next === '/' ? takeReturn() : next;
        forgetReturn();
        void navigate(target, { replace: true });
      }
      else setFormError(t('problem.generic'));
    },
    onError: (err) => {
      if (!applyFieldErrors(err, ['email', 'password'], form.setError, t)) setFormError(problemMessage(err, t));
    },
  });

  const urlMessage =
    urlError === 'google_email_unverified' ? t('login.errorGoogleUnverified') : urlError ? t('login.errorGoogle') : null;
  const googleHref = '/api/auth/oauth2/google';

  return (
    <AuthCard
      title={t('login.title')}
      description={t('login.description')}
      footer={
        <p>
          {t('login.noAccount')}{' '}
          <Link to={`/register${location.search}`} className="font-semibold text-primary">
            {t('login.registerLink')}
          </Link>
        </p>
      }
    >
      {status === 'authenticated' && <p role="status">{t('login.alreadySignedIn')}</p>}
      {urlMessage && <FormAlert>{urlMessage}</FormAlert>}
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={form.handleSubmit((v) => {
          setFormError(null);
          login.mutate(v);
        })}
      >
        {formError && <FormAlert>{formError}</FormAlert>}
        <Input
          label={t('fields.email')}
          type="email"
          autoComplete="email"
          required
          error={form.formState.errors.email && t(form.formState.errors.email.message ?? 'validation.required')}
          {...form.register('email')}
        />
        <Input
          label={t('fields.password')}
          type="password"
          autoComplete="current-password"
          required
          error={form.formState.errors.password && t(form.formState.errors.password.message ?? 'validation.required')}
          {...form.register('password')}
        />
        <Button type="submit" loading={login.isPending}>
          {t('login.submit')}
        </Button>
        <Link to="/forgot-password" className="text-sm font-semibold text-primary">
          {t('login.forgot')}
        </Link>
      </form>
      {config.data?.googleEnabled && (
        <div className="flex flex-col gap-2 border-t border-line pt-4">
          <p className="text-center text-sm text-muted">{t('common.or')}</p>
          {/* a full-page navigation: the server redirects to Google and back; no script handles any token */}
          <Button asChild variant="secondary">
            <a href={googleHref} onClick={() => rememberReturn(next)}>{t('login.google')}</a>
          </Button>
        </div>
      )}
    </AuthCard>
  );
}
