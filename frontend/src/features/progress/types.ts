/** The answers of GET /api/me/progress (F24). Hand-written like the other result screens: the generated schema types these loosely. */
export interface Score {
  value: number;
  kind: 'PRELIMINARY' | 'OFFICIAL';
  source: string;
  examCode: string;
}
export interface CourseProgress {
  courseId: string;
  code: string | null;
  title: string;
  percent: number;
  lessonsDone: number;
  lessonsTotal: number;
  chaptersDone: number;
  chaptersTotal: number;
  chapterTestsDone: number;
  chapterTestsTotal: number;
  examsTaken: number;
  latestScore: Score | null;
  bestScore: Score | null;
  pendingAssignments: number;
  resumeLessonId: string | null;
  lastActivity: string | null;
}
export interface PlanCard {
  source: 'ORDER' | 'SEAT';
  id: string;
  name: string | null;
  organisation: string | null;
  activatedAt: string;
  validUntil: string;
  active: boolean;
  readOnly: boolean;
  daysLeft: number;
  coursesUsed: number;
  coursesMax: number | null;
  courses: CourseProgress[];
}
export interface ScorePoint {
  attemptId: string;
  examCode: string;
  at: string;
  score: number;
  kind: 'PRELIMINARY' | 'OFFICIAL';
  predicted: number;
}
export interface WeekMinutes {
  weekStart: string;
  minutes: number;
}
export interface PendingAssignment {
  id: string;
  className: string;
  dueAt: string;
  overdue: boolean;
  courseId: string | null;
}
export interface Progress {
  plans: PlanCard[];
  expired: PlanCard[];
  scoreSeries: ScorePoint[];
  weeklyMinutes: WeekMinutes[];
  streak: { currentDays: number; longestDays: number; lastDay: string | null };
  pendingAssignments: PendingAssignment[];
}
