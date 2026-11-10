import type { components } from '../../../api/generated/client';
import { ContentStatuses, type ContentStatus, type QuestionPage, type QuestionSummary } from './types';

type Raw = components['schemas']['QuestionSummary'];

/**
 * The generated types make every field of a response optional (the server's schema marks none as required). The screens need the identifying ones, so
 * they are checked here once, at the edge: a summary without them is dropped (it cannot be opened or listed), never invented. The types are not loosened.
 */
export function narrowSummary(raw: Raw | null | undefined): QuestionSummary | null {
  if (!raw || !raw.questionId || !raw.versionId || !raw.examCode || !raw.typeCode || typeof raw.versionNo !== 'number') return null;
  if (!ContentStatuses.includes(raw.status as ContentStatus)) return null;
  return {
    questionId: raw.questionId,
    versionId: raw.versionId,
    versionNo: raw.versionNo,
    status: raw.status as ContentStatus,
    examCode: raw.examCode,
    typeCode: raw.typeCode,
    ...(raw.title !== undefined && { title: raw.title }),
    ...(raw.points !== undefined && { points: raw.points }),
    ...(raw.updatedAt !== undefined && { updatedAt: raw.updatedAt }),
    ...(raw.createdBy !== undefined && { createdBy: raw.createdBy }),
  };
}

export function narrowPage(raw: { items?: Raw[]; total?: number; offset?: number; limit?: number } | null | undefined): QuestionPage {
  const items = (raw?.items ?? []).map(narrowSummary).filter((x): x is QuestionSummary => x !== null);
  return { items, total: raw?.total ?? items.length, offset: raw?.offset ?? 0, limit: raw?.limit ?? items.length };
}
