import type { PartDraft, TestDraft, TestVersion } from './types';

const DEFAULT_MINUTES = '30';

/** The editor's state for a version the server sent. */
export function draftOf(v: TestVersion): TestDraft {
  return {
    title: v.title,
    maxResumes: v.maxResumes === null ? '' : String(v.maxResumes),
    parts: v.parts.map((p) => ({
      sectionCode: p.sectionCode,
      minutes: String(Math.round((p.durationSec / 60) * 100) / 100),
      groups: p.groups.map((g) => ({ groupVersionId: g.groupVersionId, title: g.title ?? g.groupVersionId })),
    })),
  };
}

/** The body of PUT /api/cms/tests/{id}/structure. Nothing is checked here: the server says what is wrong, by path. */
export function requestOf(d: TestDraft) {
  return {
    title: d.title.trim() || null,
    maxResumes: d.maxResumes.trim() === '' ? null : Number(d.maxResumes),
    parts: d.parts.map((p) => ({
      sectionCode: p.sectionCode,
      durationSec: Math.round(Number(p.minutes) * 60),
      groups: p.groups.map((g) => g.groupVersionId),
    })),
  };
}

/** A new part for the first section of the exam that no part uses yet. */
export function addPart(d: TestDraft, sectionCodes: readonly string[]): TestDraft {
  const next = sectionCodes.find((c) => !d.parts.some((p) => p.sectionCode === c));
  if (!next) return d;
  return { ...d, parts: [...d.parts, { sectionCode: next, minutes: DEFAULT_MINUTES, groups: [] }] };
}

export function updatePart(d: TestDraft, index: number, change: Partial<PartDraft>): TestDraft {
  return { ...d, parts: d.parts.map((p, i) => (i === index ? { ...p, ...change } : p)) };
}

export function removePart(d: TestDraft, index: number): TestDraft {
  return { ...d, parts: d.parts.filter((_, i) => i !== index) };
}

/** Moves a group one place up or down inside its part. */
export function moveGroup(d: TestDraft, part: number, index: number, by: -1 | 1): TestDraft {
  const groups = [...(d.parts[part]?.groups ?? [])];
  const to = index + by;
  if (to < 0 || to >= groups.length) return d;
  const [moved] = groups.splice(index, 1);
  groups.splice(to, 0, moved!);
  return updatePart(d, part, { groups });
}
