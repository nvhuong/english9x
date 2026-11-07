import { env, json, poll, request, type PollOptions } from './env';

export interface TelegramMessage {
  message_id: number;
  token: string;
  chat_id: string | number;
  text: string;
  date: number;
}

/** Messages the app sent to `chatId` via the fake Bot API so far (a single read). Chat ids are the isolation key between tests. */
export async function messagesFor(chatId: string | number): Promise<TelegramMessage[]> {
  return json<TelegramMessage[]>(`${env.telegramURL}/__messages?chat_id=${encodeURIComponent(String(chatId))}`);
}

/** Polls until at least `count` messages (default 1) for `chatId` exist; returns all of them. Throws at the timeout. */
export async function waitForMessages(chatId: string | number, opts: PollOptions & { count?: number } = {}): Promise<TelegramMessage[]> {
  const count = opts.count ?? 1;
  return poll(`${count} Telegram message(s) for chat ${chatId}`, async () => {
    const messages = await messagesFor(chatId);
    return messages.length >= count ? messages : undefined;
  }, opts);
}

/** Sends through the fake Bot API as the app would (harness self-check only). */
export async function sendAsBot(chatId: string | number, text: string, token = 'e2e-token'): Promise<void> {
  await json(`${env.telegramURL}/bot${token}/sendMessage`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ chat_id: chatId, text }),
  });
}

/** Test-local unique chat id (numeric string), so parallel tests never see each other's messages. */
export function uniqueChatId(): string {
  return String(7_000_000_000 + Math.floor(Math.random() * 1_000_000_000));
}

/**
 * Plays Telegram: delivers an update to the app's webhook the way Telegram does (POST with the secret token header).
 * `secret` defaults to the right one; pass another string, or null for no header, to test refusals. Resolves to the HTTP status.
 */
export async function deliverUpdate(
  update: unknown,
  secret: string | null = env.telegramWebhookSecret,
): Promise<number> {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (secret !== null) headers['x-telegram-bot-api-secret-token'] = secret;
  return (await request(`${env.baseURL}/api/telegram/webhook`, { method: 'POST', headers, body: JSON.stringify(update) })).status;
}

/** The update Telegram sends when a person presses Start on a deep link: `/start <token>` in their private chat. */
export function startUpdate(chatId: string | number, token: string): unknown {
  return { update_id: Math.floor(Math.random() * 1e9), message: { message_id: 1, chat: { id: Number(chatId), type: 'private' }, text: `/start ${token}` } };
}

export const telegram = { messagesFor, waitForMessages, sendAsBot, uniqueChatId, deliverUpdate, startUpdate };
