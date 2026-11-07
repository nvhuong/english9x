import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ForbiddenPage } from '../../../app/pages/ForbiddenPage';
import { Badge, Button, Input, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { applyFieldErrors } from '../../auth/problems';
import { orgProblemMessage } from '../problems';
import { useAllOrganizations, useCreateOrganization } from '../queries';
import { createOrgSchema, type CreateOrgValues } from '../schemas';
import { pageState } from '../util';

/** SYS_ADMIN: every organisation, and the form that creates one (F05). */
export default function OrganizationsPage() {
  const { t, i18n } = useTranslation('org');
  const list = useAllOrganizations();
  const create = useCreateOrganization();
  const [formError, setFormError] = useState<string | null>(null);
  const [created, setCreated] = useState<string | null>(null);
  const form = useForm<CreateOrgValues>({ resolver: zodResolver(createOrgSchema), defaultValues: { name: '' } });
  const errors = form.formState.errors;

  if (list.isError && pageState(list.error) === 'forbidden') return <ForbiddenPage />;
  const fmt = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });

  return (
    <div className="flex flex-col gap-8">
      <h1 className="font-serif text-3xl font-bold text-ink">{t('orgs.title')}</h1>

      <section aria-labelledby="create-org" className="flex max-w-xl flex-col gap-3">
        <h2 id="create-org" className="font-serif text-xl font-semibold">
          {t('orgs.create.title')}
        </h2>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit((v) => {
            setFormError(null);
            setCreated(null);
            create.mutate(v, {
              onSuccess: () => {
                setCreated(v.name);
                form.reset({ name: '' });
              },
              onError: (err) => {
                if (!applyFieldErrors(err, ['name'], form.setError, t)) setFormError(orgProblemMessage(err, t));
              },
            });
          })}
        >
          {formError && <FormAlert>{formError}</FormAlert>}
          {created && <FormNotice>{t('orgs.create.created', { name: created })}</FormNotice>}
          <Input label={t('fields.orgName')} required autoComplete="off" error={errors.name && t(errors.name.message ?? 'validation.required')} {...form.register('name')} />
          <Controller
            control={form.control}
            name="type"
            render={({ field }) => (
              <Select
                label={t('fields.orgType')}
                value={field.value}
                onValueChange={field.onChange}
                options={[
                  { value: 'CENTER', label: t('types.CENTER') },
                  { value: 'ENTERPRISE', label: t('types.ENTERPRISE') },
                ]}
                error={errors.type && t(errors.type.message ?? 'validation.orgType')}
              />
            )}
          />
          <div>
            <Button type="submit" loading={create.isPending}>
              {t('orgs.create.submit')}
            </Button>
          </div>
        </form>
      </section>

      <section aria-label={t('orgs.caption')}>
        {list.isPending ? (
          <p role="status" className="text-muted">
            {t('members.loading')}
          </p>
        ) : list.isError ? (
          <FormAlert>{t('orgs.loadFailed')}</FormAlert>
        ) : list.data.length === 0 ? (
          <p className="text-muted">{t('orgs.empty')}</p>
        ) : (
          <Table caption={t('orgs.caption')} dense>
            <TableHead>
              <TableRow>
                <TableHeaderCell>{t('orgs.name')}</TableHeaderCell>
                <TableHeaderCell>{t('orgs.type')}</TableHeaderCell>
                <TableHeaderCell>{t('orgs.createdAt')}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {list.data.map((o) => (
                <TableRow key={o.id}>
                  <TableCell>
                    <Link to={`/admin/organizations/${o.id}`} className="font-semibold text-primary">
                      {o.name}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Badge tone={o.type === 'CENTER' ? 'info' : 'accent'}>{t(`types.${o.type}`)}</Badge>
                  </TableCell>
                  <TableCell>{fmt.format(new Date(o.createdAt))}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </section>
    </div>
  );
}
