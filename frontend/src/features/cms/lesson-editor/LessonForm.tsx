import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input, Select } from '../../../design';
import { BlockEditor } from './BlockEditor';
import { emptyBlock } from './blocks';
import { issuesByBlock, move, newKey, type LessonModel } from './model';
import type { FieldIssue } from './problems';
import { BLOCK_TYPES, type BlockType } from './types';

export interface LessonFormProps {
  examCode: string;
  /** The question types of the lesson's exam the lesson may teach. */
  typeOptions: string[];
  model: LessonModel;
  onChange: (m: LessonModel) => void;
  onSave: (m: LessonModel) => void;
  issues: FieldIssue[];
  locked?: boolean;
  saving?: boolean;
  saveLabel: string;
  actions?: ReactNode;
}

/**
 * The lesson editor's form: title, the question types the lesson teaches, and the ordered blocks (add, edit, move, remove). Nothing is validated here:
 * the server's answer, a JSON path with a reason each, is shown next to the fields it names and as a list under the form.
 */
export function LessonForm({ examCode, typeOptions, model, onChange, onSave, issues, locked = false, saving = false, saveLabel, actions }: LessonFormProps) {
  const { t } = useTranslation('lessons');
  const summaryId = useId();
  const byBlock = issuesByBlock(issues);
  const titleIssue = issues.find((i) => i.field === 'title')?.message;
  const typesIssue = issues.filter((i) => i.field.startsWith('questionTypeCodes')).map((i) => i.message).join(' ');
  const blocksIssue = issues.find((i) => i.field === 'blocks')?.message;
  const add = (type: BlockType) => onChange({ ...model, items: [...model.items, { key: newKey(), block: emptyBlock(type) }] });

  return (
    <form
      className="flex flex-col gap-6"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        onSave(model);
      }}
    >
      <Input label={t('field.lessonTitle')} value={model.title} disabled={locked} required onChange={(e) => onChange({ ...model, title: e.target.value })} error={titleIssue} className="max-w-2xl" />

      <fieldset className="flex flex-col gap-2" disabled={locked} aria-describedby={typesIssue ? `${summaryId}-types` : undefined}>
        <legend className="text-sm font-semibold text-ink">{t('types.legend')}</legend>
        <p className="text-sm text-muted">{t('types.hint')}</p>
        <div className="flex flex-wrap gap-x-6 gap-y-2">
          {typeOptions.map((code) => (
            <label key={code} className="flex items-center gap-2 text-sm text-ink">
              <input
                type="checkbox"
                checked={model.typeCodes.includes(code)}
                onChange={(e) => onChange({ ...model, typeCodes: e.target.checked ? [...model.typeCodes, code] : model.typeCodes.filter((c) => c !== code) })}
              />
              <code className="text-xs">{code}</code>
            </label>
          ))}
        </div>
        {typesIssue && (
          <p id={`${summaryId}-types`} className="text-sm font-medium text-danger">
            {typesIssue}
          </p>
        )}
      </fieldset>

      <section aria-labelledby={`${summaryId}-blocks`} className="flex flex-col gap-4">
        <h2 id={`${summaryId}-blocks`} className="font-serif text-xl font-bold text-ink">
          {t('blocks.title')}
        </h2>
        {model.items.length === 0 && <p className="text-muted">{t('blocks.empty')}</p>}
        {blocksIssue && <p className="text-sm font-medium text-danger">{blocksIssue}</p>}
        <ol className="flex flex-col gap-4">
          {model.items.map((item, index) => (
            <li key={item.key}>
              <section aria-label={t('blocks.label', { n: index + 1, type: t(`blockType.${item.block.type}`) })} className="flex flex-col gap-3 rounded-lg border border-line bg-surface p-4">
                <header className="flex flex-wrap items-center justify-between gap-2">
                  <h3 className="text-sm font-bold text-ink">
                    {index + 1}. {t(`blockType.${item.block.type}`)}
                  </h3>
                  {!locked && (
                    <div className="flex gap-2">
                      <Button variant="secondary" size="sm" disabled={index === 0} onClick={() => onChange({ ...model, items: move(model.items, index, index - 1) })}>
                        {t('blocks.up')}
                      </Button>
                      <Button variant="secondary" size="sm" disabled={index === model.items.length - 1} onClick={() => onChange({ ...model, items: move(model.items, index, index + 1) })}>
                        {t('blocks.down')}
                      </Button>
                      <Button variant="danger" size="sm" onClick={() => onChange({ ...model, items: model.items.filter((x) => x.key !== item.key) })}>
                        {t('blocks.remove')}
                      </Button>
                    </div>
                  )}
                </header>
                <BlockEditor
                  block={item.block}
                  examCode={examCode}
                  locked={locked}
                  errors={byBlock.get(index) ?? {}}
                  onChange={(block) => onChange({ ...model, items: model.items.map((x) => (x.key === item.key ? { ...x, block } : x)) })}
                />
                {byBlock.get(index)?._ && <p className="text-sm font-medium text-danger">{byBlock.get(index)!._}</p>}
              </section>
            </li>
          ))}
        </ol>
        {!locked && (
          <div className="flex flex-wrap items-end gap-3">
            <Select
              label={t('blocks.addLabel')}
              placeholder={t('blocks.addPlaceholder')}
              value=""
              onValueChange={(v) => add(v as BlockType)}
              options={BLOCK_TYPES.map((b) => ({ value: b, label: t(`blockType.${b}`) }))}
              className="min-w-64"
            />
          </div>
        )}
      </section>

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
