import type { Json } from './types';

/** What the learner wrote, as a short text: a string, a list of strings (one per blank), or nothing. */
export function answerText(answer: Json | null | undefined): string {
  if (answer === null || answer === undefined) return '';
  if (typeof answer === 'string') return answer.trim();
  if (typeof answer === 'number' || typeof answer === 'boolean') return String(answer);
  if (Array.isArray(answer)) return answer.map(answerText).filter((s) => s !== '').join(', ');
  const values = (answer as { values?: Json }).values;
  return values === undefined ? '' : answerText(values);
}

/** The accepted answers of a key ({@code [[variants of blank 1], [variants of blank 2]]}): one entry per blank, its variants joined. */
export function keySlots(key: Json | null | undefined, alternatives: string): string[] {
  if (!Array.isArray(key)) return [];
  return key.map((slot) => (Array.isArray(slot) ? slot.map(answerText).filter((s) => s !== '').join(` ${alternatives} `) : answerText(slot)));
}

/** `{code: score}` of a criteria object, in the order given; anything else is nothing. */
export function criteriaRows(criteria: Json | null | undefined): Array<[string, string]> {
  if (!criteria || typeof criteria !== 'object' || Array.isArray(criteria)) return [];
  return Object.entries(criteria).flatMap(([code, v]): Array<[string, string]> => {
    if (typeof v === 'number' || typeof v === 'string') return [[code, String(v)]];
    if (v && typeof v === 'object' && !Array.isArray(v) && typeof (v as { score?: Json }).score === 'number') return [[code, String((v as { score: number }).score)]];
    return [];
  });
}

/** The free-text feedback of an AI result: the first of the usual fields that holds text. */
export function feedbackText(result: Json | null | undefined): string {
  if (!result || typeof result !== 'object' || Array.isArray(result)) return '';
  for (const key of ['feedback', 'comment', 'summary', 'comments']) {
    const v = (result as Record<string, Json>)[key];
    if (typeof v === 'string' && v.trim() !== '') return v;
  }
  return '';
}

export function percent(rate: number): number {
  return Math.round(rate * 100);
}
