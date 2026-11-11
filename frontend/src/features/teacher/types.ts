/** The JSON of /api/teacher/queue (grading.teacher.TeacherDtos). Never the learner's identity. */
export interface QueueItem {
  id: string;
  examCode: string;
  state: 'OPEN' | 'CLAIMED' | 'DONE';
  mine: boolean;
  createdAt: string;
  claimDueAt: string | null;
}

export interface Criterion {
  code: string;
  name: string;
  min: number | null;
  max: number | null;
  step: number | null;
}

export interface AiResult {
  band: number;
  result: Record<string, unknown>;
}

export interface Task {
  questionId: string;
  typeCode: string;
  prompt: string;
  text: string;
  hasRecording: boolean;
  ai: AiResult | null;
}

export interface Review {
  criteria: Record<string, number>;
  comment: string;
  band: number;
}

export interface SkillWork {
  skill: string;
  tasks: Task[];
  criteria: Criterion[];
  aiBand: number | null;
  review: Review | null;
}

export interface Detail {
  id: string;
  examCode: string;
  state: string;
  claimDueAt: string | null;
  skills: SkillWork[];
}

export interface ReviewResult {
  skill: string;
  band: number;
  done: boolean;
  remaining: string[];
}
