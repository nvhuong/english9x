import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { useAuthStore } from '../../../app/stores/auth';
import { Button, Input } from '../../../design';
import { AuthCard, FormAlert } from '../../auth/components';
import { rememberReturn } from '../../auth/returnTo';
import { useJoin } from './queries';

function messageKey(err: unknown): string {
  if (err instanceof ApiProblemError) {
    if (err.status === 410) return 'join.invalid';
    if (err.status === 429) return 'join.tooMany';
    if (err.type === 'urn:e9x:problem:email-not-verified') return 'join.unverified';
    return 'problem.generic';
  }
  return 'problem.network';
}

/** /join: a signed-in person types the code the organisation gave them (/join?code=... fills it in). Guessing is limited by the server. */
export default function JoinPage() {
  const { t } = useTranslation('org');
  const location = useLocation();
  const status = useAuthStore((s) => s.status);
  const [code, setCode] = useState(new URLSearchParams(location.search).get('code') ?? '');
  const [error, setError] = useState<string | null>(null);
  const join = useJoin();

  if (join.isSuccess) {
    return (
      <AuthCard title={t(join.data.status === 'joined' ? 'join.doneTitle' : 'join.alreadyTitle')} description={t('join.doneBody')}>
        <Button asChild>
          <Link to="/">{t('accept.home')}</Link>
        </Button>
      </AuthCard>
    );
  }
  if (status !== 'authenticated') {
    const next = encodeURIComponent(location.pathname + location.search);
    return (
      <AuthCard title={t('join.title')} description={t('join.visitor')}>
        <div className="flex flex-wrap gap-3">
          <Button asChild>
            <Link to={`/login?next=${next}`}>{t('accept.login')}</Link>
          </Button>
          <Button asChild variant="secondary">
            <Link to={`/register?next=${next}`}>{t('accept.register')}</Link>
          </Button>
        </div>
      </AuthCard>
    );
  }
  return (
    <AuthCard title={t('join.title')} description={t('join.description')}>
      {error && <FormAlert>{error}</FormAlert>}
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          setError(null);
          join.mutate(code.trim(), {
            onError: (err) => {
              if (err instanceof ApiProblemError && err.type === 'urn:e9x:problem:email-not-verified') rememberReturn(location.pathname + location.search);
              setError(t(messageKey(err)));
            },
          });
        }}
      >
        <Input label={t('join.code')} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" maxLength={12} required />
        <div>
          <Button type="submit" loading={join.isPending}>
            {t('join.submit')}
          </Button>
        </div>
      </form>
    </AuthCard>
  );
}
