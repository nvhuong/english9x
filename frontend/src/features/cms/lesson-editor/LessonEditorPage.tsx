import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { Badge, Button, EmptyState, Select } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { useQuestionTypeForms } from '../questions/queries';
import { LessonForm } from './LessonForm';
import { draftOf, emptyModel, modelOf, type LessonModel } from './model';
import { issuesOf, lessonProblemText, type FieldIssue } from './problems';
import { useLesson, useLessonMutations } from './queries';
import type { ContentStatus, LessonVersion } from './types';

const TONE: Record<ContentStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'info',
  CHANGES_REQUESTED: 'warning',
  PUBLISHED: 'success',
  ARCHIVED: 'neutral',
};

/** /cms/strategy-lessons/new and /cms/strategy-lessons/:id: write a strategy lesson or change one; submit the draft for review. */
export default function LessonEditorPage() {
  const { id } = useParams();
  return id ? <Edit lessonId={id} /> : <Create />;
}

function Heading({ children }: { children: string }) {
  return <h1 className="font-serif text-3xl font-bold text-ink">{children}</h1>;
}

function Create() {
  const { t } = useTranslation('lessons');
  const navigate = useNavigate();
  const types = useQuestionTypeForms();
  const { create } = useLessonMutations();
  const [exam, setExam] = useState<string | undefined>();
  const [model, setModel] = useState<LessonModel>(emptyModel());
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const list = useMemo(() => types.data ?? [], [types.data]);
  const exams = useMemo(() => [...new Set(list.map((x) => x.examCode))].sort(), [list]);

  return (
    <div className="flex flex-col gap-6">
      <Heading>{t('new.title')}</Heading>
      <p className="max-w-prose text-muted">{t('new.description')}</p>
      {types.isPending ? (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      ) : exams.length === 0 ? (
        <EmptyState title={t('new.noneTitle')} description={t('new.none')} />
      ) : (
        <>
          <Select
            label={t('new.exam')}
            placeholder={t('new.examPlaceholder')}
            value={exam}
            onValueChange={(v) => {
              setExam(v);
              setModel(emptyModel());
              setIssues([]);
            }}
            options={exams.map((e) => ({ value: e, label: e }))}
            className="max-w-xs"
          />
          {exam && (
            <LessonForm
              key={exam}
              examCode={exam}
              typeOptions={list.filter((x) => x.examCode === exam).map((x) => x.code)}
              model={model}
              onChange={(m) => {
                setModel(m);
                setIssues([]);
              }}
              issues={issues}
              saving={create.isPending}
              saveLabel={t('actions.createDraft')}
              onSave={(m) => {
                setProblem(null);
                setIssues([]);
                create.mutate(draftOf(exam, m), {
                  onSuccess: (v) => navigate(`/cms/strategy-lessons/${v.lessonId}`),
                  onError: (err) => {
                    setIssues(issuesOf(err));
                    setProblem(lessonProblemText(t, err));
                  },
                });
              }}
            />
          )}
          {problem && issues.length === 0 && <FormAlert>{problem}</FormAlert>}
        </>
      )}
    </div>
  );
}

function Edit({ lessonId }: { lessonId: string }) {
  const { t } = useTranslation('lessons');
  const lesson = useLesson(lessonId);
  const types = useQuestionTypeForms();
  const [notice, setNotice] = useState<string | null>(null);
  const v = lesson.data;
  if (lesson.isPending || types.isPending) {
    return (
      <p role="status" className="text-muted">
        {t('loading')}
      </p>
    );
  }
  if (lesson.isError || !v) {
    const notFound = lesson.error instanceof ApiProblemError && (lesson.error.status === 404 || lesson.error.status === 403);
    return <EmptyState title={t(notFound ? 'edit.notFoundTitle' : 'errors.generic')} description={notFound ? t('edit.notFound') : undefined} />;
  }
  // a different version (a new draft after saving a published one) starts from the server's copy
  return <EditBody key={v.versionId} v={v} typeOptions={(types.data ?? []).filter((x) => x.examCode === v.examCode).map((x) => x.code)} notice={notice} setNotice={setNotice} />;
}

function EditBody({ v, typeOptions, notice, setNotice }: { v: LessonVersion; typeOptions: string[]; notice: string | null; setNotice: (n: string | null) => void }) {
  const { t } = useTranslation('lessons');
  const { save, submit } = useLessonMutations();
  const [model, setModel] = useState<LessonModel>(() => modelOf(v));
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const editable = v.status === 'DRAFT' || v.status === 'CHANGES_REQUESTED';
  const published = v.status === 'PUBLISHED';

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link to="/cms/strategy-lessons" className="text-sm text-primary underline">
          {t('edit.back')}
        </Link>
        <Heading>{t('edit.title')}</Heading>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted">
          <Badge tone={TONE[v.status]}>{t(`status.${v.status}`)}</Badge>
          <span>{t('edit.version', { n: v.versionNo })}</span>
          <span>{v.examCode}</span>
        </p>
        {published && <FormNotice tone="info">{t('edit.publishedNote', { next: v.versionNo + 1 })}</FormNotice>}
        {v.status === 'IN_REVIEW' && <FormNotice tone="info">{t('edit.inReviewNote')}</FormNotice>}
        {v.status === 'ARCHIVED' && <FormNotice tone="info">{t('edit.archivedNote')}</FormNotice>}
        {notice && <FormNotice>{notice}</FormNotice>}
      </header>
      <LessonForm
        examCode={v.examCode}
        typeOptions={typeOptions}
        model={model}
        onChange={(m) => {
          setModel(m);
          setIssues([]);
        }}
        issues={issues}
        locked={v.status === 'IN_REVIEW' || v.status === 'ARCHIVED'}
        saving={save.isPending}
        saveLabel={published ? t('actions.saveNewVersion') : t('actions.save')}
        onSave={(m) => {
          setProblem(null);
          setIssues([]);
          setNotice(null);
          save.mutate(
            { lessonId: v.lessonId, draft: draftOf(v.examCode, m) },
            {
              onSuccess: ({ version, newVersion }) => setNotice(newVersion ? t('edit.savedNewVersion', { n: version.versionNo }) : t('edit.saved')),
              onError: (err) => {
                setIssues(issuesOf(err));
                setProblem(lessonProblemText(t, err));
              },
            },
          );
        }}
        actions={
          editable && (
            <Button
              variant="secondary"
              loading={submit.isPending}
              onClick={() => {
                setProblem(null);
                setNotice(null);
                submit.mutate(v.lessonId, {
                  onSuccess: () => setNotice(t('edit.submitted')),
                  onError: (err) => setProblem(lessonProblemText(t, err)),
                });
              }}
            >
              {t('actions.submit')}
            </Button>
          )
        }
      />
      {problem && issues.length === 0 && <FormAlert>{problem}</FormAlert>}
    </div>
  );
}
