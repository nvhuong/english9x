import type { components } from '../../../api/generated/client';

export type ImportDetail = components['schemas']['ImportDetail'];
export type ImportView = components['schemas']['ImportView'];
export type ImportError = components['schemas']['ErrorView'];
export type ImportStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'REJECTED' | 'FAILED';

/** The job is still the server's to finish: keep asking. */
export const isRunning = (status: string | undefined) => status === 'QUEUED' || status === 'RUNNING';
