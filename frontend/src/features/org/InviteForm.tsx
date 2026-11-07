import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Button, Input } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { applyFieldErrors } from '../auth/problems';
import { orgProblemMessage } from './problems';
import { useInvite } from './queries';
import { RolePicker } from './RolePicker';
import type { Scope } from './roles';
import { inviteSchema, type InviteValues } from './schemas';

export interface InviteFormProps {
  scope: Scope;
  /** The organisation, or null for English9x staff invitations. */
  orgId: string | null;
}

/** Invite a person by email with one or more roles. The server enforces every rule again; this only helps. */
export function InviteForm({ scope, orgId }: InviteFormProps) {
  const { t } = useTranslation('org');
  const [formError, setFormError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const invite = useInvite(orgId);
  const form = useForm<InviteValues>({ resolver: zodResolver(inviteSchema), defaultValues: { email: '', roles: [] } });
  const errors = form.formState.errors;
  return (
    <form
      noValidate
      className="flex max-w-xl flex-col gap-4"
      aria-label={t('invite.title')}
      onSubmit={form.handleSubmit((v) => {
        setFormError(null);
        setSentTo(null);
        invite.mutate(
          { email: v.email, keys: v.roles },
          {
            onSuccess: () => {
              setSentTo(v.email);
              form.reset({ email: '', roles: [] });
            },
            onError: (err) => {
              if (!applyFieldErrors(err, ['email'], form.setError, t)) setFormError(orgProblemMessage(err, t));
            },
          },
        );
      })}
    >
      {formError && <FormAlert>{formError}</FormAlert>}
      {sentTo && <FormNotice>{t('invite.sent', { email: sentTo })}</FormNotice>}
      <Input
        label={t('fields.email')}
        type="email"
        autoComplete="off"
        required
        error={errors.email && t(errors.email.message ?? 'validation.required')}
        {...form.register('email')}
      />
      <Controller
        control={form.control}
        name="roles"
        render={({ field }) => (
          <RolePicker scope={scope} value={field.value} onChange={field.onChange} error={errors.roles && t(errors.roles.message ?? 'validation.roleRequired')} />
        )}
      />
      <div>
        <Button type="submit" loading={invite.isPending}>
          {t('invite.submit')}
        </Button>
      </div>
    </form>
  );
}
