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

/**
 * The criteria scores of an AI result. The grading skill's schema decides the shape: a {@code criteria} object, a list of {@code {name, band}}, or (the usual one) one
 * numeric field per criterion beside the free text.
 */
export function aiCriteriaRows(result: Json | null | undefined): Array<[string, string]> {
  if (!result || typeof result !== 'object' || Array.isArray(result)) return [];
  const obj = result as Record<string, Json>;
  const c = obj.criteria;
  if (Array.isArray(c)) {
    return c.flatMap((x): Array<[string, string]> => {
      const o = x && typeof x === 'object' && !Array.isArray(x) ? (x as Record<string, Json>) : null;
      const score = o?.band ?? o?.score;
      return o && typeof o.name === 'string' && typeof score === 'number' ? [[o.name, String(score)]] : [];
    });
  }
  if (c && typeof c === 'object') return criteriaRows(c);
  return Object.entries(obj).flatMap(([k, v]): Array<[string, string]> => (typeof v === 'number' ? [[k, String(v)]] : []));
}

/** What the AI heard in a spoken answer, when the grading skill's schema asks for it. */
export function transcriptText(result: Json | null | undefined): string {
  if (!result || typeof result !== 'object' || Array.isArray(result)) return '';
  const v = (result as Record<string, Json>).transcript;
  return typeof v === 'string' ? v.trim() : '';
}
