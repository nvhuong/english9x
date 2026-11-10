import type { SkillDraft, SkillVersion } from './types';

export interface CriterionRow {
  key: string;
  code: string;
  name: string;
  min: string;
  max: string;
  step: string;
}

export interface SampleRow {
  key: string;
  band: string;
  text: string;
  mediaId: string;
  commentary: string;
}

/** What the form holds: text as typed, never a judged value. */
export interface SkillModel {
  rubric: string;
  criteria: CriterionRow[];
  promptTemplate: string;
  samples: SampleRow[];
  /** The JSON Schema of the AI's answer, as text. */
  resultSchema: string;
}

let counter = 0;
export const newKey = () => `k${++counter}`;

export const DEFAULT_TEMPLATE = `You are an experienced examiner. Grade the candidate's answer against the rubric.

Rubric:
{{rubric}}

Scored examples:
{{samples}}

Task given to the candidate:
{{task}}

Candidate's answer:
{{response}}
`;

export const emptyCriterion = (): CriterionRow => ({ key: newKey(), code: '', name: '', min: '0', max: '9', step: '0.5' });
export const emptySample = (): SampleRow => ({ key: newKey(), band: '', text: '', mediaId: '', commentary: '' });

export const emptyModel = (): SkillModel => ({ rubric: '', criteria: [emptyCriterion()], promptTemplate: DEFAULT_TEMPLATE, samples: [], resultSchema: '' });

export const modelOf = (v: SkillVersion): SkillModel => ({
  rubric: v.rubric,
  criteria: v.criteria.map((c) => ({ key: newKey(), code: c.code, name: c.name, min: String(c.min), max: String(c.max), step: String(c.step) })),
  promptTemplate: v.promptTemplate,
  samples: v.samples.map((s) => ({ key: newKey(), band: String(s.band), text: s.text ?? '', mediaId: s.mediaId ?? '', commentary: s.commentary ?? '' })),
  resultSchema: JSON.stringify(v.resultSchema, null, 2),
});

const num = (s: string): number | undefined => (s.trim() === '' ? undefined : Number(s));

/** @throws SyntaxError when the result schema text is not JSON (the only thing the form judges: it cannot send what it cannot read) */
export function draftOf(questionTypeCode: string, m: SkillModel): SkillDraft {
  return {
    questionTypeCode,
    rubric: m.rubric,
    criteria: m.criteria.map((c) => ({ code: c.code.trim(), name: c.name, min: num(c.min), max: num(c.max), step: num(c.step) })),
    promptTemplate: m.promptTemplate,
    samples: m.samples.map((s) => ({
      band: num(s.band),
      ...(s.text.trim() ? { text: s.text } : {}),
      ...(s.mediaId.trim() ? { mediaId: s.mediaId.trim() } : {}),
      ...(s.commentary.trim() ? { commentary: s.commentary } : {}),
    })),
    resultSchema: JSON.parse(m.resultSchema.trim() === '' ? '{}' : m.resultSchema) as unknown,
  };
}

/** A starting schema for the AI's answer: a number inside the scale of each criterion, and written feedback. */
export function schemaOf(criteria: CriterionRow[]): string {
  const properties: Record<string, unknown> = {};
  for (const c of criteria) {
    const code = c.code.trim();
    if (code) properties[code] = { type: 'number', minimum: num(c.min) ?? 0, maximum: num(c.max) ?? 9 };
  }
  properties.feedback = { type: 'string' };
  return JSON.stringify({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false }, null, 2);
}
