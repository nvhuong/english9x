import type { Block } from '../cms/lesson-editor/types';

export interface LearningQuestion {
  versionId: string;
  typeCode: string;
  content: { stem?: string; options?: { key: string; text: string }[]; [key: string]: unknown };
  points: number;
}

export interface LessonData {
  lessonId: string;
  courseId: string;
  title: string;
  completed: boolean;
  blocks: Block[];
  questionTypeCodes: string[];
  questions: LearningQuestion[];
}

export interface PracticeResultData {
  correct: boolean;
  earned: number;
  explanation?: string | null;
  correctAnswer?: string;
}

export interface ChapterTestData {
  lessonId: string;
  courseId: string;
  title: string;
  passThreshold: number;
  /** 0 = unlimited */
  maxAttempts: number;
  attemptsUsed: number;
  canRetake: boolean;
  bestScore?: number | null;
  groups: { content?: { title?: string; instructions?: string; passage?: string; [key: string]: unknown }; questions: LearningQuestion[] }[];
}

export interface AttemptResultData {
  attemptNo: number;
  score: number;
  raw: number;
  rawMax: number;
  passed: boolean;
  bestScore: number;
  attemptsUsed: number;
  attemptsLeft?: number | null;
}

export interface MapLessonData {
  id: string;
  kind: 'LESSON' | 'CHAPTER_TEST' | 'MOCK_TEST';
  title: string;
  completed: boolean;
  bestScore?: number | null;
  locked: boolean;
}

export interface ChapterMapData {
  courseId: string;
  title: string;
  unlockRule: 'STRICT' | 'COMPLETION' | 'FREE';
  streakDays: number;
  todayMinutes: number;
  chapters: { title: string; passThreshold: number; lessons: MapLessonData[] }[];
}
