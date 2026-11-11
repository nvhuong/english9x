export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type JsonObject = { [key: string]: Json };

export interface ExamQuestion {
  questionId: string;
  versionId: string;
  typeCode: string;
  points: number;
  content: JsonObject;
}
export interface ExamGroup {
  groupId: string;
  versionId: string;
  content: JsonObject;
  questions: ExamQuestion[];
}
export interface ExamSectionContent {
  sectionId: string;
  skill: string | null;
  durationSec: number;
  startsAt: string;
  endsAt: string;
  groups: ExamGroup[];
}
export interface ExamSectionSummary {
  sectionId: string;
  position: number;
  skill: string | null;
  durationSec: number;
  state: 'DONE' | 'CURRENT' | 'PENDING';
}
export interface ExamResult {
  attemptId: string;
  status: string;
  submittedAt: string;
  reason: string | null;
  interrupted: boolean;
}
export interface ExamAttemptView {
  attemptId: string;
  testVersionId: string;
  title: string | null;
  examCode: string;
  status: 'IN_PROGRESS' | 'SUBMITTED';
  sections: ExamSectionSummary[];
  currentSection: ExamSectionContent | null;
  endsAt: string | null;
  serverNow: string;
  resumeCount: number;
  maxResumes: number;
  saved: Array<{ questionId: string; value: Json; savedAt: string }>;
  result: ExamResult | null;
}

export type InteractionKind =
  | 'MCQ_SINGLE' | 'MCQ_MULTI' | 'GAP_TYPED' | 'GAP_SELECT' | 'MATCHING' | 'TRUE_FALSE_NG'
  | 'YES_NO_NG' | 'LABEL_DIAGRAM' | 'SHORT_ANSWER' | 'ORDERING' | 'ESSAY' | 'SPEAKING_RESPONSE';
