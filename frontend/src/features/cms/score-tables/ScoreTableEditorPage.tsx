import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { Badge, Button, EmptyState, Select } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { issuesOf, scoreProblemText, type FieldIssue } from './problems';
import { useExamScores, useScoreTable, useScoreTableMutations } from './queries';
import { draftOf, textOf } from './rows';
import { ScoreTableForm } from './ScoreTableForm';
import type { ContentStatus, ScoreTableVersion } from './types';

const TONE: Record<ContentStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'info',
  CHANGES_REQUESTED: 'warning',
  PUBLISHED: 'success',
  ARCHIVED: 'neutral',
};

/** /cms/score-tables/new and /cms/score-tables/:id: make the conversion table of an exam skill or change it; submit the draft for review. */
export default function ScoreTableEditorPage() {
  const { id } = useParams();
  return id ? <Edit tableId={id} /> : <Create />;
}

function Heading({ children }: { children: string }) {
  return <h1 className="font-serif text-3xl font-bold text-ink">{children}</h1>;
}

function Create() {
  const { t } = useTranslation('scoring');
  const navigate = useNavigate();
  const exams = useExamScores();
  const { create } = useScoreTableMutations();
  const [exam, setExam] = useState<string | undefined>();
  const [skill, setSkill] = useState<string | undefined>();
  const [text, setText] = useState('');
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const skills = useMemo(() => (exams.data ?? []).find((e) => e.code === exam)?.scoreSkills ?? [], [exams.data, exam]);

  return (
    <div className="flex flex-col gap-6">
      <Heading>{t('new.title')}</Heading>
      <p className="max-w-prose text-muted">{t('new.description')}</p>
      {exams.isPending ? (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      ) : (
        <>
          <div className="flex flex-wrap gap-3">
            <Select
              label={t('new.exam')}
              placeholder={t('new.examPlaceholder')}
              value={exam}
              onValueChange={(v) => {
                setExam(v);
                setSkill(undefined);
                setIssues([]);
              }}
              options={(exams.data ?? []).map((e) => ({ value: e.code, label: e.code }))}
              className="min-w-40"
            />
            <Select
              label={t('new.skill')}
              placeholder={t('new.skillPlaceholder')}
              value={skill}
              disabled={!exam}
              onValueChange={(v) => {
                setSkill(v);
                setIssues([]);
              }}
              options={skills.map((s) => ({ value: s, label: s }))}
              className="min-w-56"
            />
          </div>
          <p className="text-sm text-muted">{t('new.scope')}</p>
          {exam && skill && (
            <ScoreTableForm
              key={`${exam}/${skill}`}
              text={text}
              onChange={(v) => {
                setText(v);
                setIssues([]);
              }}
              issues={issues}
              saving={create.isPending}
              saveLabel={t('actions.createDraft')}
              onSave={() => {
                setProblem(null);
                setIssues([]);
                create.mutate(draftOf(exam, skill, text), {
                  onSuccess: (v) => navigate(`/cms/score-tables/${v.tableId}`),
                  onError: (err) => {
                    setIssues(issuesOf(err));
                    setProblem(scoreProblemText(t, err));
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

function Edit({ tableId }: { tableId: string }) {
  const { t } = useTranslation('scoring');
  const table = useScoreTable(tableId);
  const [notice, setNotice] = useState<string | null>(null);
  const v = table.data;
  if (table.isPending) {
    return (
      <p role="status" className="text-muted">
        {t('loading')}
      </p>
    );
  }
  if (table.isError || !v) {
    const notFound = table.error instanceof ApiProblemError && (table.error.status === 404 || table.error.status === 403);
    return <EmptyState title={t(notFound ? 'edit.notFoundTitle' : 'errors.generic')} description={notFound ? t('edit.notFound') : undefined} />;
  }
  // a different version (a new draft after saving a published one) starts from the server's copy
  return <EditBody key={v.versionId} v={v} notice={notice} setNotice={setNotice} />;
}

function EditBody({ v, notice, setNotice }: { v: ScoreTableVersion; notice: string | null; setNotice: (n: string | null) => void }) {
  const { t } = useTranslation('scoring');
  const { save, submit } = useScoreTableMutations();
  const [text, setText] = useState(() => textOf(v.rows));
  const [issues, setIssues] = useState<FieldIssue[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const computed = v.function !== 'TABLE';
  const editable = !computed && (v.status === 'DRAFT' || v.status === 'CHANGES_REQUESTED');
  const published = v.status === 'PUBLISHED';

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link to="/cms/score-tables" className="text-sm text-primary underline">
          {t('edit.back')}
        </Link>
        <Heading>{t('edit.title', { skill: v.skill })}</Heading>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted">
          <Badge tone={TONE[v.status]}>{t(`status.${v.status}`)}</Badge>
          <span>{t('edit.version', { n: v.versionNo })}</span>
          <span>{v.examCode}</span>
          <span>{t('edit.scopeExam')}</span>
        </p>
        {computed && <FormNotice tone="info">{t('edit.functionNote', { function: v.function })}</FormNotice>}
        {!computed && published && <FormNotice tone="info">{t('edit.publishedNote', { next: v.versionNo + 1 })}</FormNotice>}
        {v.status === 'IN_REVIEW' && <FormNotice tone="info">{t('edit.inReviewNote')}</FormNotice>}
        {v.status === 'ARCHIVED' && <FormNotice tone="info">{t('edit.archivedNote')}</FormNotice>}
        {notice && <FormNotice>{notice}</FormNotice>}
      </header>
      <ScoreTableForm
        text={text}
        onChange={(x) => {
          setText(x);
          setIssues([]);
        }}
        issues={issues}
        locked={computed || v.status === 'IN_REVIEW' || v.status === 'ARCHIVED'}
        saving={save.isPending}
        saveLabel={published ? t('actions.saveNewVersion') : t('actions.save')}
        onSave={() => {
          setProblem(null);
          setIssues([]);
          setNotice(null);
          save.mutate(
            { tableId: v.tableId, draft: draftOf(v.examCode, v.skill, text) },
            {
              onSuccess: ({ version, newVersion }) => setNotice(newVersion ? t('edit.savedNewVersion', { n: version.versionNo }) : t('edit.saved')),
              onError: (err) => {
                setIssues(issuesOf(err));
                setProblem(scoreProblemText(t, err));
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
                submit.mutate(v.tableId, {
                  onSuccess: () => setNotice(t('edit.submitted')),
                  onError: (err) => setProblem(scoreProblemText(t, err)),
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
