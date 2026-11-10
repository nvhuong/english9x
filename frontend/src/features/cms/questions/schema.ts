import type { ErrorSchema, RJSFSchema, UiSchema } from '@rjsf/utils';
import type { TFunction } from 'i18next';
import type { Json, JsonObject, QuestionDraft, QuestionTypeForm, QuestionVersion } from './types';

/** What the form edits: the two documents the server validates, and the two plain fields next to them. */
export interface FormModel {
  content: JsonObject;
  answerKey: JsonObject;
  explanation: string;
  points: number;
}

export const emptyModel = (): FormModel => ({ content: {}, answerKey: {}, explanation: '', points: 1 });

export const modelOf = (q: QuestionVersion): FormModel => ({
  content: q.content,
  answerKey: q.answerKey,
  explanation: q.explanation ?? '',
  points: q.points ?? 1,
});

export const draftOf = (typeCode: string, m: FormModel): QuestionDraft => ({
  typeCode,
  content: m.content,
  answerKey: m.answerKey,
  ...(m.explanation.trim() === '' ? {} : { explanation: m.explanation }),
  points: m.points,
});

/** The schemas are 2020-12; the form's validator is not asked to check anything (the server does), so the dialect marker only gets in the way. */
function clean(node: Json): Json {
  if (Array.isArray(node)) return node.map(clean);
  if (node && typeof node === 'object') {
    const out: JsonObject = {};
    for (const [k, v] of Object.entries(node)) if (k !== '$schema') out[k] = clean(v as Json);
    return out;
  }
  return node;
}

/**
 * One schema for the whole form: the content and the answer key of the type, next to the explanation and the points. Every constraint is the
 * server's: the form shows the shape; {@code noValidate} on the form leaves the checking, and its words, to the one place that knows all the rules.
 */
export function formSchema(type: QuestionTypeForm, t: TFunction<'questions'>): RJSFSchema {
  return {
    type: 'object',
    properties: {
      content: { ...(clean(type.contentSchema) as RJSFSchema), title: t('sections.content') },
      answerKey: { ...(clean(type.answerKeySchema) as RJSFSchema), title: t('sections.answerKey') },
      explanation: { type: 'string', title: t('fields.explanation') },
      points: { type: 'number', title: t('fields.points') },
    },
  };
}

const MEDIA_KINDS: Array<[string, 'AUDIO' | 'IMAGE' | 'VIDEO']> = [
  ['audio', 'AUDIO'],
  ['image', 'IMAGE'],
  ['video', 'VIDEO'],
];

/** The kind of file a field holds, from its name: audioMediaId an audio file, imageMediaId an image (the server checks the same rule). */
export function mediaKindOf(field: string): 'AUDIO' | 'IMAGE' | 'VIDEO' | undefined {
  if (!field.endsWith('MediaId')) return undefined;
  return MEDIA_KINDS.find(([prefix]) => field.toLowerCase().startsWith(prefix))?.[1];
}

type Schema = { type?: unknown; properties?: Record<string, Schema>; items?: Schema; maxLength?: number };

function uiFor(name: string, schema: Schema, t: TFunction<'questions'>, exists: (key: string) => boolean): UiSchema {
  const ui: UiSchema = {};
  if (name && exists(`fields.${name}`)) ui['ui:title'] = t(`fields.${name}`);
  if (name.endsWith('MediaId') && schema.type === 'string') {
    ui['ui:widget'] = 'mediaId';
    ui['ui:options'] = { kind: mediaKindOf(name) ?? 'AUDIO' };
  } else if (schema.type === 'string' && (schema.maxLength ?? 0) > 300) {
    ui['ui:widget'] = 'textarea';
    ui['ui:options'] = { rows: 6 };
  }
  if (schema.properties) {
    for (const [child, sub] of Object.entries(schema.properties)) ui[child] = uiFor(child, sub, t, exists);
  }
  if (schema.items) ui.items = uiFor('', schema.items, t, exists);
  return ui;
}

export function formUiSchema(type: QuestionTypeForm, t: TFunction<'questions'>, exists: (key: string) => boolean): UiSchema {
  return {
    'ui:order': ['content', 'answerKey', 'explanation', 'points'],
    content: { ...uiFor('', type.contentSchema as Schema, t, exists), 'ui:title': t('sections.content') },
    answerKey: { ...uiFor('', type.answerKeySchema as Schema, t, exists), 'ui:title': t('sections.answerKey') },
    explanation: { 'ui:widget': 'textarea', 'ui:options': { rows: 4 } },
  };
}

/** `content.options[2].key` -> ['content', 'options', 2, 'key'] */
export function pathOf(field: string): Array<string | number> {
  const out: Array<string | number> = [];
  for (const m of field.matchAll(/([^.[\]]+)|\[(\d+)]/g)) out.push(m[2] !== undefined ? Number(m[2]) : m[1]!);
  return out;
}

/** The server's errors[] as the form's error schema, so each message shows next to its field. Paths the form has no field for stay in the summary. */
export function errorSchemaOf(errors: Array<{ field?: string | null; message?: string }>): ErrorSchema {
  const root: ErrorSchema = {};
  for (const e of errors) {
    if (!e.field) continue;
    let node = root as Record<string | number, ErrorSchema>;
    for (const step of pathOf(e.field)) {
      node[step] = node[step] ?? {};
      node = node[step] as Record<string | number, ErrorSchema>;
    }
    const at = node as unknown as { __errors?: string[] };
    (at.__errors ??= []).push(e.message ?? '');
  }
  return root;
}
