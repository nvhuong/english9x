/** The JSON of /api/results (F38), as the server sends it. */
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

/** PRELIMINARY / OFFICIAL as chosen by the server (the rule of the teacher's queue), PENDING while a skill has no score. */
export type ScoreLabel = 'PRELIMINARY' | 'OFFICIAL' | 'PENDING';

export interface SkillScore {
  skill: string;
  aggregate: boolean;
  score: number | null;
  raw: number | null;
  rawMax: number | null;
  label: ScoreLabel;
  source: 'AUTO' | 'AI' | 'TEACHER' | null;
}

export interface QuestionResult {
  questionId: string | null;
  sectionCode: string;
  skill: string;
  typeCode: string;
  typeName: string;
  scored: boolean;
  correct: boolean;
  earned: number;
  max: number;
  answer: Json | null;
  correctAnswer: Json | null;
  explanation: string | null;
  transcript: string | null;
}

export interface AiFeedback {
  questionId: string;
  band: number;
  result: Json;
}

export interface TeacherFeedback {
  criteria: Json;
  comment: string;
  band: number;
}

export interface ProductiveFeedback {
  skill: string;
  ai: AiFeedback[];
  teacher: TeacherFeedback | null;
}

export interface AttemptResult {
  attemptId: string;
  /** SCORING: submitted, not scored yet; nothing else is sent. READY: everything below is filled. */
  state: 'SCORING' | 'READY';
  examCode: string;
  interrupted: boolean;
  submittedAt: string | null;
  skills: SkillScore[];
  questions: QuestionResult[];
  feedback: ProductiveFeedback[];
}

export interface TypeRate {
  typeCode: string;
  typeName: string;
  correct: number;
  total: number;
  rate: number;
}

export interface LessonRef {
  courseId: string;
  courseTitle: string;
  lessonId: string;
  title: string;
}

export interface Recommendation {
  typeCode: string;
  typeName: string;
  rate: number;
  lessons: LessonRef[];
}

export interface Weakness {
  exam: string | null;
  attempts: number;
  types: TypeRate[];
  recommendations: Recommendation[];
}
