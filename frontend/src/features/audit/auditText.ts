import type { TFunction } from 'i18next';

/** The words for a known action; an action this build has no words for is shown by its code, which is always readable. */
export function actionLabel(t: TFunction, action: string): string {
  return t(`actionNames.${action.replace(/[.-]/g, '_')}`, { defaultValue: action });
}

/**
 * The detail of a record as `key: value` text lines. Everything is a string that React will print as text; nothing here is ever
 * given to `dangerouslySetInnerHTML`, so a value like `<img onerror=...>` is shown as those characters.
 */
export function detailLines(detail: Record<string, unknown> | null | undefined): Array<[string, string]> {
  return Object.entries(detail ?? {}).map(([key, value]) => [key, valueText(value)]);
}

function valueText(value: unknown): string {
  if (value === null || value === undefined) return '–';
  if (typeof value === 'string') return value;
  if (Array.isArray(value) && value.every((v) => typeof v === 'string' || typeof v === 'number')) return value.join(', ');
  return JSON.stringify(value);
}

/** `yyyy-MM-dd` (a date input) as the instant that day starts, in the viewer's time zone. */
export function dayStartIso(day: string): string | undefined {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  if (!m) return undefined;
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).toISOString();
}

/** The start of the day AFTER `day`: the exclusive upper bound that makes the chosen "to" day inclusive. */
export function nextDayStartIso(day: string): string | undefined {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  if (!m) return undefined;
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + 1).toISOString();
}
