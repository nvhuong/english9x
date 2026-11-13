import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { Badge, Button, EmptyState, Input, Select } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { EMPTY_DRAFT, addChapter, addLesson, draftOf, lessonOf, moveLesson, removeChapter, removeLesson, updateChapter } from './model';
import { SharedCatalogPanel } from './SharedCatalogPanel';
import { courseProblemText, issuesAt, issuesOf, type FieldIssue } from './problems';
import { useCourse, useCourseMutations, useExamCodes, useRefChoices } from './queries';
import type { ContentStatus, CourseDraft, CourseVersion, LessonKind, UnlockRule } from './types';

const TONE: Record<ContentStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'info',
  CHANGES_REQUESTED: 'warning',
  PUBLISHED: 'success',
  ARCHIVED: 'neutral',
};
const RULES: UnlockRule[] = ['STRICT', 'COMPLETION', 'FREE'];
const KINDS: LessonKind[] = ['LESSON', 'CHAPTER_TEST', 'MOCK_TEST'];

/** /cms/courses/new and /cms/courses/:id: build a course chapter by chapter from published lessons and tests, then submit it for review. */
export default function CourseBuilderPage() {
  const { id } = useParams();
  return id ? <Edit courseId={id} /> : <Editor key="new" version={undefined} />;
}

function Edit({ courseId }: { courseId: string }) {
  const { t } = useTranslation('courses');
  const course = useCourse(courseId);
  if (course.isPending) {
    return (
      <p role="status" className="text-muted">
        {t('loading')}
      </p>
    );
  }
  if (course.isError || !course.data) {
    const notFound = course.error instanceof ApiProblemError && (course.error.status === 404 || course.error.status === 403);
    return <EmptyState title={t(notFound ? 'edit.notFoundTitle' : 'errors.generic')} description={notFound ? t('edit.notFound') : undefined} />;
  }
  // a different version (a new draft after saving a published one) starts from the server's copy
  return <Editor key={course.data.versionId} version={course.data} />;
}

function Editor({ version }: { version: CourseVersion | undefined }) {
  const { t } = useTranslation('courses');
  const navigate = useNavigate();
  const exams = useExamCodes();
  const { create, save, submit } = useCourseMutations();
  const [draft, setDraft] = useState<CourseDraft>(() => (version ? draftOf(version) : EMPTY_DRAFT));
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const choices = useRefChoices(draft.examCode || undefined);
  const isNew = !version;
  const status = version?.status;
  const editable = isNew || status === 'DRAFT' || status === 'CHANGES_REQUESTED';
  const published = status === 'PUBLISHED';
  const locked = !(editable || published);
  // always from the latest draft: a handler that kept an older copy (a select that closes while the next field is typed in) would write it back
  const patch = (change: Partial<CourseDraft>) => setDraft((d) => ({ ...d, ...change }));
  const fail = (err: unknown) => {
    setIssues(issuesOf(err));
    setProblem(courseProblemText(t, err));
  };

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link to="/cms/courses" className="text-sm text-primary underline">
          {t('edit.back')}
        </Link>
        <h1 className="font-serif text-3xl font-bold text-ink">{version ? version.title : t('new.title')}</h1>
        {version && status && (
          <p className="flex flex-wrap items-center gap-2 text-sm text-muted">
            <Badge tone={TONE[status]}>{t(`status.${status}`)}</Badge>
            <span>{t('edit.version', { n: version.versionNo })}</span>
            <span>{`${version.examCode} · ${version.code}`}</span>
          </p>
        )}
        {published && version && <FormNotice tone="info">{t('edit.publishedNote', { next: version.versionNo + 1 })}</FormNotice>}
        {status === 'IN_REVIEW' && <FormNotice tone="info">{t('edit.inReviewNote')}</FormNotice>}
        {status === 'ARCHIVED' && <FormNotice tone="info">{t('edit.archivedNote')}</FormNotice>}
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
          if (isNew) {
            create.mutate(draft, { onSuccess: (v) => navigate(`/cms/courses/${v.courseId}`), onError: fail });
          } else {
            save.mutate(
              { courseId: version.courseId, draft },
              { onSuccess: ({ version: v, newVersion }) => setNotice(newVersion ? t('edit.savedNewVersion', { n: v.versionNo }) : t('edit.saved')), onError: fail },
            );
          }
        }}
      >
        <fieldset className="grid max-w-3xl gap-4 sm:grid-cols-2" disabled={locked}>
          <legend className="sr-only">{t('form.heading')}</legend>
          {isNew && (
            <>
              <Select
                label={t('new.exam')}
                placeholder={t('new.examPlaceholder')}
                value={draft.examCode || undefined}
                onValueChange={(v) => patch({ examCode: v, chapters: [] })}
                options={(exams.data ?? []).map((e) => ({ value: e, label: e }))}
                error={issuesAt(issues, 'examCode')}
              />
              <Input
                label={t('new.code')}
                hint={t('new.codeHint')}
                value={draft.code}
                maxLength={6}
                onChange={(e) => patch({ code: e.target.value.toUpperCase() })}
                error={issuesAt(issues, 'code')}
              />
            </>
          )}
          <Input label={t('form.name')} value={draft.title} maxLength={200} onChange={(e) => patch({ title: e.target.value })} error={issuesAt(issues, 'title')} className="sm:col-span-2" />
          <Input label={t('form.description')} value={draft.description} onChange={(e) => patch({ description: e.target.value })} error={issuesAt(issues, 'description')} className="sm:col-span-2" />
          <Input label={t('form.author')} hint={t('form.authorHint')} value={draft.authorDisplay} maxLength={200} onChange={(e) => patch({ authorDisplay: e.target.value })} error={issuesAt(issues, 'authorDisplay')} />
          <Select
            label={t('form.unlockRule')}
            value={draft.unlockRule}
            onValueChange={(v) => patch({ unlockRule: v as UnlockRule })}
            options={RULES.map((r) => ({ value: r, label: t(`rule.${r}`) }))}
            error={issuesAt(issues, 'unlockRule')}
          />
          <p className="text-sm text-muted sm:col-span-2">{t(`ruleHint.${draft.unlockRule}`)}</p>
        </fieldset>

        <section aria-labelledby="chapters-h" className="flex flex-col gap-4">
          <h2 id="chapters-h" className="font-serif text-xl font-bold text-ink">
            {t('chapters.heading')}
          </h2>
          {draft.chapters.length === 0 && <p className="text-muted">{t('chapters.empty')}</p>}
          {draft.chapters.map((c, i) => (
            <fieldset key={i} className="flex flex-col gap-3 rounded-md border border-edge p-4" disabled={locked}>
              <legend className="px-1 text-sm font-semibold text-ink">{t('chapters.chapterN', { n: i + 1 })}</legend>
              <div className="flex flex-wrap items-end gap-3">
                <Input label={t('chapters.title')} value={c.title} maxLength={200} onChange={(e) => setDraft((d) => updateChapter(d, i, { title: e.target.value }))} error={issuesAt(issues, `chapters[${i}].title`)} className="min-w-64 flex-1" />
                <Input
                  label={t('chapters.passPercent')}
                  hint={t('chapters.passPercentHint')}
                  type="number"
                  min={0}
                  max={100}
                  value={c.passPercent}
                  onChange={(e) => setDraft((d) => updateChapter(d, i, { passPercent: e.target.value }))}
                  error={issuesAt(issues, `chapters[${i}].passThreshold`)}
                  className="w-40"
                />
                <Button type="button" variant="secondary" size="sm" onClick={() => setDraft((d) => removeChapter(d, i))}>
                  {t('chapters.remove')}
                </Button>
              </div>
              <ul className="flex flex-col gap-2" aria-label={t('lessons.of', { n: i + 1 })}>
                {c.lessons.map((l, j) => (
                  <li key={`${l.refId}-${j}`} className="flex flex-wrap items-center gap-2">
                    <span className="min-w-0 flex-1 truncate text-ink">{l.title}</span>
                    {l.kind !== 'LESSON' && (
                      <Select
                        label={t('lessons.kind')}
                        value={l.kind}
                        onValueChange={(k) => setDraft((d) => updateChapter(d, i, { lessons: (d.chapters[i]?.lessons ?? []).map((x, y) => (y === j ? { ...x, kind: k as LessonKind } : x)) }))}
                        options={KINDS.filter((k) => k !== 'LESSON').map((k) => ({ value: k, label: t(`kind.${k}`) }))}
                        className="w-44"
                      />
                    )}
                    {l.kind === 'LESSON' && <Badge>{t('kind.LESSON')}</Badge>}
                    <Button type="button" variant="secondary" size="sm" aria-label={t('lessons.up', { title: l.title })} disabled={j === 0} onClick={() => setDraft((d) => moveLesson(d, i, j, -1))}>
                      ↑
                    </Button>
                    <Button type="button" variant="secondary" size="sm" aria-label={t('lessons.down', { title: l.title })} disabled={j === c.lessons.length - 1} onClick={() => setDraft((d) => moveLesson(d, i, j, 1))}>
                      ↓
                    </Button>
                    <Button type="button" variant="secondary" size="sm" aria-label={t('lessons.remove', { title: l.title })} onClick={() => setDraft((d) => removeLesson(d, i, j))}>
                      {t('lessons.removeShort')}
                    </Button>
                    {issuesAt(issues, `chapters[${i}].lessons[${j}]`) && (
                      <p role="alert" className="w-full text-sm font-medium text-danger">
                        {issuesAt(issues, `chapters[${i}].lessons[${j}]`)}
                      </p>
                    )}
                  </li>
                ))}
              </ul>
              {issuesAt(issues, `chapters[${i}].lessons`) && c.lessons.length === 0 && (
                <p role="alert" className="text-sm font-medium text-danger">
                  {issuesAt(issues, `chapters[${i}].lessons`)}
                </p>
              )}
              <Select
                label={t('lessons.add')}
                placeholder={!draft.examCode ? t('lessons.chooseExamFirst') : choices.isPending ? t('loading') : t('lessons.addPlaceholder')}
                value=""
                onValueChange={(refId) => {
                  const choice = (choices.data ?? []).find((x) => x.refId === refId);
                  if (choice) setDraft((d) => addLesson(d, i, lessonOf(choice)));
                }}
                options={(choices.data ?? []).map((x) => ({ value: x.refId, label: t('lessons.option', { title: x.title, what: t(`refKind.${x.kind}`), status: t(`status.${x.status}`) }) }))}
                className="max-w-lg"
              />
            </fieldset>
          ))}
          {!locked && (
            <div>
              <Button type="button" variant="secondary" onClick={() => setDraft((d) => addChapter(d))}>
                {t('chapters.add')}
              </Button>
            </div>
          )}
          {issuesAt(issues, 'chapters') && draft.chapters.length === 0 && (
            <p role="alert" className="text-sm font-medium text-danger">
              {issuesAt(issues, 'chapters')}
            </p>
          )}
        </section>

        <div className="flex flex-wrap gap-3">
          {!locked && (
            <Button type="submit" loading={create.isPending || save.isPending}>
              {isNew ? t('actions.create') : published ? t('actions.saveNewVersion') : t('actions.save')}
            </Button>
          )}
          {version && (status === 'DRAFT' || status === 'CHANGES_REQUESTED') && (
            <Button
              type="button"
              variant="secondary"
              loading={submit.isPending}
              onClick={() => {
                setProblem(null);
                setNotice(null);
                submit.mutate(version.courseId, { onSuccess: () => setNotice(t('edit.submitted')), onError: fail });
              }}
            >
              {t('actions.submit')}
            </Button>
          )}
        </div>
        {problem && <FormAlert>{problem}</FormAlert>}
      </form>
      {published && version && <SharedCatalogPanel version={version} />}
    </div>
  );
}
