import type { ScoreRow, ScoreTableDraft } from './types';

/**
 * The editor keeps the table as the text the author types or pastes, one row per line: the number of correct answers, then the scaled score
 * (`12  5.5`, `12, 5.5` or `12;5.5`). A line with one number is the scaled score of the next raw value (a column pasted from a sheet). Nothing here
 * judges the table: what is sent is what was typed, and the server's reasons (a gap in the raw values, a score that falls) come back with the line.
 */
export function parseRows(text: string): Array<{ raw: number | null; scaled: number | null }> {
  const out: Array<{ raw: number | null; scaled: number | null }> = [];
  for (const line of text.split(/\r?\n/)) {
    const parts = line.trim().split(/[\s,;]+/).filter(Boolean);
    if (parts.length === 0) continue;
    const number = (s: string | undefined) => (s === undefined || s === '' || Number.isNaN(Number(s)) ? null : Number(s));
    out.push(parts.length === 1 ? { raw: out.length, scaled: number(parts[0]) } : { raw: number(parts[0]), scaled: number(parts[1]) });
  }
  return out;
}

export function textOf(rows: ScoreRow[]): string {
  return rows.map((r) => `${r.raw}\t${r.scaled}`).join('\n');
}

export const draftOf = (examCode: string, skill: string, text: string): ScoreTableDraft => ({ examCode, skill, scope: 'EXAM', rows: parseRows(text) });
