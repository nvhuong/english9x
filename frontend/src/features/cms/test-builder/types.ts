export type ContentStatus = 'DRAFT' | 'IN_REVIEW' | 'CHANGES_REQUESTED' | 'PUBLISHED' | 'ARCHIVED';

export interface TestGroup {
  groupVersionId: string;
  groupId: string | null;
  title: string | null;
  questions: number;
}

export interface TestPart {
  position: number;
  sectionCode: string;
  skill: string | null;
  durationSec: number;
  groups: TestGroup[];
  questions: number;
}

/** A line of what the exam expects of the test: `state` is OK, MISSING, SHORT or OVER. */
export interface SectionCheck {
  sectionCode: string;
  skill: string;
  expected: number;
  actual: number;
  state: 'OK' | 'MISSING' | 'SHORT' | 'OVER';
}

export interface TestIssue {
  sectionCode: string | null;
  skill: string | null;
  code: string;
  message: string;
}

/** A version of a test as the CMS API answers it. */
export interface TestVersion {
  versionId: string;
  testId: string;
  versionNo: number;
  status: ContentStatus;
  examCode: string;
  variant: string | null;
  title: string;
  maxResumes: number | null;
  parts: TestPart[];
  sections: SectionCheck[];
  warnings: TestIssue[];
  blockers: TestIssue[];
  scoreTableOverrides: Array<{ scoreTableId: string; skill: string }>;
}

export interface TestSummary {
  testId: string;
  versionId: string;
  versionNo: number;
  status: ContentStatus;
  examCode: string;
  variant: string | null;
  title: string;
  parts: number;
}

export interface TestPage {
  items: TestSummary[];
  offset: number;
  limit: number;
}

export interface TestFilters {
  exam?: string;
  status?: ContentStatus;
  page?: number;
}

/** A question group the author may put in a test (the latest version of each group of the exam). */
export interface GroupChoice {
  groupVersionId: string;
  title: string;
  members: number;
  status: ContentStatus;
}

export interface ExamInfo {
  code: string;
  name: string;
  variants: string[];
  sections: Array<{ code: string; skill: string; position: number; itemCount: number }>;
}

/** The structure as the person edits it: minutes are typed, seconds are sent. */
export interface PartDraft {
  sectionCode: string;
  minutes: string;
  groups: Array<{ groupVersionId: string; title: string }>;
}

export interface TestDraft {
  title: string;
  /** Empty = the default of the exam. */
  maxResumes: string;
  parts: PartDraft[];
}
