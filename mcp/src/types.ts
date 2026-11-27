export interface ApiResponse<T = any> {
  success: boolean;
  data?: T;
  error?: string;
  statusCode?: number;
}

export interface UserContext {
  userId: string;
  email: string;
  displayName: string;
  roles: string[];
  currentOrgId?: string;
}

export interface CourseCatalogItem {
  id: string;
  code: string;
  examCode: string;
  title: string;
  description?: string;
  authorDisplay?: string;
  unlockRule: string;
  chapters: number;
  lessons: number;
  shared: boolean;
}

export interface CourseOutline {
  code: string;
  examCode: string;
  title: string;
  description?: string;
  authorDisplay?: string;
  unlockRule: string;
  shared: boolean;
  chapters: Array<{
    title: string;
    lessons: Array<{
      id: string;
      kind: string;
      title: string;
    }>;
  }>;
}

export interface TestSummary {
  testId: string;
  versionId: string;
  examCode: string;
  variant?: string;
  title: string;
  status: string;
  partsCount?: number;
  totalDurationSec?: number;
}

export interface QuestionView {
  questionId: string;
  versionId: string;
  examCode: string;
  typeCode: string;
  points: number;
  content: any;
  answerKey?: any;
  explanation?: string;
}

export interface AttemptView {
  id: string;
  testVersionId: string;
  examCode: string;
  status: string;
  currentSectionId?: string;
  timeRemainingSec?: number;
  sections?: Array<{
    id: string;
    sectionCode: string;
    skill: string;
    durationSec: number;
    questionCount: number;
  }>;
}

export interface AttemptResult {
  attemptId: string;
  status: string;
  totalScore?: number;
  skills?: Record<string, { score: number; rawScore?: number; status: string }>;
  questions?: Array<{
    questionId: string;
    typeCode: string;
    correct: boolean;
    userAnswer?: any;
    answerKey?: any;
    explanation?: string;
  }>;
}

export interface WeaknessReport {
  examCode?: string;
  weakestQuestionTypes: Array<{
    typeCode: string;
    accuracyRate: number;
    totalAttempted: number;
    recommendedLessons?: Array<{
      courseCode: string;
      lessonId: string;
      title: string;
    }>;
  }>;
}

export interface ClassView {
  id: string;
  orgId: string;
  name: string;
  teacherId?: string;
  memberCount?: number;
  createdAt?: string;
}

export interface ClassMemberView {
  userId: string;
  email: string;
  displayName: string;
  joinedAt: string;
}

export interface PlanView {
  id: string;
  code: string;
  name: string;
  description?: string;
  priceVnd: number;
  durationDays: number;
  examCodes: string[];
  onSale: boolean;
}

export interface OrderView {
  id: string;
  code: string;
  planId: string;
  planName: string;
  amountVnd: number;
  status: string;
  createdAt: string;
  paidAt?: string;
}

export interface DatabaseQueryResult {
  command: string;
  rowCount: number;
  fields: Array<{ name: string; dataTypeId: number }>;
  rows: any[];
}
