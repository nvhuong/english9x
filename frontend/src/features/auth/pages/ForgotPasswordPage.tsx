import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useApi } from '../../../api/useApi';
import { Button, Input } from '../../../design';
import { AuthCard, FormAlert } from '../components';
import { applyFieldErrors, problemMessage } from '../problems';
import { forgotSchema, type ForgotValues } from '../schemas';

export default function ForgotPasswordPage() {
  const { t } = useTranslation('auth');
  const api = useApi();
  const [formError, setFormError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const form = useForm<ForgotValues>({ resolver: zodResolver(forgotSchema), defaultValues: { email: '' } });
  const request = useMutation({
    mutationFn: async (v: ForgotValues) => api.POST('/api/auth/password-reset/request', { body: v }),
    onSuccess: () => setSent(true),
    onError: (err) => {
      if (!applyFieldErrors(err, ['email'], form.setError, t)) setFormError(problemMessage(err, t));
    },
  });
  const back = <Link to="/login" className="font-semibold text-primary">{t('common.backToLogin')}</Link>;

  if (sent) {
    // the same message whether or not an account exists
    return (
      <AuthCard title={t('forgot.sentTitle')} footer={back}>
        <p role="status">{t('forgot.sentBody')}</p>
      </AuthCard>
    );
  }
  return (
    <AuthCard title={t('forgot.title')} description={t('forgot.description')} footer={back}>
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((v) => { setFormError(null); request.mutate(v); })}>
        {formError && <FormAlert>{formError}</FormAlert>}
        <Input
          label={t('fields.email')}
          type="email"
          autoComplete="email"
          required
          error={form.formState.errors.email && t(form.formState.errors.email.message ?? 'validation.required')}
          {...form.register('email')}
        />
        <Button type="submit" loading={request.isPending}>
          {t('forgot.submit')}
        </Button>
      </form>
    </AuthCard>
  );
}
