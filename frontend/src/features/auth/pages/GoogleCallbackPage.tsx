import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useApi } from '../../../api/useApi';
import { AuthCard, FormAlert } from '../components';
import { problemMessage } from '../problems';
import { startSession } from '../session';

/**
 * `/login/google?code=...`: the server finished the Google sign-in and handed over a one-time code (60 s, single use).
 * The code is taken out of the address bar immediately, exchanged for the tokens in a response body, and forgotten.
 */
export default function GoogleCallbackPage() {
  const { t } = useTranslation('auth');
  const api = useApi();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [code] = useState(() => params.get('code')); // taken once; removed from the address bar below
  const started = useRef(false);
  const [error, setError] = useState<string | null>(code ? null : t('problem.invalidToken'));

  useEffect(() => {
    if (started.current || !code) return;
    started.current = true; // single use: StrictMode's second effect run must not spend it again
    if (params.has('code')) setParams({}, { replace: true });
    api
      .POST('/api/auth/oauth/exchange', { body: { code } })
      .then(async (res) => {
        const user = await startSession(res.data!);
        if (user) void navigate('/', { replace: true });
        else setError(t('problem.generic'));
      })
      .catch((e: unknown) => setError(problemMessage(e, t)));
  }, [api, code, navigate, params, setParams, t]);

  if (error) {
    return (
      <AuthCard title={t('google.failTitle')}>
        <FormAlert>{error}</FormAlert>
        <Link to="/login" className="font-semibold text-primary">{t('common.backToLogin')}</Link>
      </AuthCard>
    );
  }
  return (
    <AuthCard title={t('login.title')}>
      <p role="status">{t('google.working')}</p>
    </AuthCard>
  );
}
