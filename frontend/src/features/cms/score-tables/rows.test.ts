import { describe, expect, it } from 'vitest';
import { draftOf, parseRows, textOf } from './rows';

describe('parseRows', () => {
  it('reads "raw scaled" lines separated by space, tab, comma or semicolon', () => {
    expect(parseRows('0 3\n1\t3.5\n2,4\r\n3;4.5\n')).toEqual([
      { raw: 0, scaled: 3 },
      { raw: 1, scaled: 3.5 },
      { raw: 2, scaled: 4 },
      { raw: 3, scaled: 4.5 },
    ]);
  });

  it('takes a line with one number as the score of the next raw value (a column pasted from a sheet)', () => {
    expect(parseRows('3\n3.5\n4')).toEqual([
      { raw: 0, scaled: 3 },
      { raw: 1, scaled: 3.5 },
      { raw: 2, scaled: 4 },
    ]);
  });

  it('judges nothing: a gap in the raw values and a word instead of a number are sent as typed, for the server to explain', () => {
    expect(parseRows('0 1\n2 2\n3 x')).toEqual([
      { raw: 0, scaled: 1 },
      { raw: 2, scaled: 2 },
      { raw: 3, scaled: null },
    ]);
  });

  it('round-trips the rows the server holds, and the draft is the EXAM default of the exam skill', () => {
    const rows = [
      { raw: 0, scaled: 0 },
      { raw: 1, scaled: 4.5 },
    ];
    expect(parseRows(textOf(rows))).toEqual(rows);
    expect(draftOf('IELTS', 'LISTENING', textOf(rows))).toEqual({ examCode: 'IELTS', skill: 'LISTENING', scope: 'EXAM', rows });
  });
});
