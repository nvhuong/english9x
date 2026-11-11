import { describe, expect, it } from 'vitest';
import { addChapter, addLesson, draftOf, lessonOf, moveLesson, removeChapter, requestOf } from './model';
import type { CourseDraft, CourseVersion } from './types';

const VERSION = {
  examCode: 'TOEIC',
  code: 'AB12CD',
  title: 'Part 5 in a month',
  description: 'd',
  authorDisplay: 'Ms Lan',
  unlockRule: 'STRICT',
  chapters: [
    {
      title: 'Chapter 1',
      passThreshold: 0.75,
      lessons: [
        { id: 'l1', kind: 'LESSON', refId: 'v1', title: 'Verbs', maxAttempts: null },
        { id: 'l2', kind: 'CHAPTER_TEST', refId: 'v2', title: 'Test 1', maxAttempts: 3 },
      ],
    },
  ],
} as unknown as CourseVersion;

describe('course builder model', () => {
  it('shows the threshold in percent and sends it back as a fraction', () => {
    const d = draftOf(VERSION);
    expect(d.chapters[0]!.passPercent).toBe('75');
    expect(requestOf(d, false)).toEqual({
      title: 'Part 5 in a month',
      description: 'd',
      authorDisplay: 'Ms Lan',
      unlockRule: 'STRICT',
      chapters: [
        {
          title: 'Chapter 1',
          passThreshold: 0.75,
          lessons: [
            { kind: 'LESSON', refId: 'v1', maxAttempts: null },
            { kind: 'CHAPTER_TEST', refId: 'v2', maxAttempts: 3 },
          ],
        },
      ],
    });
  });

  it('sends the exam and the code, in capitals, only when the course is new', () => {
    const d: CourseDraft = { ...draftOf(VERSION), code: 'ab12cd' };
    expect(requestOf(d, true)).toMatchObject({ examCode: 'TOEIC', code: 'AB12CD' });
    expect(requestOf(d, false)).not.toHaveProperty('code');
  });

  it('moves a lesson inside its chapter and stops at the ends', () => {
    const d = draftOf(VERSION);
    expect(moveLesson(d, 0, 0, 1).chapters[0]!.lessons.map((l) => l.refId)).toEqual(['v2', 'v1']);
    expect(moveLesson(d, 0, 0, -1)).toBe(d);
  });

  it('adds and removes chapters and lessons; a test is a chapter test unless said otherwise', () => {
    let d = addChapter(draftOf(VERSION));
    expect(d.chapters).toHaveLength(2);
    d = addLesson(d, 1, lessonOf({ refId: 't', title: 'Mock', kind: 'TEST', status: 'PUBLISHED' }, 'MOCK_TEST'));
    expect(d.chapters[1]!.lessons[0]!.kind).toBe('MOCK_TEST');
    expect(lessonOf({ refId: 't', title: 'T', kind: 'TEST', status: 'PUBLISHED' }).kind).toBe('CHAPTER_TEST');
    expect(removeChapter(d, 1).chapters).toHaveLength(1);
  });
});
