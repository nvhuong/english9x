import type { components } from '../../../api/generated/client';

type Schemas = components['schemas'];

export type ContentStatus = NonNullable<Schemas['StrategyLessonVersion']['status']>;

export type Callout = 'tip' | 'trap';
export type Provider = 'youtube' | 'vimeo';

/** The blocks of a lesson, as the server stores them (catalog/strategy-block.schema.json). */
export type Block =
  | { type: 'heading'; level: number; text: string }
  | { type: 'paragraph'; text: string }
  | { type: 'table'; header: string[]; rows: string[][]; caption?: string }
  | { type: 'image'; imageMediaId: string; alt: string; caption?: string }
  | { type: 'audio'; audioMediaId: string; caption?: string }
  | { type: 'video-upload'; videoMediaId: string; caption?: string }
  | { type: 'video-embed'; provider: Provider; url: string; caption?: string }
  | { type: 'callout'; kind: Callout; text: string }
  | { type: 'embedded-question'; questionVersionId: string }
  | { type: 'worked-example'; title: string; problem: string; steps: string[]; answer?: string };

export type BlockType = Block['type'];

export const BLOCK_TYPES: BlockType[] = [
  'heading',
  'paragraph',
  'table',
  'image',
  'audio',
  'video-upload',
  'video-embed',
  'callout',
  'embedded-question',
  'worked-example',
];

export interface LessonVersion extends Omit<Schemas['StrategyLessonVersion'], 'blocks' | 'status'> {
  blocks: Block[];
  status: ContentStatus;
  versionId: string;
  lessonId: string;
  versionNo: number;
  examCode: string;
  title: string;
  questionTypeCodes: string[];
}

export type LessonSummary = Required<Pick<Schemas['StrategyLessonSummary'], 'lessonId' | 'versionId' | 'versionNo' | 'status' | 'examCode' | 'title'>> &
  Pick<Schemas['StrategyLessonSummary'], 'updatedAt'>;

export interface LessonPage {
  items: LessonSummary[];
  total: number;
  offset: number;
  limit: number;
}

/** The body of POST / PUT /api/cms/strategy-lessons. */
export interface LessonDraft {
  examCode: string;
  title: string;
  blocks: Block[];
  questionTypeCodes: string[];
}

export interface LessonFilters {
  exam?: string;
  status?: ContentStatus;
  page?: number;
}
