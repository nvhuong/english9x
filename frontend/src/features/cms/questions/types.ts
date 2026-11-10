import type { components } from '../../../api/generated/client';

/** JSON with no further shape: the content and the answer key are checked against the schema of the question type, by the server. */
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type JsonObject = { [key: string]: Json };

type Schemas = components['schemas'];

export type ContentStatus = NonNullable<Schemas['QuestionVersion']['status']>;
export const ContentStatuses: readonly ContentStatus[] = ['DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'];

/** A question version as the editor gets it: with the solution side (the answer key is for the exam's author and reviewer only). */
export interface QuestionVersion extends Omit<Schemas['QuestionVersion'], 'content' | 'answerKey' | 'status'> {
  content: JsonObject;
  answerKey: JsonObject;
  status: ContentStatus;
  versionId: string;
  questionId: string;
  versionNo: number;
  typeCode: string;
  examCode: string;
}

/** The form of one question type: the JSON Schemas the server validates the content and the answer key against. */
export interface QuestionTypeForm {
  code: string;
  examCode: string;
  section: string;
  kind: string;
  contentSchema: JsonObject;
  answerKeySchema: JsonObject;
}

export type QuestionSummary = Required<Pick<Schemas['QuestionSummary'], 'questionId' | 'versionId' | 'versionNo' | 'status' | 'examCode' | 'typeCode'>> &
  Pick<Schemas['QuestionSummary'], 'title' | 'points' | 'updatedAt' | 'createdBy'>;

export interface QuestionPage {
  items: QuestionSummary[];
  total: number;
  offset: number;
  limit: number;
}

/** The body of POST / PUT /api/cms/questions. */
export interface QuestionDraft {
  typeCode: string;
  content: JsonObject;
  answerKey: JsonObject;
  /** Left out when empty. */
  explanation?: string;
  points: number;
}

export interface QuestionFilters {
  exam?: string;
  type?: string;
  status?: ContentStatus;
  page?: number;
  /** Page size; the server's default when left out. */
  size?: number;
}
