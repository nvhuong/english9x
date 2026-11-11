import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { NotFoundPage } from '../../app/pages/NotFoundPage';
import { useContextStore } from '../../app/stores/context';
import { hasRole, useRoleStore } from '../../app/stores/roles';
import { Badge, Button, Input, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow, type BadgeTone } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { useOrgLicenses } from '../org/licenses/queries';
import { pageState } from '../org/util';
import { useMembers } from '../org/queries';
import { classroomProblemMessage } from './problems';
import {
  useAddClassMembers, useAssignClassCourses, useAssignmentReport, useClass, useClassAssignments, useClassMembers, useCreateAssignment, useRemoveClassMember,
  type AssignmentView,
} from './queries';

const when = (iso: string, locale: string) => new Date(iso).toLocaleString(locale, { dateStyle: 'short', timeStyle: 'short' });

export const STATUS_TONE: Record<string, BadgeTone> = { PENDING: 'neutral', SUBMITTED: 'success', LATE: 'warning', MISSING: 'danger' };

/** /org/classes/:classId: the roster, the courses given to the whole class and the assignments (F45, F46). The ORG_ADMIN and the class's teacher manage it. */
export default function ClassDetailPage() {
  const orgId = useContextStore((s) => s.orgId);
  const { classId } = useParams<{ classId: string }>();
  if (!orgId || !classId) return <NotFoundPage />;
  return <ClassDetail orgId={orgId} classId={classId} />;
}

function ClassDetail({ orgId, classId }: { orgId: string; classId: string }) {
  const { t } = useTranslation('classroom');
  const cls = useClass(orgId, classId);
  const grants = useRoleStore((s) => s.grants);
  const isAdmin = hasRole(grants, orgId, 'ORG_ADMIN');
  if (cls.isPending) return <p role="status" className="text-muted">{t('classes.loading')}</p>;
  if (cls.isError) {
    const state = pageState(cls.error);
    if (state === 'not-found') return <NotFoundPage />;
    if (state === 'forbidden') return <ForbiddenPage />;
    return <FormAlert>{t('classes.loadFailed')}</FormAlert>;
  }
  if (!cls.data.canManage) return <ForbiddenPage />;
  return (
    <div className="flex flex-col gap-8">
      <header>
        <Link to="/org/classes" className="text-primary underline">
          {t('detail.back')}
        </Link>
        <h1 className="mt-2 font-serif text-3xl font-bold text-ink">{cls.data.name}</h1>
        <Badge tone="neutral">{t(`kind.${cls.data.kind}`)}</Badge>
      </header>
      <section aria-labelledby="roster" className="flex flex-col gap-3">
        <h2 id="roster" className="font-serif text-xl font-semibold">
          {t('roster.title')}
        </h2>
        <Roster orgId={orgId} classId={classId} isAdmin={isAdmin} />
      </section>
      {isAdmin && (
        <section aria-labelledby="courses" className="flex flex-col gap-3">
          <h2 id="courses" className="font-serif text-xl font-semibold">
            {t('courses.title')}
          </h2>
          <p className="max-w-prose text-muted">{t('courses.description')}</p>
          <GiveCourses orgId={orgId} classId={classId} />
        </section>
      )}
      <section aria-labelledby="assignments" className="flex flex-col gap-3">
        <h2 id="assignments" className="font-serif text-xl font-semibold">
          {t('assignments.title')}
        </h2>
        <Assignments orgId={orgId} classId={classId} />
      </section>
    </div>
  );
}

function Roster({ orgId, classId, isAdmin }: { orgId: string; classId: string; isAdmin: boolean }) {
  const { t } = useTranslation('classroom');
  const roster = useClassMembers(orgId, classId);
  const remove = useRemoveClassMember(orgId, classId);
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="flex flex-col gap-3">
      {error && <FormAlert>{error}</FormAlert>}
      {isAdmin && <AddMember orgId={orgId} classId={classId} inClass={(roster.data ?? []).map((m) => m.userId)} />}
      {roster.isPending && <p role="status" className="text-muted">{t('roster.loading')}</p>}
      {roster.isError && <FormAlert>{t('roster.loadFailed')}</FormAlert>}
      {roster.data && roster.data.length === 0 && <p className="text-muted">{t('roster.empty')}</p>}
      {roster.data && roster.data.length > 0 && (
        <Table caption={t('roster.caption')} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('roster.member')}</TableHeaderCell>
              <TableHeaderCell>
                <span className="sr-only">{t('roster.actions')}</span>
              </TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {roster.data.map((m) => {
              const name = m.displayName ?? m.email ?? m.userId;
              return (
                <TableRow key={m.userId} data-testid="roster-row">
                  <TableCell>
                    {name}
                    {m.email && m.displayName && <span className="ml-2 text-sm text-muted">{m.email}</span>}
                  </TableCell>
                  <TableCell>
                    <Button
                      variant="secondary"
                      aria-label={t('roster.remove', { name })}
                      onClick={() => {
                        setError(null);
                        remove.mutate(m.userId, { onError: (err) => setError(classroomProblemMessage(err, t)) });
                      }}
                    >
                      {t('roster.removeShort')}
                    </Button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </div>
  );
}

function AddMember({ orgId, classId, inClass }: { orgId: string; classId: string; inClass: string[] }) {
  const { t } = useTranslation('classroom');
  const members = useMembers(orgId);
  const add = useAddClassMembers(orgId, classId);
  const [userId, setUserId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const options = (members.data ?? [])
    .filter((m) => !inClass.includes(m.userId))
    .map((m) => ({ value: m.userId, label: m.displayName ? `${m.displayName} (${m.email})` : m.email }));
  return (
    <form
      className="flex max-w-xl flex-col gap-3"
      aria-label={t('roster.addTitle')}
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        if (!userId) {
          setError(t('validation.member'));
          return;
        }
        add.mutate([userId], { onSuccess: () => setUserId(''), onError: (err) => setError(classroomProblemMessage(err, t)) });
      }}
    >
      {error && <FormAlert>{error}</FormAlert>}
      <Select label={t('roster.pick')} options={options} value={userId} onValueChange={setUserId} />
      <div>
        <Button type="submit" loading={add.isPending}>
          {t('roster.add')}
        </Button>
      </div>
    </form>
  );
}

function GiveCourses({ orgId, classId }: { orgId: string; classId: string }) {
  const { t } = useTranslation('classroom');
  const licenses = useOrgLicenses(orgId);
  const give = useAssignClassCourses(orgId, classId);
  const [licenseId, setLicenseId] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const active = (licenses.data ?? []).filter((l) => l.status === 'ACTIVE');
  const license = active.find((l) => l.id === licenseId);
  return (
    <form
      className="flex max-w-xl flex-col gap-3"
      aria-label={t('courses.title')}
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        setNotice(null);
        if (!licenseId || picked.length === 0) {
          setError(t('validation.courses'));
          return;
        }
        give.mutate(
          { licenseId, courseIds: picked },
          {
            onSuccess: (r) => setNotice(t('courses.done', { assigned: r.assigned, holding: r.alreadyHolding.length })),
            onError: (err) => setError(classroomProblemMessage(err, t)),
          },
        );
      }}
    >
      {error && <FormAlert>{error}</FormAlert>}
      {notice && <FormNotice>{notice}</FormNotice>}
      <Select
        label={t('courses.license')}
        options={active.map((l) => ({ value: l.id, label: t('courses.licenseOption', { seats: l.seats, used: l.seatsUsed }) }))}
        value={licenseId}
        onValueChange={(v) => {
          setLicenseId(v);
          setPicked([]);
        }}
      />
      {license && (
        <fieldset className="flex flex-col gap-1">
          <legend className="text-sm font-semibold text-ink">{t('courses.pick')}</legend>
          {license.courses.map((c) => (
            <label key={c.id} className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={picked.includes(c.id)}
                onChange={(e) => setPicked(e.target.checked ? [...picked, c.id] : picked.filter((x) => x !== c.id))}
              />
              <span>{`${c.code} ${c.title}`}</span>
            </label>
          ))}
        </fieldset>
      )}
      <div>
        <Button type="submit" loading={give.isPending}>
          {t('courses.give')}
        </Button>
      </div>
    </form>
  );
}

function Assignments({ orgId, classId }: { orgId: string; classId: string }) {
  const { t, i18n } = useTranslation('classroom');
  const list = useClassAssignments(orgId, classId);
  return (
    <div className="flex flex-col gap-4">
      <GiveAssignment orgId={orgId} classId={classId} />
      {list.isPending && <p role="status" className="text-muted">{t('assignments.loading')}</p>}
      {list.isError && <FormAlert>{t('assignments.loadFailed')}</FormAlert>}
      {list.data && list.data.length === 0 && <p className="text-muted">{t('assignments.empty')}</p>}
      {list.data && list.data.length > 0 && (
        <ul aria-label={t('assignments.listCaption')} className="flex flex-col gap-2">
          {list.data.map((a) => (
            <AssignmentRow key={a.id} orgId={orgId} a={a} locale={i18n.language} />
          ))}
        </ul>
      )}
    </div>
  );
}

function AssignmentRow({ orgId, a, locale }: { orgId: string; a: AssignmentView; locale: string }) {
  const { t } = useTranslation('classroom');
  const [open, setOpen] = useState(false);
  const report = useAssignmentReport(orgId, a.id, open);
  const title = a.title ?? (a.testVersionId ? t('assignments.exam') : t('assignments.lesson'));
  return (
    <li data-testid="assignment-row" className="flex flex-col gap-2 rounded-md border border-line px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="font-semibold">{title}</p>
          <p className="text-sm text-muted">{t('assignments.due', { date: when(a.dueAt, locale) })}</p>
        </div>
        <p data-testid="assignment-counts" className="text-sm">
          {t('assignments.counts', { total: a.total, submitted: a.submitted, late: a.late, missing: a.missing })}
        </p>
        <Button variant="secondary" aria-expanded={open} onClick={() => setOpen(!open)}>
          {open ? t('assignments.hide') : t('assignments.show')}
        </Button>
      </div>
      {open && report.isPending && <p role="status" className="text-muted">{t('assignments.loading')}</p>}
      {open && report.isError && <FormAlert>{t('assignments.loadFailed')}</FormAlert>}
      {open && report.data && (
        <Table caption={t('assignments.reportCaption', { title })} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('roster.member')}</TableHeaderCell>
              <TableHeaderCell>{t('assignments.status')}</TableHeaderCell>
              <TableHeaderCell>{t('assignments.submittedAt')}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {report.data.rows.map((r) => (
              <TableRow key={r.userId} data-testid="report-row">
                <TableCell>{r.displayName ?? r.email ?? r.userId}</TableCell>
                <TableCell>
                  <Badge tone={STATUS_TONE[r.status] ?? 'neutral'}>{t(`status.${r.status}`)}</Badge>
                </TableCell>
                <TableCell>{r.submittedAt ? when(r.submittedAt, locale) : '–'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </li>
  );
}

function GiveAssignment({ orgId, classId }: { orgId: string; classId: string }) {
  const { t } = useTranslation('classroom');
  const create = useCreateAssignment(orgId, classId);
  const [kind, setKind] = useState<'exam' | 'lesson'>('exam');
  const [target, setTarget] = useState('');
  const [dueAt, setDueAt] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <form
      className="flex max-w-xl flex-col gap-3"
      aria-label={t('assignments.giveTitle')}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        setNotice(null);
        if (!target.trim() || !dueAt) {
          setError(t('validation.assignment'));
          return;
        }
        const due = new Date(dueAt).toISOString();
        create.mutate(kind === 'exam' ? { testVersionId: target.trim(), dueAt: due } : { lessonId: target.trim(), dueAt: due }, {
          onSuccess: () => {
            setNotice(t('assignments.given'));
            setTarget('');
          },
          onError: (err) => setError(classroomProblemMessage(err, t)),
        });
      }}
    >
      <h3 className="font-semibold">{t('assignments.giveTitle')}</h3>
      {error && <FormAlert>{error}</FormAlert>}
      {notice && <FormNotice>{notice}</FormNotice>}
      <Select
        label={t('assignments.kind')}
        options={[
          { value: 'exam', label: t('assignments.exam') },
          { value: 'lesson', label: t('assignments.lessonOrChapterTest') },
        ]}
        value={kind}
        onValueChange={(v) => setKind(v as 'exam' | 'lesson')}
      />
      <Input label={kind === 'exam' ? t('assignments.testVersionId') : t('assignments.lessonId')} value={target} onChange={(e) => setTarget(e.target.value)} />
      <Input label={t('assignments.dueAt')} type="datetime-local" value={dueAt} onChange={(e) => setDueAt(e.target.value)} />
      <div>
        <Button type="submit" loading={create.isPending}>
          {t('assignments.give')}
        </Button>
      </div>
    </form>
  );
}
