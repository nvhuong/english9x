import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge, Button, Input, Select } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { scoreProblemText } from './problems';
import { useAttachOverride, useExamScores, useTestOverrides } from './queries';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * /admin/score-overrides (SYS_ADMIN, English9x tests) and /org/score-overrides (ORG_ADMIN, the organisation's tests): the administrator of the test's owner
 * attaches the override of one score to a test by its id. An author then writes the rows on the score-table editor and a reviewer publishes it (F13, F15).
 * The administrator does not open the test builder (an author's screen), so the control lives here too; the server decides who may attach.
 */
export default function OverrideAttachPage() {
  const { t } = useTranslation('scoring');
  const exams = useExamScores();
  const attach = useAttachOverride();
  const [testId, setTestId] = useState('');
  const [exam, setExam] = useState<string | undefined>();
  const [skill, setSkill] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const id = testId.trim().toLowerCase();
  const valid = UUID.test(id);
  const overrides = useTestOverrides(valid ? id : undefined);
  const skills = useMemo(() => (exams.data ?? []).find((e) => e.code === exam)?.scoreSkills ?? [], [exams.data, exam]);

  return (
    <div className="flex flex-col gap-6">
      <h1 className="font-serif text-3xl font-bold text-ink">{t('attach.title')}</h1>
      <p className="max-w-prose text-muted">{t('attach.description')}</p>
      <div className="flex max-w-xl flex-col gap-4">
        <Input label={t('attach.testId')} hint={t('attach.testIdHint')} value={testId} onChange={(e) => setTestId(e.target.value)} />
        <div className="flex flex-wrap gap-3">
          <Select
            label={t('new.exam')}
            placeholder={t('new.examPlaceholder')}
            value={exam}
            onValueChange={(v) => {
              setExam(v);
              setSkill(undefined);
            }}
            options={(exams.data ?? []).map((e) => ({ value: e.code, label: e.code }))}
            className="min-w-40"
          />
          <Select label={t('overrides.skill')} placeholder={t('overrides.skillPlaceholder')} value={skill} disabled={!exam} onValueChange={setSkill} options={skills.map((s) => ({ value: s, label: s }))} className="min-w-56" />
        </div>
        <div>
          <Button
            type="button"
            disabled={!valid || !skill}
            loading={attach.isPending}
            onClick={() => {
              if (!skill) return;
              setProblem(null);
              setDone(null);
              attach.mutate(
                { testId: id, skill },
                {
                  onSuccess: (o) => setDone(t('attach.done', { skill: o.skill })),
                  onError: (err) => setProblem(scoreProblemText(t, err)),
                },
              );
            }}
          >
            {t('overrides.attach')}
          </Button>
        </div>
        {done && <FormNotice>{done}</FormNotice>}
        {problem && <FormAlert>{problem}</FormAlert>}
      </div>
      {valid && (overrides.data ?? []).length > 0 && (
        <section aria-labelledby="attached-h" className="flex flex-col gap-2">
          <h2 id="attached-h" className="font-serif text-xl font-bold text-ink">
            {t('overrides.heading')}
          </h2>
          <ul className="flex flex-col gap-2" aria-label={t('overrides.list')}>
            {(overrides.data ?? []).map((o) => (
              <li key={o.tableId} className="flex flex-wrap items-center gap-2">
                <span className="font-semibold text-ink">{`${o.examCode} ${o.skill}`}</span>
                {o.versionNo === null ? <Badge tone="warning">{t('overrides.noRows')}</Badge> : <Badge tone="neutral">{`${t('edit.version', { n: o.versionNo })} · ${o.status ? t(`status.${o.status}`) : ''}`}</Badge>}
                <span className="text-sm text-muted">{o.tableId}</span>
                <Link to={`/cms/score-tables/${o.tableId}?test=${id}`} className="text-sm text-primary underline">
                  {t('attach.openEditor')}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
