import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { NotFoundPage } from '../../app/pages/NotFoundPage';
import { useContextStore } from '../../app/stores/context';
import { hasRole, useRoleStore } from '../../app/stores/roles';
import { Badge, Button, EmptyState, Input, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { useMembers } from '../org/queries';
import { useOrgStore } from '../org/orgContext';
import { pageState } from '../org/util';
import { classroomProblemMessage } from './problems';
import { useClasses, useCreateClass } from './queries';

/** /org/classes: the classes of a centre / the groups of an enterprise (F45). The ORG_ADMIN creates; a teacher sees theirs. The server decides everything again. */
export default function ClassesPage() {
  const orgId = useContextStore((s) => s.orgId);
  if (!orgId) return <NotFoundPage />;
  return <Classes orgId={orgId} />;
}

function Classes({ orgId }: { orgId: string }) {
  const { t } = useTranslation('classroom');
  const classes = useClasses(orgId);
  const grants = useRoleStore((s) => s.grants);
  const type = useOrgStore((s) => s.orgs.find((o) => o.id === orgId)?.type ?? 'CENTER');
  const isAdmin = hasRole(grants, orgId, 'ORG_ADMIN');
  if (classes.isError && pageState(classes.error) === 'forbidden') return <ForbiddenPage />;
  const group = type === 'ENTERPRISE';
  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{group ? t('classes.titleGroups') : t('classes.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{group ? t('classes.descriptionGroups') : t('classes.description')}</p>
      </header>
      {isAdmin && <CreateClass orgId={orgId} group={group} />}
      {classes.isPending && <p role="status" className="text-muted">{t('classes.loading')}</p>}
      {classes.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('classes.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void classes.refetch()}>
            {t('retry')}
          </Button>
        </div>
      )}
      {classes.data && classes.data.length === 0 && <EmptyState icon="🏫" title={group ? t('classes.emptyGroups') : t('classes.empty')} description={t('classes.emptyHint')} />}
      {classes.data && classes.data.length > 0 && (
        <Table caption={group ? t('classes.captionGroups') : t('classes.caption')} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('classes.name')}</TableHeaderCell>
              <TableHeaderCell>{t('classes.kind')}</TableHeaderCell>
              <TableHeaderCell>{t('classes.memberCount')}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {classes.data.map((c) => (
              <TableRow key={c.id} data-testid="class-row">
                <TableCell>
                  {c.canManage ? (
                    <Link to={`/org/classes/${c.id}`} className="font-semibold text-primary underline">
                      {c.name}
                    </Link>
                  ) : (
                    c.name
                  )}
                </TableCell>
                <TableCell>
                  <Badge tone="neutral">{t(`kind.${c.kind}`)}</Badge>
                </TableCell>
                <TableCell>{c.memberCount ?? '–'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}

function CreateClass({ orgId, group }: { orgId: string; group: boolean }) {
  const { t } = useTranslation('classroom');
  const create = useCreateClass(orgId);
  const members = useMembers(orgId);
  const [name, setName] = useState('');
  const [teacherId, setTeacherId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [teacherError, setTeacherError] = useState<string | null>(null);
  const teachers = (members.data ?? []).filter((m) => m.roles.some((r) => r.role === 'TEACHER'));
  return (
    <form
      className="flex max-w-xl flex-col gap-3"
      aria-label={group ? t('create.titleGroup') : t('create.title')}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        setNotice(null);
        const trimmed = name.trim();
        setNameError(trimmed ? null : t('validation.name'));
        setTeacherError(group || teacherId ? null : t('validation.teacher'));
        if (!trimmed || (!group && !teacherId)) return;
        create.mutate(group ? { name: trimmed } : { name: trimmed, teacherId }, {
          onSuccess: (c) => {
            setNotice(t('create.created', { name: c.name }));
            setName('');
            setTeacherId('');
          },
          onError: (err) => setError(classroomProblemMessage(err, t)),
        });
      }}
    >
      <h2 className="font-serif text-xl font-semibold">{group ? t('create.titleGroup') : t('create.title')}</h2>
      {error && <FormAlert>{error}</FormAlert>}
      {notice && <FormNotice>{notice}</FormNotice>}
      <Input label={t('create.name')} value={name} onChange={(e) => setName(e.target.value)} maxLength={100} error={nameError ?? undefined} />
      {!group && (
        <Select
          label={t('create.teacher')}
          options={teachers.map((m) => ({ value: m.userId, label: m.displayName ?? m.email }))}
          value={teacherId}
          onValueChange={setTeacherId}
          error={teacherError ?? undefined}
        />
      )}
      <div>
        <Button type="submit" loading={create.isPending}>
          {group ? t('create.submitGroup') : t('create.submit')}
        </Button>
      </div>
    </form>
  );
}
