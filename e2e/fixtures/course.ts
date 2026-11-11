import { expect } from '@playwright/test';
import { api } from './org';
import { publishedQuestion, type Staffed } from './content';
import { seed } from './seed';

/** Courses through the REAL CMS API and the real review (P3.1): published lessons and tests to hang a course on, and a published course. */

/** A strategy lesson version (with an audio block, so that its file belongs to the course), PUBLISHED. */
export async function publishedLesson(s: Staffed, exam: string, title: string): Promise<string> {
  const made = await api(s.author.token, 'POST', '/api/cms/strategy-lessons', {
    org: s.orgId,
    body: {
      examCode: exam,
      title,
      blocks: [{ type: 'paragraph', text: `SECRET-LESSON-BODY of ${title}` }, { type: 'audio', audioMediaId: s.mediaByExam[exam]!.audioId }],
      questionTypeCodes: [exam === 'TOEIC' ? 'TOEIC_P5_INCOMPLETE_SENTENCE' : 'IELTS_R_SHORT_ANSWER'],
    },
  });
  expect(made.status, made.text).toBe(201);
  await seed.reviewVersion({ type: 'strategy_lesson', versionId: made.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
  return made.body.versionId as string;
}

/** A TOEIC test version (a photo group and a sentence group), PUBLISHED. */
export async function publishedToeicTestVersion(s: Staffed, title: string): Promise<string> {
  const group = async (typeCode: string, groupTitle: string, withAudio: boolean) => {
    const q = await publishedQuestion(s, typeCode);
    const content: Record<string, string> = { title: groupTitle };
    if (withAudio) content.audioMediaId = s.audioId;
    const made = await api(s.author.token, 'POST', '/api/cms/question-groups', { org: s.orgId, body: { examCode: 'TOEIC', content, questionVersionIds: [q.versionId] } });
    expect(made.status, made.text).toBe(201);
    await seed.reviewVersion({ type: 'question_group', versionId: made.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    return made.body.versionId as string;
  };
  const listening = await group('TOEIC_P1_PHOTO', `${title} photos`, true);
  const reading = await group('TOEIC_P5_INCOMPLETE_SENTENCE', `${title} sentences`, false);
  const created = await api(s.author.token, 'POST', '/api/cms/tests', { org: s.orgId, body: { examCode: 'TOEIC', title } });
  expect(created.status, created.text).toBe(201);
  const saved = await api(s.author.token, 'PUT', `/api/cms/tests/${created.body.testId}/structure`, {
    org: s.orgId,
    body: { parts: [{ sectionCode: 'P1', durationSec: 600, groups: [listening] }, { sectionCode: 'P5', durationSec: 600, groups: [reading] }] },
  });
  expect(saved.status, saved.text).toBe(200);
  await seed.reviewVersion({ type: 'test', versionId: saved.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
  return saved.body.versionId as string;
}

export const newCode = () => Math.random().toString(36).slice(2, 8).toUpperCase().padEnd(6, '0');

export interface CourseVersion {
  courseId: string;
  versionId: string;
  code: string;
  status: string;
  chapters: Array<{ title: string; passThreshold: number; lessons: Array<{ id: string; kind: string; refId: string; title: string }> }>;
}

/** A course of one chapter holding a lesson and a chapter test, and a second chapter with a lesson: PUBLISHED through the real review. */
export async function publishedCourse(s: Staffed, rule: 'STRICT' | 'COMPLETION' | 'FREE', lessons: { l1: string; test: string; l2: string }, title = 'E2E course'): Promise<CourseVersion> {
  const made = await api(s.author.token, 'POST', '/api/cms/courses', {
    org: s.orgId,
    body: {
      examCode: 'TOEIC',
      code: newCode(),
      title,
      description: 'What this course teaches.',
      authorDisplay: 'Ms Lan',
      unlockRule: rule,
      chapters: [
        { title: 'Chapter 1', passThreshold: 0.7, lessons: [{ kind: 'LESSON', refId: lessons.l1 }, { kind: 'CHAPTER_TEST', refId: lessons.test }] },
        { title: 'Chapter 2', passThreshold: 0.7, lessons: [{ kind: 'LESSON', refId: lessons.l2 }] },
      ],
    },
  });
  expect(made.status, made.text).toBe(201);
  await seed.reviewVersion({ type: 'course', versionId: made.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
  return made.body as CourseVersion;
}
