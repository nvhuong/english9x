import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { useApi } from '../../../api/useApi';
import { Button, Input } from '../../../design';
import { AuthCard, FormAlert, FormNotice } from '../components';
import { applyFieldErrors, problemMessage } from '../problems';
import { resetSchema, type ResetValues } from '../schemas';

/** `/reset-password?token=...` */
export default function ResetPasswordPage() {
  const { t } = useTranslation('auth');
  const api = useApi();
  const [params] = useSearchParams();
  const [token] = useState(() => params.get('token')); // kept in memory only
  const [formError, setFormError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const form = useForm<ResetValues>({ resolver: zodResolver(resetSchema), defaultValues: { newPassword: '', confirm: '' } });
  const confirm = useMutation({
    mutationFn: async (v: ResetValues) => api.POST('/api/auth/password-reset/confirm', { body: { token: token!, newPassword: v.newPassword } }),
    onSuccess: () => setDone(true),
    onError: (err) => {
      if (!applyFieldErrors(err, ['newPassword'], form.setError, t)) setFormError(problemMessage(err, t));
    },
  });

  if (!token) {
    return (
      <AuthCard title={t('reset.failTitle')} description={t('reset.missingToken')}>
        <Button asChild>
          <Link to="/forgot-password">{t('reset.requestNew')}</Link>
        </Button>
      </AuthCard>
    );
  }
  if (done) {
    return (
      <AuthCard title={t('reset.doneTitle')}>
        <FormNotice>{t('reset.doneBody')}</FormNotice>
        <Button asChild>
          <Link to="/login">{t('verify.login')}</Link>
        </Button>
      </AuthCard>
    );
  }
  const err = form.formState.errors;
  return (
    <AuthCard title={t('reset.title')} description={t('reset.description')}>
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((v) => { setFormError(null); confirm.mutate(v); })}>
        {formError && (
          <div className="flex flex-col gap-2">
            <FormAlert>{formError}</FormAlert>
            <Link to="/forgot-password" className="text-sm font-semibold text-primary">{t('reset.requestNew')}</Link>
          </div>
        )}
        <Input
          label={t('fields.newPassword')}
          type="password"
          autoComplete="new-password"
          hint={t('hints.passwordPolicy')}
          required
          error={err.newPassword && t(err.newPassword.message ?? 'validation.required')}
          {...form.register('newPassword')}
        />
        <Input
          label={t('fields.confirmPassword')}
          type="password"
          autoComplete="new-password"
          required
          error={err.confirm && t(err.confirm.message ?? 'validation.required')}
          {...form.register('confirm')}
        />
        <Button type="submit" loading={confirm.isPending}>
          {t('reset.submit')}
        </Button>
      </form>
    </AuthCard>
  );
}
