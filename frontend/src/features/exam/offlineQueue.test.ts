import { describe, expect, it } from 'vitest';
import { OfflineQueue, createMemoryStore, type QueuedWrite } from './offlineQueue';

const w = (questionId: string, answer: string, at: string) => ({ attemptId: 'a1', questionId, answer, answeredAt: at });

describe('offlineQueue', () => {
  it('replaysInOrder: while offline nothing is lost, and when the connection returns the answers go out in the order they were written', async () => {
    let online = false;
    const sent: string[] = [];
    const q = new OfflineQueue(createMemoryStore(), async (x: QueuedWrite) => {
      if (!online) return 'retry';
      sent.push(`${x.questionId}=${String(x.answer)}@${x.answeredAt}`);
      return 'sent';
    });
    await q.enqueue(w('q1', 'A', 't1'));
    await q.enqueue(w('q2', 'B', 't2'));
    await q.enqueue(w('q1', 'C', 't3')); // the same question again: the later answer must arrive later
    expect(await q.flush('a1')).toMatchObject({ sent: 0, remaining: 3 });
    expect(sent).toEqual([]);
    online = true;
    expect(await q.flush('a1')).toMatchObject({ sent: 3, remaining: 0 });
    expect(sent).toEqual(['q1=A@t1', 'q2=B@t2', 'q1=C@t3']);
  });

  it('stops at the first answer it cannot send, so a later one never overtakes it', async () => {
    const sent: string[] = [];
    let failQ2 = true;
    const q = new OfflineQueue(createMemoryStore(), async (x) => {
      if (x.questionId === 'q2' && failQ2) return 'retry';
      sent.push(x.questionId);
      return 'sent';
    });
    for (const id of ['q1', 'q2', 'q3']) await q.enqueue(w(id, 'x', 't'));
    expect(await q.flush('a1')).toMatchObject({ sent: 1, remaining: 2 });
    failQ2 = false;
    await q.flush('a1');
    expect(sent).toEqual(['q1', 'q2', 'q3']);
  });

  it('drops an answer the server refused for good and goes on; keeps attempts apart', async () => {
    const q = new OfflineQueue(createMemoryStore(), async (x) => (x.questionId === 'late' ? 'rejected' : 'sent'));
    await q.enqueue(w('late', 'x', 't'));
    await q.enqueue(w('ok', 'y', 't'));
    await q.enqueue({ ...w('other', 'z', 't'), attemptId: 'a2' });
    expect(await q.flush('a1')).toEqual({ sent: 1, rejected: 1, remaining: 0 });
    expect(await q.pending('a2')).toBe(1);
  });

  it('a flush asked during another waits for it and then sees what was added meanwhile', async () => {
    const sent: string[] = [];
    const q = new OfflineQueue(createMemoryStore(), async (x) => {
      await Promise.resolve();
      sent.push(x.questionId);
      return 'sent';
    });
    await q.enqueue(w('q1', 'a', 't'));
    const first = q.flush('a1');
    await q.enqueue(w('q2', 'b', 't'));
    const second = q.flush('a1');
    await Promise.all([first, second]);
    expect(sent).toEqual(['q1', 'q2']);
  });
});
