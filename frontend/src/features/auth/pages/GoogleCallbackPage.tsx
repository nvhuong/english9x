import { zodResolver } from '@hookform/resolvers/zod';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { useApi } from '../../../api/useApi';
import { useAuthStore } from '../../../app/stores/auth';
import { Button } from '../../../design';
import { AuthCard, CheckboxField, FormAlert } from '../components';
import { problemMessage } from '../problems';
import { takeReturn } from '../returnTo';
import { googleConsentSchema, type GoogleConsentValues } from '../schemas';
import { refreshStorage, startSession, type TokenPair } from '../session';

type Stage = 'confirm' | 'working' | 'consent';

/**
 * `/login/google?code=...`: the server finished the Google sign-in and handed over a one-time code (60 s, single use, and
 * it only works in the browser that finished the sign-in). The code is taken out of the address bar immediately, exchanged
 * for the tokens in a response body, and forgotten.
 *  - A session already live in this browser is never replaced silently: the person is asked first, and nothing is spent
 *    until they answer. Anyone can send anyone a link to this page.
 *  - When the server has no consent on record it refuses the exchange (403 consent-required) and the page asks with a
 *    real checkbox. Only the answer, checked by the server, lets the tokens be issued.
 */
export default function GoogleCallbackPage() {
  const { t } = useTranslation('auth');
  const api = useApi();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [code] = useState(() => params.get('code')); // taken once; removed from the address bar below
  const started = useRef(false);
  const [stage, setStage] = useState<Stage>(() => (refreshStorage.get() !== null || useAuthStore.getState().status === 'authenticated' ? 'confirm' : 'working'));
  const [error, setError] = useState<string | null>(code ? null : t('problem.invalidToken'));
  const signedInAs = useAuthStore((s) => s.user?.displayName ?? s.user?.email ?? null);

  const finish = useCallback(
    async (tokens: TokenPair) => {
      const user = await startSession(tokens);
      if (user) void navigate(takeReturn(), { replace: true });
      else setError(t('problem.generic'));
    },
    [navigate, t],
  );

  const exchange = useCallback(
    (value: string) => {
      api
        .POST('/api/auth/oauth/exchange', { body: { code: value } })
        .then((res) => finish(res.data!))
        .catch((e: unknown) => {
          if (e instanceof ApiProblemError && e.type === 'urn:e9x:problem:consent-required') setStage('consent');
          else setError(problemMessage(e, t));
        });
    },
    [api, finish, t],
  );

  useEffect(() => {
    if (started.current || !code) return;
    started.current = true; // single use: StrictMode's second effect run must not spend it again
    if (params.has('code')) setParams({}, { replace: true });
    if (stage === 'working') exchange(code);
  }, [code, exchange, params, setParams, stage]);

  const form = useForm<GoogleConsentValues>({ resolver: zodResolver(googleConsentSchema), defaultValues: { consent: false } });
  const [submitting, setSubmitting] = useState(false);

  if (error) {
    return (
      <AuthCard title={t('google.failTitle')}>
        <FormAlert>{error}</FormAlert>
        <Link to="/login" className="font-semibold text-primary">{t('common.backToLogin')}</Link>
      </AuthCard>
    );
  }

  if (stage === 'confirm') {
    return (
      <AuthCard title={t('google.replaceTitle')}>
        <p>{signedInAs ? t('google.replaceBody', { name: signedInAs }) : t('google.replaceBodyNoName')}</p>
        <Button asChild>
          <Link to="/" replace>{t('google.replaceStay')}</Link>
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            setStage('working');
            exchange(code!);
          }}
        >
          {t('google.replaceContinue')}
        </Button>
      </AuthCard>
    );
  }

  if (stage === 'consent') {
    return (
      <AuthCard
        title={t('google.consentTitle')}
        description={t('google.consentBody')}
        footer={<Link to="/login" className="font-semibold text-primary">{t('common.backToLogin')}</Link>}
      >
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit(() => {
            setSubmitting(true);
            api
              .POST('/api/auth/oauth/consent', { body: { code: code!, consent: true } })
              .then((res) => finish(res.data!))
              .catch((e: unknown) => setError(problemMessage(e, t)))
              .finally(() => setSubmitting(false));
          })}
        >
          <CheckboxField
            label={t('register.consent')}
            error={form.formState.errors.consent && t(form.formState.errors.consent.message ?? 'validation.consentGoogle')}
            {...form.register('consent')}
          />
          <Button type="submit" loading={submitting}>
            {t('google.consentSubmit')}
          </Button>
        </form>
      </AuthCard>
    );
  }

  return (
    <AuthCard title={t('login.title')}>
      <p role="status">{t('google.working')}</p>
    </AuthCard>
  );
}
