// F52 F53 F54 F55 — the grading events of spec 13 on every channel, from ONE run (it holds the fake Gemini for minutes, so it is taken once, not by four files).
// The F-ID files (F52-in-app, F53-email, F54-telegram, F55-channel-prefs) keep the other events; this file asserts the screen (F52), the mailbox (F53), the chat (F54)
// and the preferences (F55) for SCORE_PRELIMINARY, SCORE_OFFICIAL, GRADING_QUEUE_NEW, GRADING_CLAIM_DUE and AI_GRADING_FAILED.
import { test } from '../../fixtures';
import { gradingWorld, runChannels, type Fx } from './_events';

test.describe('F52 F53 F54 F55 grading events', () => {
  // The ONE run of the grading events. The fake Gemini lock is taken once for it, so it carries every channel of the four specs: F52 (the screen), F53 (mail),
  // F54 (the chat) and F55 (the preferences): every recipient is linked, every event is asserted on the screen, by mail and in the chat, except the two switched off
  // on the preference screen: Email off for SCORE_PRELIMINARY (SCORE_OFFICIAL follows to the same learner) and Telegram off for GRADING_QUEUE_NEW (GRADING_CLAIM_DUE follows
  // to the same teacher). Their absence is asserted after the later event arrived on that channel.
  test('F52 F53 F54 F55 the grading events reach the screen, the mailbox and the chat, and the channel switched off for an event gets nothing', async ({ browser, seed, auth, org, mailpit, telegram, uniqueEmail, request, gemini }) => {
    const fx: Fx = { seed, org, uniqueEmail, request, gemini };
    const world = await gradingWorld(fx, 'f55grading');
    test.setTimeout(world.timeoutMs);
    const all = world.steps.flatMap((st) => st.expects);
    const find = (event: string) => all.find((e) => e.event === event)!;
    const off = [
      { who: find('SCORE_PRELIMINARY').who, event: 'SCORE_PRELIMINARY' as const, channel: 'Email' as const },
      { who: find('GRADING_QUEUE_NEW').who, event: 'GRADING_QUEUE_NEW' as const, channel: 'Telegram' as const },
    ];
    await runChannels({ fx, world, browser, auth, telegram, mailpit, use: { screen: true, email: true, telegram: true }, off });
  });
});
