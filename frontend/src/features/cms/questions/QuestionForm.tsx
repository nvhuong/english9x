import Form from '@rjsf/core';
import { TranslatableString } from '@rjsf/utils';
import validator from '@rjsf/validator-ajv8';
import { useId, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import i18n from '../../../i18n';
import { Button } from '../../../design';
import { FormAlert } from '../../auth/components';
import { MediaIdWidget } from './MediaIdWidget';
import { errorSchemaOf, formSchema, formUiSchema, type FormModel } from './schema';
import type { FieldIssue } from './problems';
import type { JsonObject, QuestionTypeForm } from './types';
import './questions.css';

export interface QuestionFormProps {
  type: QuestionTypeForm;
  model: FormModel;
  onChange: (model: FormModel) => void;
  /** Called with the model the author means to save (from the form, or parsed from the JSON text). */
  onSave: (model: FormModel) => void;
  issues: FieldIssue[];
  /** Read-only: the version is under review, retired, or somebody else's draft. */
  locked?: boolean;
  saving?: boolean;
  saveLabel: string;
  /** More buttons next to Save (submit for review). */
  actions?: ReactNode;
}

const widgets = { mediaId: MediaIdWidget };

/**
 * The question editor's form, built from the schemas of the type (react-jsonschema-form), plus the same two documents as JSON text for the author who
 * prefers to paste. Neither validates here: the server holds every rule, and its answer, a list of JSON paths with a reason each, is shown both next to
 * the fields it names and as a list under the form. Nothing is hidden because the screen thinks it is wrong.
 */
export function QuestionForm({ type, model, onChange, onSave, issues, locked = false, saving = false, saveLabel, actions }: QuestionFormProps) {
  const { t } = useTranslation('questions');
  const summaryId = useId();
  const [mode, setMode] = useState<'form' | 'json'>('form');
  const [text, setText] = useState('');
  const [textProblem, setTextProblem] = useState<string | null>(null);

  // the form library's own words (buttons of the lists, empty-list hint): ours, in the language of the page
  const translateString = useMemo(
    () => (key: TranslatableString, params?: string[]) => {
      switch (key) {
        case TranslatableString.AddItemButton:
        case TranslatableString.AddButton:
          return t('rjsf.add');
        case TranslatableString.RemoveButton:
          return t('rjsf.remove');
        case TranslatableString.MoveUpButton:
          return t('rjsf.moveUp');
        case TranslatableString.MoveDownButton:
          return t('rjsf.moveDown');
        case TranslatableString.CopyButton:
          return t('rjsf.copy');
        case TranslatableString.EmptyArray:
          return t('rjsf.emptyList');
        default:
          return key.replace(/%(\d)/g, (_, n: string) => params?.[Number(n) - 1] ?? '');
      }
    },
    [t],
  );
  const schema = useMemo(() => formSchema(type, t), [type, t]);
  const uiSchema = useMemo(() => formUiSchema(type, t, (key) => i18n.exists(`questions:${key}`)), [type, t]);
  const extraErrors = useMemo(() => (issues.length ? errorSchemaOf(issues) : undefined), [issues]);

  const toJson = () => {
    setText(JSON.stringify({ content: model.content, answerKey: model.answerKey, explanation: model.explanation, points: model.points }, null, 2));
    setTextProblem(null);
    setMode('json');
  };

  const fromJson = (): FormModel | null => {
    try {
      const parsed = JSON.parse(text) as { content?: unknown; answerKey?: unknown; explanation?: unknown; points?: unknown };
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object');
      return {
        content: (parsed.content ?? {}) as JsonObject,
        answerKey: (parsed.answerKey ?? {}) as JsonObject,
        explanation: typeof parsed.explanation === 'string' ? parsed.explanation : '',
        points: typeof parsed.points === 'number' ? parsed.points : model.points,
      };
    } catch {
      setTextProblem(t('json.invalid'));
      return null;
    }
  };

  const backToForm = () => {
    const next = fromJson();
    if (!next) return;
    onChange(next);
    setMode('form');
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted">
          <code>{type.code}</code> · {type.examCode} · {type.section}
        </p>
        {!locked && (
          <Button variant="secondary" size="sm" onClick={mode === 'form' ? toJson : backToForm}>
            {mode === 'form' ? t('json.show') : t('json.hide')}
          </Button>
        )}
      </div>

      {issues.length > 0 && (
        <div role="alert" id={summaryId} className="rounded-md border border-danger bg-danger-bg px-3 py-2 text-sm text-danger">
          <p className="font-semibold">{t('errors.invalid')}</p>
          <ul className="mt-1 list-disc pl-5">
            {issues.map((i, n) => (
              <li key={`${i.field}-${n}`}>
                <code className="font-mono">{i.field || t('errors.general')}</code>: {i.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {mode === 'form' ? (
        <div className="e9x-rjsf">
          <Form
            schema={schema}
            uiSchema={uiSchema}
            formData={model}
            validator={validator}
            widgets={widgets}
            formContext={{ examCode: type.examCode }}
            extraErrors={extraErrors}
            noValidate
            noHtml5Validate
            showErrorList={false}
            translateString={translateString}
            disabled={locked}
            onChange={(e) => onChange(e.formData as FormModel)}
            onSubmit={(e) => onSave(e.formData as FormModel)}
          >
            <div className="mt-6 flex flex-wrap gap-2">
              {!locked && (
                <Button type="submit" loading={saving} className="e9x-reset">
                  {saveLabel}
                </Button>
              )}
              {actions}
            </div>
          </Form>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          <label htmlFor={`${summaryId}-json`} className="text-sm font-semibold text-ink">
            {t('json.label')}
          </label>
          <textarea
            id={`${summaryId}-json`}
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              setTextProblem(null);
            }}
            spellCheck={false}
            rows={18}
            aria-describedby={`${summaryId}-json-help`}
            className="w-full rounded-md border border-edge bg-surface p-3 font-mono text-sm text-ink"
          />
          <p id={`${summaryId}-json-help`} className="text-sm text-muted">
            {t('json.help')}
          </p>
          {textProblem && <FormAlert>{textProblem}</FormAlert>}
          <div className="flex flex-wrap gap-2">
            <Button
              loading={saving}
              onClick={() => {
                const next = fromJson();
                if (next) {
                  onChange(next);
                  onSave(next);
                }
              }}
            >
              {saveLabel}
            </Button>
            {actions}
          </div>
        </div>
      )}
    </div>
  );
}
