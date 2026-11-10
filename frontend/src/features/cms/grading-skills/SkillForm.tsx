import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input } from '../../../design';
import { TextArea } from '../lesson-editor/fields';
import { emptyCriterion, emptySample, schemaOf, type CriterionRow, type SampleRow, type SkillModel } from './model';
import { fieldIssue, rowIssue, type FieldIssue } from './problems';

export interface SkillFormProps {
  model: SkillModel;
  onChange: (m: SkillModel) => void;
  onSave: (m: SkillModel) => void;
  issues: FieldIssue[];
  locked?: boolean;
  saving?: boolean;
  saveLabel: string;
  actions?: ReactNode;
}

/**
 * The grading skill's form: rubric, criteria, prompt template, scored samples and the JSON Schema of the AI's answer. Nothing is validated here
 * except that the schema is JSON: the server's answer, a JSON path with a reason each, is shown next to the fields it names and as a list.
 */
export function SkillForm({ model, onChange, onSave, issues, locked = false, saving = false, saveLabel, actions }: SkillFormProps) {
  const { t } = useTranslation('skills');
  const id = useId();
  const patchCriterion = (key: string, patch: Partial<CriterionRow>) => onChange({ ...model, criteria: model.criteria.map((c) => (c.key === key ? { ...c, ...patch } : c)) });
  const patchSample = (key: string, patch: Partial<SampleRow>) => onChange({ ...model, samples: model.samples.map((s) => (s.key === key ? { ...s, ...patch } : s)) });

  return (
    <form
      className="flex flex-col gap-8"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        onSave(model);
      }}
    >
      <TextArea label={t('field.rubric')} hint={t('field.rubricHint')} value={model.rubric} onValue={(v) => onChange({ ...model, rubric: v })} disabled={locked} rows={8}
        error={fieldIssue(issues, 'rubric')} className="max-w-3xl" />

      <section aria-labelledby={`${id}-criteria`} className="flex flex-col gap-3">
        <h2 id={`${id}-criteria`} className="font-serif text-xl font-bold text-ink">
          {t('criteria.title')}
        </h2>
        <p className="max-w-prose text-sm text-muted">{t('criteria.hint')}</p>
        {fieldIssue(issues, 'criteria') && issues.some((i) => i.field === 'criteria') && <p className="text-sm font-medium text-danger">{fieldIssue(issues, 'criteria')}</p>}
        <ol className="flex flex-col gap-3">
          {model.criteria.map((c, i) => (
            <li key={c.key}>
              <fieldset disabled={locked} className="flex flex-col gap-2 rounded-lg border border-line bg-surface p-4">
                <legend className="px-1 text-sm font-bold text-ink">{t('criteria.label', { n: i + 1 })}</legend>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
                  <Input label={t('criteria.code')} value={c.code} onChange={(e) => patchCriterion(c.key, { code: e.target.value })} />
                  <Input label={t('criteria.name')} value={c.name} onChange={(e) => patchCriterion(c.key, { name: e.target.value })} />
                  <Input label={t('criteria.min')} inputMode="decimal" value={c.min} onChange={(e) => patchCriterion(c.key, { min: e.target.value })} />
                  <Input label={t('criteria.max')} inputMode="decimal" value={c.max} onChange={(e) => patchCriterion(c.key, { max: e.target.value })} />
                  <Input label={t('criteria.step')} inputMode="decimal" value={c.step} onChange={(e) => patchCriterion(c.key, { step: e.target.value })} />
                </div>
                {rowIssue(issues, 'criteria', i) && <p className="text-sm font-medium text-danger">{rowIssue(issues, 'criteria', i)}</p>}
                {!locked && (
                  <div>
                    <Button variant="danger" size="sm" onClick={() => onChange({ ...model, criteria: model.criteria.filter((x) => x.key !== c.key) })}>
                      {t('criteria.remove')}
                    </Button>
                  </div>
                )}
              </fieldset>
            </li>
          ))}
        </ol>
        {!locked && (
          <div>
            <Button variant="secondary" onClick={() => onChange({ ...model, criteria: [...model.criteria, emptyCriterion()] })}>
              {t('criteria.add')}
            </Button>
          </div>
        )}
      </section>

      <TextArea label={t('field.promptTemplate')} hint={t('field.promptTemplateHint')} value={model.promptTemplate} onValue={(v) => onChange({ ...model, promptTemplate: v })}
        disabled={locked} rows={12} spellCheck={false} error={fieldIssue(issues, 'promptTemplate')} className="max-w-3xl font-mono" />

      <section aria-labelledby={`${id}-samples`} className="flex flex-col gap-3">
        <h2 id={`${id}-samples`} className="font-serif text-xl font-bold text-ink">
          {t('samples.title')}
        </h2>
        <p className="max-w-prose text-sm text-muted">{t('samples.hint')}</p>
        {model.samples.length === 0 && <p className="text-muted">{t('samples.empty')}</p>}
        <ol className="flex flex-col gap-3">
          {model.samples.map((s, i) => (
            <li key={s.key}>
              <fieldset disabled={locked} className="flex flex-col gap-2 rounded-lg border border-line bg-surface p-4">
                <legend className="px-1 text-sm font-bold text-ink">{t('samples.label', { n: i + 1 })}</legend>
                <Input label={t('samples.band')} inputMode="decimal" value={s.band} onChange={(e) => patchSample(s.key, { band: e.target.value })} className="max-w-40" />
                <TextArea label={t('samples.text')} value={s.text} onValue={(v) => patchSample(s.key, { text: v })} rows={4} />
                <Input label={t('samples.mediaId')} value={s.mediaId} onChange={(e) => patchSample(s.key, { mediaId: e.target.value })} className="max-w-xl" />
                <TextArea label={t('samples.commentary')} value={s.commentary} onValue={(v) => patchSample(s.key, { commentary: v })} rows={3} />
                {rowIssue(issues, 'samples', i) && <p className="text-sm font-medium text-danger">{rowIssue(issues, 'samples', i)}</p>}
                {!locked && (
                  <div>
                    <Button variant="danger" size="sm" onClick={() => onChange({ ...model, samples: model.samples.filter((x) => x.key !== s.key) })}>
                      {t('samples.remove')}
                    </Button>
                  </div>
                )}
              </fieldset>
            </li>
          ))}
        </ol>
        {!locked && (
          <div>
            <Button variant="secondary" onClick={() => onChange({ ...model, samples: [...model.samples, emptySample()] })}>
              {t('samples.add')}
            </Button>
          </div>
        )}
      </section>

      <div className="flex flex-col gap-2">
        <TextArea label={t('field.resultSchema')} hint={t('field.resultSchemaHint')} value={model.resultSchema} onValue={(v) => onChange({ ...model, resultSchema: v })}
          disabled={locked} rows={14} spellCheck={false} error={fieldIssue(issues, 'resultSchema')} className="max-w-3xl font-mono" />
        {!locked && (
          <div>
            <Button variant="secondary" size="sm" onClick={() => onChange({ ...model, resultSchema: schemaOf(model.criteria) })}>
              {t('field.schemaFromCriteria')}
            </Button>
          </div>
        )}
      </div>

      {issues.length > 0 && (
        <div role="alert" className="rounded-md border border-danger bg-danger-bg px-3 py-2 text-sm text-ink">
          <p className="font-semibold text-danger">{t('errors.invalid')}</p>
          <ul className="mt-1 list-disc pl-5">
            {issues.map((i, n) => (
              <li key={n}>
                <code className="text-xs">{i.field}</code>: {i.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {!locked && (
        <div className="flex flex-wrap gap-3">
          <Button type="submit" loading={saving}>
            {saveLabel}
          </Button>
          {actions}
        </div>
      )}
      {locked && actions}
    </form>
  );
}
