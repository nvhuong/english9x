import { env, json, poll, type PollOptions } from './env';

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

export const telegram = { messagesFor, waitForMessages, sendAsBot, uniqueChatId };
