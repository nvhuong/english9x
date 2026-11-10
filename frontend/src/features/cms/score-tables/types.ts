import type { components } from '../../../api/generated/client';

type Schemas = components['schemas'];

export type ContentStatus = NonNullable<Schemas['ScoreTableVersion']['status']>;

export interface ScoreRow {
  raw: number;
  scaled: number;
}

/** A version of a conversion table as the CMS API answers it. */
export interface ScoreTableVersion extends Omit<Schemas['ScoreTableVersion'], 'rows' | 'status'> {
  status: ContentStatus;
  versionId: string;
  tableId: string;
  versionNo: number;
  examCode: string;
  skill: string;
  function: string;
  rows: ScoreRow[];
}

export type ScoreTableSummary = Required<Pick<Schemas['ScoreTableSummary'], 'tableId' | 'versionId' | 'versionNo' | 'status' | 'examCode' | 'skill' | 'function' | 'rowCount'>>;

export interface ScoreTablePage {
  items: ScoreTableSummary[];
  total: number;
  offset: number;
  limit: number;
}

/** The body of POST / PUT /api/cms/score-tables. Only the EXAM scope can be written until tests can be built. */
export interface ScoreTableDraft {
  examCode: string;
  skill: string;
  scope: 'EXAM';
  rows: Array<{ raw: number | null; scaled: number | null }>;
}

export interface ScoreTableFilters {
  exam?: string;
  status?: ContentStatus;
  page?: number;
}

export interface ExamScores {
  code: string;
  name: string;
  scoreSkills: string[];
}
