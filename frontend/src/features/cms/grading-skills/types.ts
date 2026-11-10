import type { components } from '../../../api/generated/client';

type Schemas = components['schemas'];

export type ContentStatus = NonNullable<Schemas['GradingSkillVersion']['status']>;

export interface Criterion {
  code: string;
  name: string;
  min: number;
  max: number;
  step: number;
}

export interface Sample {
  band: number;
  text?: string;
  mediaId?: string;
  commentary?: string;
}

/** A version of a grading skill as the CMS API answers it. */
export interface SkillVersion extends Omit<Schemas['GradingSkillVersion'], 'criteria' | 'samples' | 'resultSchema' | 'status'> {
  status: ContentStatus;
  versionId: string;
  skillId: string;
  versionNo: number;
  examCode: string;
  questionTypeCode: string;
  rubric: string;
  promptTemplate: string;
  criteria: Criterion[];
  samples: Sample[];
  resultSchema: Record<string, unknown>;
}

export type SkillSummary = Required<Pick<Schemas['GradingSkillSummary'], 'skillId' | 'versionId' | 'versionNo' | 'status' | 'examCode' | 'questionTypeCode'>>;

export interface SkillPage {
  items: SkillSummary[];
  total: number;
  offset: number;
  limit: number;
}

/** The body of POST / PUT /api/cms/grading-skills. Numbers may be absent or null: the server says which and why. */
export interface SkillDraft {
  questionTypeCode: string;
  rubric: string;
  criteria: Array<{ code: string; name: string; min?: number | null; max?: number | null; step?: number | null }>;
  promptTemplate: string;
  samples: Array<{ band?: number | null; text?: string; mediaId?: string; commentary?: string }>;
  resultSchema: unknown;
}

export interface SkillFilters {
  exam?: string;
  status?: ContentStatus;
  page?: number;
}

/** The question types a skill can be made for: the ones a person writes, not the ones an answer key scores. */
export const GRADED_KINDS = ['ESSAY', 'SPEAKING_RESPONSE'];
