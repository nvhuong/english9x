import type { InteractionKind, Json, JsonObject } from './types';

/** The interaction kind of each catalog question type (the exam DTO carries the type code only; this mirrors the V2026111000 catalog seed). */
const BY_TYPE: Record<string, InteractionKind> = {
  TOEIC_P1_PHOTO: 'MCQ_SINGLE', TOEIC_P2_QUESTION_RESPONSE: 'MCQ_SINGLE', TOEIC_P3_CONVERSATION: 'MCQ_SINGLE', TOEIC_P4_TALK: 'MCQ_SINGLE',
  TOEIC_P5_INCOMPLETE_SENTENCE: 'MCQ_SINGLE', TOEIC_P6_TEXT_COMPLETION: 'GAP_SELECT', TOEIC_P7_SINGLE_PASSAGE: 'MCQ_SINGLE', TOEIC_P7_MULTIPLE_PASSAGES: 'MCQ_SINGLE',
  IELTS_L_MULTIPLE_CHOICE: 'MCQ_MULTI', IELTS_L_MATCHING: 'MATCHING', IELTS_L_PLAN_MAP_DIAGRAM_LABELLING: 'LABEL_DIAGRAM',
  IELTS_L_FORM_NOTE_TABLE_FLOW_CHART_SUMMARY_COMPLETION: 'GAP_TYPED', IELTS_L_SENTENCE_COMPLETION: 'GAP_TYPED', IELTS_L_SHORT_ANSWER: 'SHORT_ANSWER',
  IELTS_R_MULTIPLE_CHOICE: 'MCQ_MULTI', IELTS_R_IDENTIFYING_INFORMATION: 'TRUE_FALSE_NG', IELTS_R_IDENTIFYING_WRITERS_VIEWS: 'YES_NO_NG',
  IELTS_R_MATCHING_INFORMATION: 'MATCHING', IELTS_R_MATCHING_HEADINGS: 'MATCHING', IELTS_R_MATCHING_FEATURES: 'MATCHING', IELTS_R_MATCHING_SENTENCE_ENDINGS: 'MATCHING',
  IELTS_R_SENTENCE_COMPLETION: 'GAP_TYPED', IELTS_R_SUMMARY_NOTE_TABLE_FLOW_CHART_COMPLETION: 'GAP_TYPED', IELTS_R_DIAGRAM_LABEL_COMPLETION: 'LABEL_DIAGRAM',
  IELTS_R_SHORT_ANSWER: 'SHORT_ANSWER', IELTS_W_AC_TASK1: 'ESSAY', IELTS_W_GT_TASK1: 'ESSAY', IELTS_W_TASK2: 'ESSAY',
  IELTS_S_PART1: 'SPEAKING_RESPONSE', IELTS_S_PART2: 'SPEAKING_RESPONSE', IELTS_S_PART3: 'SPEAKING_RESPONSE',
};

/** Known code, else guessed from the shape of the content (a type added to the catalog later still renders). */
export function kindOfQuestion(typeCode: string, content: JsonObject): InteractionKind {
  const known = BY_TYPE[typeCode];
  if (known) return known;
  if (typeCode.includes('_S_')) return 'SPEAKING_RESPONSE';
  if ('prompt' in content) return 'ESSAY';
  if ('items' in content && 'choices' in content) return 'MATCHING';
  if ('statement' in content) return typeCode.includes('WRITERS') ? 'YES_NO_NG' : 'TRUE_FALSE_NG';
  if (typeof content.text === 'string' && content.text.includes('[[')) return 'GAP_TYPED';
  if (Array.isArray(content.options)) return typeof content.selectCount === 'number' ? 'MCQ_MULTI' : 'MCQ_SINGLE';
  return 'SHORT_ANSWER';
}

export const text = (v: Json | undefined): string => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '');
export const objects = (v: Json | undefined): JsonObject[] => (Array.isArray(v) ? (v.filter((x) => x && typeof x === 'object' && !Array.isArray(x)) as JsonObject[]) : []);
export const stringsOf = (v: Json | undefined): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);
export interface Option { key: string; text: string }
export const optionsOf = (v: Json | undefined): Option[] => objects(v).map((o, i) => ({ key: text(o.key) || String(i + 1), text: text(o.text) }));

/** Gap numbers of a `... [[1]] ... [[2]]` text, in order. */
export const gapsOf = (source: string): string[] => [...source.matchAll(/\[\[(\d+)]]/g)].map((m) => m[1]!);

export function isAnswered(value: Json | undefined): boolean {
  if (value === undefined || value === null) return false;
  if (typeof value === 'string') return value.trim() !== '';
  if (Array.isArray(value)) return value.some((v) => typeof v === 'string' && v.trim() !== '');
  return true;
}

export function countWords(s: string): number {
  const t = s.trim();
  return t === '' ? 0 : t.split(/\s+/).length;
}
