import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { Badge, Button, EmptyState, Select } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { questionProblemText, issuesOf, type FieldIssue } from './problems';
import { QuestionForm } from './QuestionForm';
import { useQuestion, useQuestionMutations, useQuestionTypeForms } from './queries';
import { draftOf, emptyModel, modelOf, type FormModel } from './schema';
import type { ContentStatus, QuestionTypeForm, QuestionVersion } from './types';

const TONE: Record<ContentStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'info',
  CHANGES_REQUESTED: 'warning',
  PUBLISHED: 'success',
  ARCHIVED: 'neutral',
};

/** /cms/questions/new and /cms/questions/:id: write a question of a type, or change one; submit the draft for review. */
export default function QuestionEditorPage() {
  const { id } = useParams();
  return id ? <Edit questionId={id} /> : <Create />;
}

function Heading({ children }: { children: string }) {
  return <h1 className="font-serif text-3xl font-bold text-ink">{children}</h1>;
}

function Create() {
  const { t } = useTranslation('questions');
  const navigate = useNavigate();
  const types = useQuestionTypeForms();
  const { create } = useQuestionMutations();
  const [code, setCode] = useState<string | undefined>();
  const [model, setModel] = useState<FormModel>(emptyModel());
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);

  const list = types.data ?? [];
  const type = list.find((x) => x.code === code);

  return (
    <div className="flex flex-col gap-6">
      <Heading>{t('new.title')}</Heading>
      <p className="max-w-prose text-muted">{t('new.description')}</p>
      {types.isPending ? (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      ) : list.length === 0 ? (
        <EmptyState title={t('new.noneTitle')} description={t('new.none')} />
      ) : (
        <>
          <Select
            label={t('new.type')}
            placeholder={t('new.typePlaceholder')}
            value={code}
            onValueChange={(v) => {
              setCode(v);
              setModel(emptyModel());
              setIssues([]);
              setProblem(null);
            }}
            options={list.map((x) => ({ value: x.code, label: `${x.examCode} · ${x.code}` }))}
            className="max-w-xl"
          />
          {type && (
            <QuestionForm
              key={type.code}
              type={type}
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
                create.mutate(draftOf(type.code, m), {
                  onSuccess: (v) => navigate(`/cms/questions/${v.questionId}`, { state: { saved: 'created' } }),
                  onError: (err) => {
                    setIssues(issuesOf(err));
                    setProblem(questionProblemText(t, err));
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

function Edit({ questionId }: { questionId: string }) {
  const { t } = useTranslation('questions');
  const question = useQuestion(questionId);
  const types = useQuestionTypeForms();
  // kept here, not in the body: saving a published question makes a new version, which remounts the body
  const [notice, setNotice] = useState<string | null>(null);
  const q = question.data;
  const type: QuestionTypeForm | undefined = useMemo(() => types.data?.find((x) => x.code === q?.typeCode), [types.data, q?.typeCode]);

  if (question.isPending || types.isPending) {
    return (
      <p role="status" className="text-muted">
        {t('loading')}
      </p>
    );
  }
  if (question.isError || !q) {
    const notFound = question.error instanceof ApiProblemError && (question.error.status === 404 || question.error.status === 403);
    return <EmptyState title={t(notFound ? 'edit.notFoundTitle' : 'errors.generic')} description={notFound ? t('edit.notFound') : undefined} />;
  }
  if (!type) {
    return <EmptyState title={t('edit.noTypeTitle')} description={t('edit.noType')} />;
  }
  // a different version (a new draft after saving a published one) starts from the server's copy; a refetch of the same version does not overwrite what is being typed
  return <EditBody key={`${q.versionId}`} q={q} type={type} notice={notice} setNotice={setNotice} />;
}

function EditBody({ q, type, notice, setNotice }: { q: QuestionVersion; type: QuestionTypeForm; notice: string | null; setNotice: (n: string | null) => void }) {
  const { t } = useTranslation('questions');
  const { save, submit } = useQuestionMutations();
  const [model, setModel] = useState<FormModel>(() => modelOf(q));
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const questionId = q.questionId;
  const editable = q.status === 'DRAFT' || q.status === 'CHANGES_REQUESTED';
  const publishedNote = q.status === 'PUBLISHED';

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link to="/cms/questions" className="text-sm text-primary underline">
          {t('edit.back')}
        </Link>
        <Heading>{t('edit.title')}</Heading>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted">
          <Badge tone={TONE[q.status]}>{t(`status.${q.status}`)}</Badge>
          <span>{t('edit.version', { n: q.versionNo })}</span>
        </p>
        {publishedNote && <FormNotice tone="info">{t('edit.publishedNote', { next: q.versionNo + 1 })}</FormNotice>}
        {q.status === 'IN_REVIEW' && <FormNotice tone="info">{t('edit.inReviewNote')}</FormNotice>}
        {q.status === 'ARCHIVED' && <FormNotice tone="info">{t('edit.archivedNote')}</FormNotice>}
        {notice && <FormNotice>{notice}</FormNotice>}
      </header>

      <QuestionForm
        type={type}
        model={model}
        onChange={(m) => {
          setModel(m);
          setIssues([]);
        }}
        issues={issues}
        locked={q.status === 'IN_REVIEW' || q.status === 'ARCHIVED'}
        saving={save.isPending}
        saveLabel={publishedNote ? t('actions.saveNewVersion') : t('actions.save')}
        onSave={(m) => {
          setProblem(null);
          setIssues([]);
          setNotice(null);
          save.mutate(
            { questionId, draft: draftOf(type.code, m) },
            {
              onSuccess: ({ version, newVersion }) => setNotice(newVersion ? t('edit.savedNewVersion', { n: version.versionNo }) : t('edit.saved')),
              onError: (err) => {
                setIssues(issuesOf(err));
                setProblem(questionProblemText(t, err));
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
                submit.mutate(questionId, {
                  onSuccess: () => setNotice(t('edit.submitted')),
                  onError: (err) => setProblem(questionProblemText(t, err)),
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
