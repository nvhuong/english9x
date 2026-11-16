import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useContextStore } from '../../../app/stores/context';
import { hasRole, useRoleStore } from '../../../app/stores/roles';
import { Badge, Button, Select } from '../../../design';
import { FormAlert } from '../../auth/components';
import { scoreProblemText } from './problems';
import { useAttachOverride, useExamScores, useTestOverrides } from './queries';

/**
 * "Bảng quy đổi ghi đè" of a test (F13, F15): the overrides it has, each linking to the table editor where an author writes its rows, and, for the administrator of
 * the test's owner, the control that attaches the override of one more skill. NOT a security boundary: the server decides who may attach or read.
 */
export function TestOverrides({ testId, examCode, variant }: { testId: string; examCode: string; variant: string | null }) {
  const { t } = useTranslation('scoring');
  const overrides = useTestOverrides(testId);
  const exams = useExamScores();
  const attach = useAttachOverride();
  const grants = useRoleStore((s) => s.grants);
  const orgId = useContextStore((s) => s.orgId);
  const [skill, setSkill] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | null>(null);
  const admin = hasRole(grants, orgId, ['SYS_ADMIN', 'ORG_ADMIN'], examCode);
  const taken = new Set((overrides.data ?? []).map((o) => o.skill));
  const choices = ((exams.data ?? []).find((e) => e.code === examCode)?.scoreSkills ?? []).filter(
    (s) => !taken.has(s) && !(s.startsWith('READING_') && variant && s !== `READING_${variant}`),
  );
  const list = overrides.data ?? [];
  if (!admin && list.length === 0) return null;

  return (
    <section aria-labelledby="overrides-h" className="flex flex-col gap-3">
      <h2 id="overrides-h" className="font-serif text-xl font-bold text-ink">
        {t('overrides.heading')}
      </h2>
      <p className="max-w-prose text-sm text-muted">{t('overrides.note')}</p>
      {list.length === 0 ? (
        <p className="text-muted">{t('overrides.empty')}</p>
      ) : (
        <ul className="flex flex-col gap-2" aria-label={t('overrides.list')}>
          {list.map((o) => (
            <li key={o.tableId} className="flex flex-wrap items-center gap-2">
              <span className="font-semibold text-ink">{o.skill}</span>
              {o.versionNo === null ? <Badge tone="warning">{t('overrides.noRows')}</Badge> : <Badge tone="neutral">{`${t('edit.version', { n: o.versionNo })} · ${o.status ? t(`status.${o.status}`) : ''}`}</Badge>}
              <Link to={`/cms/score-tables/${o.tableId}?test=${testId}`} className="text-sm text-primary underline" aria-label={t('overrides.open', { skill: o.skill })}>
                {o.versionNo === null ? t('overrides.write') : t('overrides.edit')}
              </Link>
            </li>
          ))}
        </ul>
      )}
      {admin && (
        <div className="flex flex-wrap items-end gap-3">
          <Select label={t('overrides.skill')} placeholder={t('overrides.skillPlaceholder')} value={skill} onValueChange={setSkill} options={choices.map((s) => ({ value: s, label: s }))} className="min-w-56" />
          <Button
            type="button"
            variant="secondary"
            disabled={!skill}
            loading={attach.isPending}
            onClick={() => {
              if (!skill) return;
              setProblem(null);
              attach.mutate(
                { testId, skill },
                {
                  onSuccess: () => setSkill(undefined),
                  onError: (err) => setProblem(scoreProblemText(t, err)),
                },
              );
            }}
          >
            {t('overrides.attach')}
          </Button>
        </div>
      )}
      {problem && <FormAlert>{problem}</FormAlert>}
    </section>
  );
}
