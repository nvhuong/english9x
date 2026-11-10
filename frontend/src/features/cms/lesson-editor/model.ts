import type { FieldIssue } from './problems';
import type { Block, LessonDraft, LessonVersion } from './types';

export interface Item {
  /** Stable for the life of the editor (the position changes when blocks move). */
  key: string;
  block: Block;
}

export interface LessonModel {
  title: string;
  items: Item[];
  typeCodes: string[];
}

let counter = 0;
export const newKey = () => `b${++counter}`;

export const emptyModel = (): LessonModel => ({ title: '', items: [], typeCodes: [] });

export const modelOf = (v: LessonVersion): LessonModel => ({
  title: v.title,
  items: v.blocks.map((block) => ({ key: newKey(), block })),
  typeCodes: [...v.questionTypeCodes],
});

export const draftOf = (examCode: string, m: LessonModel): LessonDraft => ({
  examCode,
  title: m.title,
  blocks: m.items.map((i) => i.block),
  questionTypeCodes: m.typeCodes,
});

export function move<T>(list: T[], from: number, to: number): T[] {
  if (to < 0 || to >= list.length) return list;
  const next = [...list];
  const [x] = next.splice(from, 1);
  next.splice(to, 0, x!);
  return next;
}

/** The server's `blocks[3].url` as `{ index: 3, field: 'url' }`; null for any other path. */
export function blockPath(field: string): { index: number; field: string } | null {
  const m = /^blocks\[(\d+)\](?:\.(.+))?$/.exec(field);
  return m ? { index: Number(m[1]), field: m[2] ?? '' } : null;
}

export function issuesByBlock(issues: FieldIssue[]): Map<number, Record<string, string>> {
  const out = new Map<number, Record<string, string>>();
  for (const i of issues) {
    const p = blockPath(i.field);
    if (!p) continue;
    const key = p.field.split('.')[0]!.replace(/\[\d+\]/g, '') || '_';
    out.set(p.index, { ...(out.get(p.index) ?? {}), [key]: i.message });
  }
  return out;
}
