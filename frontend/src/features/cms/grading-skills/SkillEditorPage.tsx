import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { Badge, Button, EmptyState, Select } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { useQuestionTypeForms } from '../questions/queries';
import { TextArea } from '../lesson-editor/fields';
import { draftOf, emptyModel, modelOf, type SkillModel } from './model';
import { issuesOf, skillProblemText, type FieldIssue } from './problems';
import { useSkill, useSkillMutations } from './queries';
import { SkillForm } from './SkillForm';
import { GRADED_KINDS, type ContentStatus, type SkillDraft, type SkillVersion } from './types';

const TONE: Record<ContentStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'info',
  CHANGES_REQUESTED: 'warning',
  PUBLISHED: 'success',
  ARCHIVED: 'neutral',
};

/** /cms/grading-skills/new and /cms/grading-skills/:id: write the grading skill of a Writing or Speaking type or change it; submit it; run it on a sample answer. */
export default function SkillEditorPage() {
  const { id } = useParams();
  return id ? <Edit skillId={id} /> : <Create />;
}

function Heading({ children }: { children: string }) {
  return <h1 className="font-serif text-3xl font-bold text-ink">{children}</h1>;
}

/** The draft to send, or null (with the reason on the form) when the schema text is not JSON. */
function draftOrIssue(schemaJsonMessage: string, code: string, m: SkillModel, setIssues: (i: FieldIssue[]) => void): SkillDraft | null {
  try {
    return draftOf(code, m);
  } catch {
    setIssues([{ field: 'resultSchema', message: schemaJsonMessage }]);
    return null;
  }
}

function Create() {
  const { t } = useTranslation('skills');
  const navigate = useNavigate();
  const types = useQuestionTypeForms();
  const { create } = useSkillMutations();
  const [code, setCode] = useState<string | undefined>();
  const [model, setModel] = useState<SkillModel>(emptyModel());
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const graded = useMemo(() => (types.data ?? []).filter((x) => GRADED_KINDS.includes(x.kind)), [types.data]);

  return (
    <div className="flex flex-col gap-6">
      <Heading>{t('new.title')}</Heading>
      <p className="max-w-prose text-muted">{t('new.description')}</p>
      {types.isPending ? (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      ) : graded.length === 0 ? (
        <EmptyState title={t('new.noneTitle')} description={t('new.none')} />
      ) : (
        <>
          <Select
            label={t('new.type')}
            placeholder={t('new.typePlaceholder')}
            value={code}
            onValueChange={(v) => {
              setCode(v);
              setIssues([]);
            }}
            options={graded.map((x) => ({ value: x.code, label: `${x.examCode} · ${x.code}` }))}
            className="max-w-md"
          />
          {code && (
            <SkillForm
              key={code}
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
                const draft = draftOrIssue(t('errors.schemaJson'), code, m, setIssues);
                if (!draft) return;
                setIssues([]);
                create.mutate(draft, {
                  onSuccess: (v) => navigate(`/cms/grading-skills/${v.skillId}`),
                  onError: (err) => {
                    setIssues(issuesOf(err));
                    setProblem(skillProblemText(t, err));
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

function Edit({ skillId }: { skillId: string }) {
  const { t } = useTranslation('skills');
  const skill = useSkill(skillId);
  const [notice, setNotice] = useState<string | null>(null);
  const v = skill.data;
  if (skill.isPending) {
    return (
      <p role="status" className="text-muted">
        {t('loading')}
      </p>
    );
  }
  if (skill.isError || !v) {
    const notFound = skill.error instanceof ApiProblemError && (skill.error.status === 404 || skill.error.status === 403);
    return <EmptyState title={t(notFound ? 'edit.notFoundTitle' : 'errors.generic')} description={notFound ? t('edit.notFound') : undefined} />;
  }
  // a different version (a new draft after saving a published one) starts from the server's copy
  return <EditBody key={v.versionId} v={v} notice={notice} setNotice={setNotice} />;
}

function EditBody({ v, notice, setNotice }: { v: SkillVersion; notice: string | null; setNotice: (n: string | null) => void }) {
  const { t } = useTranslation('skills');
  const { save, submit } = useSkillMutations();
  const [model, setModel] = useState<SkillModel>(() => modelOf(v));
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const editable = v.status === 'DRAFT' || v.status === 'CHANGES_REQUESTED';
  const published = v.status === 'PUBLISHED';

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-2">
        <Link to="/cms/grading-skills" className="text-sm text-primary underline">
          {t('edit.back')}
        </Link>
        <Heading>{t('edit.title', { type: v.questionTypeCode })}</Heading>
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
      <SkillForm
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
          setNotice(null);
          const draft = draftOrIssue(t('errors.schemaJson'), v.questionTypeCode, m, setIssues);
          if (!draft) return;
          setIssues([]);
          save.mutate(
            { skillId: v.skillId, draft },
            {
              onSuccess: ({ version, newVersion }) => setNotice(newVersion ? t('edit.savedNewVersion', { n: version.versionNo }) : t('edit.saved')),
              onError: (err) => {
                setIssues(issuesOf(err));
                setProblem(skillProblemText(t, err));
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
                submit.mutate(v.skillId, {
                  onSuccess: () => setNotice(t('edit.submitted')),
                  onError: (err) => setProblem(skillProblemText(t, err)),
                });
              }}
            >
              {t('actions.submit')}
            </Button>
          )
        }
      />
      {problem && issues.length === 0 && <FormAlert>{problem}</FormAlert>}
      <DryRun skillId={v.skillId} />
    </div>
  );
}

/** "Chạy thử": the saved skill on a sample answer, no score kept. It needs the AI grader (P4.2); until then the server says so and nothing is sent anywhere. */
function DryRun({ skillId }: { skillId: string }) {
  const { t } = useTranslation('skills');
  const { dryRun } = useSkillMutations();
  const [response, setResponse] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  return (
    <section aria-labelledby="dry-run" className="flex max-w-3xl flex-col gap-3 border-t border-line pt-6">
      <h2 id="dry-run" className="font-serif text-xl font-bold text-ink">
        {t('dryRun.title')}
      </h2>
      <p className="text-sm text-muted">{t('dryRun.hint')}</p>
      <TextArea label={t('dryRun.response')} value={response} onValue={setResponse} rows={6} />
      <div>
        <Button
          variant="secondary"
          loading={dryRun.isPending}
          disabled={response.trim() === ''}
          onClick={() => {
            setMessage(null);
            dryRun.mutate({ skillId, response }, { onError: (err) => setMessage(skillProblemText(t, err)) });
          }}
        >
          {t('dryRun.run')}
        </Button>
      </div>
      {message && <FormNotice tone="info">{message}</FormNotice>}
    </section>
  );
}
