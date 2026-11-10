import { describe, expect, it } from 'vitest';
import { addPart, draftOf, moveGroup, removePart, requestOf } from './model';
import type { TestVersion } from './types';

const VERSION = {
  title: 'Mock 1',
  maxResumes: null,
  parts: [
    { position: 1, sectionCode: 'P1', skill: 'LISTENING', durationSec: 2700, questions: 6, groups: [{ groupVersionId: 'g1', groupId: 'a', title: 'Photos', questions: 6 }] },
    { position: 2, sectionCode: 'P5', skill: 'READING', durationSec: 90, questions: 30, groups: [] },
  ],
} as unknown as TestVersion;

describe('test builder model', () => {
  it('shows seconds as minutes and sends them back as seconds', () => {
    const d = draftOf(VERSION);
    expect(d.parts.map((p) => p.minutes)).toEqual(['45', '1.5']);
    expect(requestOf(d)).toEqual({
      title: 'Mock 1',
      maxResumes: null,
      parts: [
        { sectionCode: 'P1', durationSec: 2700, groups: ['g1'] },
        { sectionCode: 'P5', durationSec: 90, groups: [] },
      ],
    });
  });

  it('sends the number of resumes only when one was typed', () => {
    expect(requestOf({ ...draftOf(VERSION), maxResumes: '3' }).maxResumes).toBe(3);
    expect(requestOf({ ...draftOf(VERSION), maxResumes: ' ' }).maxResumes).toBeNull();
  });

  it('adds the next unused section, removes a part, and moves a group', () => {
    let d = draftOf(VERSION);
    d = addPart(d, ['P1', 'P2', 'P5']);
    expect(d.parts.map((p) => p.sectionCode)).toEqual(['P1', 'P5', 'P2']);
    expect(addPart(addPart(d, ['P1', 'P2', 'P5']), ['P1', 'P2', 'P5']).parts).toHaveLength(3);
    expect(removePart(d, 1).parts.map((p) => p.sectionCode)).toEqual(['P1', 'P2']);
    const two = { ...d, parts: [{ ...d.parts[0]!, groups: [{ groupVersionId: 'g1', title: 'a' }, { groupVersionId: 'g2', title: 'b' }] }] };
    expect(moveGroup(two, 0, 0, 1).parts[0]!.groups.map((g) => g.groupVersionId)).toEqual(['g2', 'g1']);
    expect(moveGroup(two, 0, 0, -1)).toBe(two);
  });
});
