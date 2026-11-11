import type { ChapterDraft, CourseDraft, CourseVersion, LessonDraft, LessonKind, RefChoice } from './types';

export const EMPTY_DRAFT: CourseDraft = { examCode: '', code: '', title: '', description: '', authorDisplay: '', unlockRule: 'COMPLETION', chapters: [] };

const percent = (fraction: number) => String(Math.round(fraction * 1000) / 10);

/** The editor's state for a version the server sent. */
export function draftOf(v: CourseVersion): CourseDraft {
  return {
    examCode: v.examCode,
    code: v.code,
    title: v.title,
    description: v.description,
    authorDisplay: v.authorDisplay,
    unlockRule: v.unlockRule,
    chapters: v.chapters.map((c) => ({
      title: c.title,
      passPercent: percent(c.passThreshold),
      lessons: c.lessons.map((l) => ({ kind: l.kind, refId: l.refId, title: l.title, maxAttempts: l.maxAttempts === null ? '' : String(l.maxAttempts) })),
    })),
  };
}

/** The body of POST / PUT /api/cms/courses. Nothing is checked here: the server says what is wrong, by path. On an update the exam and code are not sent. */
export function requestOf(d: CourseDraft, create: boolean) {
  return {
    ...(create ? { examCode: d.examCode, code: d.code.trim().toUpperCase() } : {}),
    title: d.title.trim(),
    description: d.description.trim(),
    authorDisplay: d.authorDisplay.trim(),
    unlockRule: d.unlockRule,
    chapters: d.chapters.map((c) => ({
      title: c.title.trim(),
      passThreshold: c.passPercent.trim() === '' ? null : Number(c.passPercent) / 100,
      lessons: c.lessons.map((l) => ({ kind: l.kind, refId: l.refId, maxAttempts: l.maxAttempts.trim() === '' ? null : Number(l.maxAttempts) })),
    })),
  };
}

export function addChapter(d: CourseDraft): CourseDraft {
  return { ...d, chapters: [...d.chapters, { title: '', passPercent: '70', lessons: [] }] };
}

export function updateChapter(d: CourseDraft, index: number, change: Partial<ChapterDraft>): CourseDraft {
  return { ...d, chapters: d.chapters.map((c, i) => (i === index ? { ...c, ...change } : c)) };
}

export function removeChapter(d: CourseDraft, index: number): CourseDraft {
  return { ...d, chapters: d.chapters.filter((_, i) => i !== index) };
}

/** Moves a lesson one place up or down inside its chapter. */
export function moveLesson(d: CourseDraft, chapter: number, index: number, by: -1 | 1): CourseDraft {
  const lessons = [...(d.chapters[chapter]?.lessons ?? [])];
  const to = index + by;
  if (to < 0 || to >= lessons.length) return d;
  [lessons[index], lessons[to]] = [lessons[to]!, lessons[index]!];
  return updateChapter(d, chapter, { lessons });
}

export function addLesson(d: CourseDraft, chapter: number, lesson: LessonDraft): CourseDraft {
  return updateChapter(d, chapter, { lessons: [...(d.chapters[chapter]?.lessons ?? []), lesson] });
}

export function removeLesson(d: CourseDraft, chapter: number, index: number): CourseDraft {
  return updateChapter(d, chapter, { lessons: (d.chapters[chapter]?.lessons ?? []).filter((_, i) => i !== index) });
}

/** The lesson a choice makes: a strategy lesson is a LESSON; a test is a CHAPTER_TEST unless the author says it is a mock test. */
export function lessonOf(choice: RefChoice, kind?: LessonKind): LessonDraft {
  return { kind: choice.kind === 'LESSON' ? 'LESSON' : (kind ?? 'CHAPTER_TEST'), refId: choice.refId, title: choice.title, maxAttempts: '' };
}
