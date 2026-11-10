import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { Badge, Button, EmptyState, Input, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { addPart, draftOf, moveGroup, removePart, updatePart } from './model';
import { issuesAt, issuesOf, testProblemText, type FieldIssue } from './problems';
import { useExams, useGroupChoices, useTest, useTestMutations } from './queries';
import type { ContentStatus, ExamInfo, TestDraft, TestVersion } from './types';

const TONE: Record<ContentStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'info',
  CHANGES_REQUESTED: 'warning',
  PUBLISHED: 'success',
  ARCHIVED: 'neutral',
};

/** /cms/tests/new and /cms/tests/:id: make a test, build it part by part from question groups, check it against the exam and submit it. */
export default function TestBuilderPage() {
  const { id } = useParams();
  return id ? <Edit testId={id} /> : <Create />;
}

function Heading({ children }: { children: string }) {
  return <h1 className="font-serif text-3xl font-bold text-ink">{children}</h1>;
}

function Create() {
  const { t } = useTranslation('tests');
  const navigate = useNavigate();
  const exams = useExams();
  const { create } = useTestMutations();
  const [exam, setExam] = useState<string | undefined>();
  const [variant, setVariant] = useState<string | undefined>();
  const [title, setTitle] = useState('');
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const variants = useMemo(() => (exams.data ?? []).find((e) => e.code === exam)?.variants ?? [], [exams.data, exam]);

  return (
    <div className="flex flex-col gap-6">
      <Heading>{t('new.title')}</Heading>
      <p className="max-w-prose text-muted">{t('new.description')}</p>
      {exams.isPending ? (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      ) : (
        <form
          className="flex max-w-xl flex-col gap-4"
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            if (!exam) return;
            setProblem(null);
            setIssues([]);
            create.mutate(
              { examCode: exam, variant: variants.length ? (variant ?? null) : null, title },
              {
                onSuccess: (v) => navigate(`/cms/tests/${v.testId}`),
                onError: (err) => {
                  setIssues(issuesOf(err));
                  setProblem(testProblemText(t, err));
                },
              },
            );
          }}
        >
          <div className="flex flex-wrap gap-3">
            <Select
              label={t('new.exam')}
              placeholder={t('new.examPlaceholder')}
              value={exam}
              onValueChange={(v) => {
                setExam(v);
                setVariant(undefined);
              }}
              options={(exams.data ?? []).map((e) => ({ value: e.code, label: e.code }))}
              className="min-w-40"
            />
            {variants.length > 0 && (
              <Select
                label={t('new.variant')}
                placeholder={t('new.variantPlaceholder')}
                value={variant}
                onValueChange={setVariant}
                options={variants.map((v) => ({ value: v, label: t(`variant.${v}`, { defaultValue: v }) }))}
                error={issuesAt(issues, 'variant')}
                className="min-w-56"
              />
            )}
          </div>
          <Input label={t('new.name')} value={title} onChange={(e) => setTitle(e.target.value)} error={issuesAt(issues, 'title')} maxLength={200} />
          <div>
            <Button type="submit" loading={create.isPending} disabled={!exam}>
              {t('actions.create')}
            </Button>
          </div>
          {problem && issues.length === 0 && <FormAlert>{problem}</FormAlert>}
        </form>
      )}
    </div>
  );
}

function Edit({ testId }: { testId: string }) {
  const { t } = useTranslation('tests');
  const test = useTest(testId);
  const exams = useExams();
  const [notice, setNotice] = useState<string | null>(null);
  const v = test.data;
  if (test.isPending || exams.isPending) {
    return (
      <p role="status" className="text-muted">
        {t('loading')}
      </p>
    );
  }
  if (test.isError || !v) {
    const notFound = test.error instanceof ApiProblemError && (test.error.status === 404 || test.error.status === 403);
    return <EmptyState title={t(notFound ? 'edit.notFoundTitle' : 'errors.generic')} description={notFound ? t('edit.notFound') : undefined} />;
  }
  const exam = (exams.data ?? []).find((e) => e.code === v.examCode);
  // a different version (a new draft after saving a published one) starts from the server's copy
  return <EditBody key={v.versionId} v={v} exam={exam} notice={notice} setNotice={setNotice} />;
}

function EditBody({ v, exam, notice, setNotice }: { v: TestVersion; exam: ExamInfo | undefined; notice: string | null; setNotice: (n: string | null) => void }) {
  const { t } = useTranslation('tests');
  const { save, submit } = useTestMutations();
  const groups = useGroupChoices(v.examCode);
  const [draft, setDraft] = useState<TestDraft>(() => draftOf(v));
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const editable = v.status === 'DRAFT' || v.status === 'CHANGES_REQUESTED';
  const published = v.status === 'PUBLISHED';
  const locked = !(editable || published);
  const sectionCodes = (exam?.sections ?? []).map((s) => s.code);
  const titleOf = (groupVersionId: string) => (groups.data ?? []).find((g) => g.groupVersionId === groupVersionId)?.title;

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link to="/cms/tests" className="text-sm text-primary underline">
          {t('edit.back')}
        </Link>
        <Heading>{v.title}</Heading>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted">
          <Badge tone={TONE[v.status]}>{t(`status.${v.status}`)}</Badge>
          <span>{t('edit.version', { n: v.versionNo })}</span>
          <span>{v.variant ? `${v.examCode} ${v.variant}` : v.examCode}</span>
        </p>
        {published && <FormNotice tone="info">{t('edit.publishedNote', { next: v.versionNo + 1 })}</FormNotice>}
        {v.status === 'IN_REVIEW' && <FormNotice tone="info">{t('edit.inReviewNote')}</FormNotice>}
        {v.status === 'ARCHIVED' && <FormNotice tone="info">{t('edit.archivedNote')}</FormNotice>}
        {notice && <FormNotice>{notice}</FormNotice>}
      </header>

      <form
        className="flex flex-col gap-6"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          setProblem(null);
          setIssues([]);
          setNotice(null);
          save.mutate(
            { testId: v.testId, draft },
            {
              onSuccess: ({ version, newVersion }) => setNotice(newVersion ? t('edit.savedNewVersion', { n: version.versionNo }) : t('edit.saved')),
              onError: (err) => {
                setIssues(issuesOf(err));
                setProblem(testProblemText(t, err));
              },
            },
          );
        }}
      >
        <div className="flex max-w-xl flex-wrap gap-3">
          <Input
            label={t('edit.name')}
            value={draft.title}
            disabled={locked}
            onChange={(e) => setDraft({ ...draft, title: e.target.value })}
            error={issuesAt(issues, 'title')}
            className="min-w-64 flex-1"
          />
          <Input
            label={t('edit.maxResumes')}
            hint={t('edit.maxResumesHint')}
            type="number"
            min={0}
            max={20}
            value={draft.maxResumes}
            disabled={locked}
            onChange={(e) => setDraft({ ...draft, maxResumes: e.target.value })}
            error={issuesAt(issues, 'maxResumes')}
            className="w-48"
          />
        </div>

        <section aria-labelledby="parts-h" className="flex flex-col gap-4">
          <h2 id="parts-h" className="font-serif text-xl font-bold text-ink">
            {t('parts.heading')}
          </h2>
          {draft.parts.length === 0 && <p className="text-muted">{t('parts.empty')}</p>}
          {draft.parts.map((p, i) => {
            const used = new Set(draft.parts.filter((_, j) => j !== i).map((x) => x.sectionCode));
            const choices = (groups.data ?? []).filter((g) => !draft.parts.some((x) => x.groups.some((y) => y.groupVersionId === g.groupVersionId)));
            return (
              <fieldset key={`${p.sectionCode}-${i}`} className="flex flex-col gap-3 rounded-md border border-edge p-4" disabled={locked}>
                <legend className="px-1 text-sm font-semibold text-ink">{t('parts.partN', { n: i + 1 })}</legend>
                <div className="flex flex-wrap items-end gap-3">
                  <Select
                    label={t('parts.section')}
                    value={p.sectionCode}
                    onValueChange={(code) => setDraft(updatePart(draft, i, { sectionCode: code }))}
                    options={sectionCodes.map((code) => ({ value: code, label: code, disabled: used.has(code) }))}
                    error={issuesAt(issues, `parts[${i}].sectionCode`)}
                    className="min-w-40"
                  />
                  <Input
                    label={t('parts.minutes')}
                    type="number"
                    min={0}
                    step="any"
                    value={p.minutes}
                    onChange={(e) => setDraft(updatePart(draft, i, { minutes: e.target.value }))}
                    error={issuesAt(issues, `parts[${i}].durationSec`)}
                    className="w-40"
                  />
                  <Button type="button" variant="secondary" size="sm" onClick={() => setDraft(removePart(draft, i))}>
                    {t('parts.remove')}
                  </Button>
                </div>
                <ul className="flex flex-col gap-2" aria-label={t('parts.groupsOf', { n: i + 1 })}>
                  {p.groups.map((g, j) => (
                    <li key={g.groupVersionId} className="flex flex-wrap items-center gap-2">
                      <span className="min-w-0 flex-1 truncate text-ink">{titleOf(g.groupVersionId) ?? g.title}</span>
                      <Button type="button" variant="secondary" size="sm" aria-label={t('parts.up', { title: g.title })} disabled={j === 0} onClick={() => setDraft(moveGroup(draft, i, j, -1))}>
                        ↑
                      </Button>
                      <Button type="button" variant="secondary" size="sm" aria-label={t('parts.down', { title: g.title })} disabled={j === p.groups.length - 1} onClick={() => setDraft(moveGroup(draft, i, j, 1))}>
                        ↓
                      </Button>
                      <Button
                        type="button"
                        variant="secondary"
                        size="sm"
                        aria-label={t('parts.removeGroup', { title: g.title })}
                        onClick={() => setDraft(updatePart(draft, i, { groups: p.groups.filter((x) => x.groupVersionId !== g.groupVersionId) }))}
                      >
                        {t('parts.removeGroupShort')}
                      </Button>
                    </li>
                  ))}
                </ul>
                {issuesAt(issues, `parts[${i}].groups`) && (
                  <p role="alert" className="text-sm font-medium text-danger">
                    {issuesAt(issues, `parts[${i}].groups`)}
                  </p>
                )}
                <Select
                  label={t('parts.addGroup')}
                  placeholder={groups.isPending ? t('loading') : t('parts.addGroupPlaceholder')}
                  value=""
                  onValueChange={(groupVersionId) => {
                    const g = choices.find((c) => c.groupVersionId === groupVersionId);
                    if (g) setDraft(updatePart(draft, i, { groups: [...p.groups, { groupVersionId, title: g.title }] }));
                  }}
                  options={choices.map((g) => ({ value: g.groupVersionId, label: t('parts.groupOption', { title: g.title, members: g.members, status: t(`status.${g.status}`) }) }))}
                  className="max-w-lg"
                />
              </fieldset>
            );
          })}
          {!locked && (
            <div>
              <Button type="button" variant="secondary" onClick={() => setDraft(addPart(draft, sectionCodes))} disabled={draft.parts.length >= sectionCodes.length}>
                {t('parts.add')}
              </Button>
            </div>
          )}
        </section>

        <div className="flex flex-wrap gap-3">
          {!locked && (
            <Button type="submit" loading={save.isPending}>
              {published ? t('actions.saveNewVersion') : t('actions.save')}
            </Button>
          )}
          {editable && (
            <Button
              type="button"
              variant="secondary"
              loading={submit.isPending}
              onClick={() => {
                setProblem(null);
                setNotice(null);
                submit.mutate(v.testId, {
                  onSuccess: () => setNotice(t('edit.submitted')),
                  onError: (err) => setProblem(testProblemText(t, err) + issuesOf(err).map((i) => ` ${i.message}`).join('')),
                });
              }}
            >
              {t('actions.submit')}
            </Button>
          )}
        </div>
        {problem && <FormAlert>{problem}</FormAlert>}
      </form>

      <section aria-labelledby="check-h" className="flex flex-col gap-3">
        <h2 id="check-h" className="font-serif text-xl font-bold text-ink">
          {t('check.heading')}
        </h2>
        <p className="max-w-prose text-sm text-muted">{t('check.note')}</p>
        {v.blockers.length > 0 && (
          <FormNotice tone="info">
            {t('check.blockers')} {v.blockers.map((b) => b.skill).join(', ')}
          </FormNotice>
        )}
        <Table caption={t('check.caption')}>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('check.colSection')}</TableHeaderCell>
              <TableHeaderCell>{t('check.colSkill')}</TableHeaderCell>
              <TableHeaderCell>{t('check.colExpected')}</TableHeaderCell>
              <TableHeaderCell>{t('check.colActual')}</TableHeaderCell>
              <TableHeaderCell>{t('check.colState')}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {v.sections.map((s) => (
              <TableRow key={s.sectionCode}>
                <TableCell>{s.sectionCode}</TableCell>
                <TableCell>{s.skill}</TableCell>
                <TableCell>{s.expected}</TableCell>
                <TableCell>{s.actual}</TableCell>
                <TableCell>
                  <Badge tone={s.state === 'OK' ? 'success' : s.state === 'MISSING' ? 'danger' : 'warning'}>{t(`check.state.${s.state}`)}</Badge>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {v.scoreTableOverrides.length > 0 && (
          <p className="text-sm text-muted">{t('check.overrides', { skills: v.scoreTableOverrides.map((o) => o.skill).join(', ') })}</p>
        )}
      </section>
    </div>
  );
}
