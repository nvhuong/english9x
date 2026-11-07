import { env, json, poll, type PollOptions } from './env';

export interface MailAddress { Name: string; Address: string }
export interface MailSummary {
  ID: string;
  Subject: string;
  From: MailAddress;
  To: MailAddress[];
  Snippet: string;
  Created: string;
}
export interface Mail extends MailSummary {
  Text: string;
  HTML: string;
}

let counter = 0;
/** An address no other test uses (tests may run in parallel, Mailpit is shared): isolate data by email, never by clearing the box. */
export function uniqueEmail(label = 'user'): string {
  const safe = label.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'user';
  return `${safe}-${Date.now().toString(36)}${(counter++).toString(36)}${Math.random().toString(36).slice(2, 6)}@e2e.english9x.test`;
}

/** Newest message addressed to `email`, or undefined. */
async function find(email: string): Promise<MailSummary | undefined> {
  const q = encodeURIComponent(`to:"${email}"`);
  const res = await json<{ messages?: MailSummary[] }>(`${env.mailpitURL}/api/v1/search?query=${q}`);
  return res.messages?.find((m) => m.To?.some((t) => t.Address.toLowerCase() === email.toLowerCase()));
}

/** Waits (polling) for a message to `email` and returns it with its text and HTML bodies. Throws at the timeout. */
export async function lastEmailTo(email: string, opts: PollOptions & { subjectIncludes?: string } = {}): Promise<Mail> {
  const summary = await poll(
    `an email to ${email}${opts.subjectIncludes ? ` with subject containing "${opts.subjectIncludes}"` : ''} in Mailpit`,
    async () => {
      const q = encodeURIComponent(`to:"${email}"`);
      const res = await json<{ messages?: MailSummary[] }>(`${env.mailpitURL}/api/v1/search?query=${q}`);
      return res.messages?.find(
        (m) =>
          m.To?.some((t) => t.Address.toLowerCase() === email.toLowerCase()) &&
          (opts.subjectIncludes === undefined || m.Subject.includes(opts.subjectIncludes)),
      );
    },
    opts,
  );
  return json<Mail>(`${env.mailpitURL}/api/v1/message/${summary.ID}`);
}

/** True when no message to `email` exists right now (a single check, for "no email was sent" assertions after the app has had time to act). */
export async function hasNoEmailTo(email: string): Promise<boolean> {
  return (await find(email)) === undefined;
}

/** How many messages to `email` exist right now, optionally only those whose subject contains `subjectIncludes` (a single check). */
export async function countEmailsTo(email: string, subjectIncludes?: string): Promise<number> {
  const q = encodeURIComponent(`to:"${email}"`);
  const res = await json<{ messages?: MailSummary[] }>(`${env.mailpitURL}/api/v1/search?query=${q}`);
  return (res.messages ?? []).filter(
    (m) => m.To?.some((t) => t.Address.toLowerCase() === email.toLowerCase()) && (subjectIncludes === undefined || m.Subject.includes(subjectIncludes)),
  ).length;
}

/** http(s) links in a message, in order, trailing punctuation removed. */
export function linksIn(mail: Mail): string[] {
  const found = `${mail.Text}\n${mail.HTML}`.match(/https?:\/\/[^\s"'<>)]+/g) ?? [];
  return [...new Set(found.map((l) => l.replace(/[.,;]+$/, '')))];
}

/** Sends a message through Mailpit's own API (used by the harness self-check; the app sends over SMTP). */
export async function sendTestEmail(to: string, subject: string, text: string): Promise<void> {
  await json(`${env.mailpitURL}/api/v1/send`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ From: { Email: 'harness@e2e.english9x.test' }, To: [{ Email: to }], Subject: subject, Text: text }),
  });
}

/** Removes this address's messages only (never the whole box: other tests share it). */
export async function deleteEmailsTo(email: string): Promise<void> {
  const q = encodeURIComponent(`to:"${email}"`);
  await json(`${env.mailpitURL}/api/v1/search?query=${q}`, { method: 'DELETE' });
}

export const mailpit = { lastEmailTo, hasNoEmailTo, countEmailsTo, linksIn, uniqueEmail, sendTestEmail, deleteEmailsTo };
