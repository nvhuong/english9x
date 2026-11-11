export type ContentStatus = 'DRAFT' | 'IN_REVIEW' | 'CHANGES_REQUESTED' | 'PUBLISHED' | 'ARCHIVED';
export type LessonKind = 'LESSON' | 'CHAPTER_TEST' | 'MOCK_TEST';
export type UnlockRule = 'STRICT' | 'COMPLETION' | 'FREE';

export interface CourseLesson {
  id: string;
  kind: LessonKind;
  refId: string;
  title: string;
  maxAttempts: number | null;
}

export interface CourseChapter {
  id: string;
  position: number;
  title: string;
  passThreshold: number;
  lessons: CourseLesson[];
}

/** A version of a course as the CMS API answers it. */
export interface CourseVersion {
  versionId: string;
  courseId: string;
  code: string;
  versionNo: number;
  status: ContentStatus;
  examCode: string;
  title: string;
  description: string;
  authorDisplay: string;
  unlockRule: UnlockRule;
  chapters: CourseChapter[];
  sharedStatus: 'NONE' | 'PENDING' | 'APPROVED';
}

export interface CourseSummary {
  courseId: string;
  versionId: string;
  code: string;
  versionNo: number;
  status: ContentStatus;
  examCode: string;
  title: string;
  chapters: number;
  lessons: number;
}

export interface CoursePage {
  items: CourseSummary[];
  offset: number;
  limit: number;
}

export interface CourseFilters {
  exam?: string;
  status?: ContentStatus;
  page?: number;
}

/** A version of a strategy lesson or of a test the author may put in a course. */
export interface RefChoice {
  refId: string;
  title: string;
  kind: 'LESSON' | 'TEST';
  status: ContentStatus;
}

export interface LessonDraft {
  kind: LessonKind;
  refId: string;
  title: string;
  /** Empty = unlimited. */
  maxAttempts: string;
}

export interface ChapterDraft {
  title: string;
  /** Percent typed, 0 to 100; sent as a fraction. */
  passPercent: string;
  lessons: LessonDraft[];
}

export interface CourseDraft {
  examCode: string;
  code: string;
  title: string;
  description: string;
  authorDisplay: string;
  unlockRule: UnlockRule;
  chapters: ChapterDraft[];
}
