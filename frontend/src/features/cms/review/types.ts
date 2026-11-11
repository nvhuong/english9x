import type { components } from '../../../api/generated/client';

type S = components['schemas'];

/** The server's schemas mark every field optional; these are the shapes the screens rely on (the server always sends them). */
export type QueueLine = Required<Pick<S['ReviewQueueLine'], 'size' | 'type' | 'examCode' | 'submittedBy' | 'submittedAt'>> &
  Pick<S['ReviewQueueLine'], 'reviewId' | 'bundleId' | 'authorName' | 'ownerOrgId'>;

export type ReviewRecord = Required<Pick<S['ReviewRecord'], 'id' | 'type' | 'versionId' | 'examCode' | 'authorId' | 'submittedBy' | 'submittedAt' | 'status'>> &
  Pick<S['ReviewRecord'], 'bundleId' | 'comment' | 'decidedAt' | 'reviewerId'>;

export type Snapshot = { versionId: string; versionNo: number; status: string; content: unknown };

export type ReviewDetail = { review: ReviewRecord; authorName?: string | null; ownVersion: boolean; version?: Snapshot | null; previous?: Snapshot | null };

export type Member = { review: ReviewRecord; authorName?: string | null; ownVersion: boolean };
