import type { TFunction } from 'i18next';

/**
 * The words of one notification, in the language of the viewer (the server stores only the event type and its data, so switching the
 * language re-words old notifications). An event type this build does not know is shown by its code rather than hidden.
 */
export function eventText(t: TFunction, type: string, data: Record<string, unknown> | undefined): { title: string; body: string } {
  const values = Object.fromEntries(Object.entries(data ?? {}).map(([k, v]) => [k, v == null ? '' : String(v)]));
  return {
    title: t(`event.${type}.title`, { defaultValue: type, ...values }),
    body: t(`event.${type}.body`, { defaultValue: '', ...values }),
  };
}
