import type { Json } from './types';

/** One answer waiting to be sent. `answeredAt` is when it was WRITTEN (server clock as estimated), kept through the wait. */
export interface QueuedWrite {
  id?: number;
  attemptId: string;
  questionId: string;
  answer: Json;
  answeredAt: string;
}

export interface QueueStore {
  add(w: Omit<QueuedWrite, 'id'>): Promise<void>;
  /** In the order they were added. */
  all(attemptId: string): Promise<QueuedWrite[]>;
  remove(id: number): Promise<void>;
}

export function createMemoryStore(): QueueStore {
  let next = 1;
  const rows: QueuedWrite[] = [];
  return {
    async add(w) {
      rows.push({ ...w, id: next++ });
    },
    async all(attemptId) {
      return rows.filter((r) => r.attemptId === attemptId).map((r) => ({ ...r }));
    },
    async remove(id) {
      const i = rows.findIndex((r) => r.id === id);
      if (i >= 0) rows.splice(i, 1);
    },
  };
}

const STORE = 'writes';

/** IndexedDB: survives reload and a crashed tab. The auto-increment key is the order of replay. */
export function createIndexedDbStore(dbName = 'e9x-exam'): QueueStore {
  let opened: Promise<IDBDatabase> | undefined;
  const open = () =>
    (opened ??= new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(dbName, 1);
      req.onupgradeneeded = () => {
        const s = req.result.createObjectStore(STORE, { keyPath: 'id', autoIncrement: true });
        s.createIndex('attempt', 'attemptId');
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }));
  const run = async <T,>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> => {
    const db = await open();
    return new Promise<T>((resolve, reject) => {
      const req = fn(db.transaction(STORE, mode).objectStore(STORE));
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  };
  return {
    async add(w) {
      await run('readwrite', (s) => s.add(w));
    },
    async all(attemptId) {
      const rows = await run('readonly', (s) => s.index('attempt').getAll(attemptId) as IDBRequest<QueuedWrite[]>);
      return rows.sort((a, b) => (a.id ?? 0) - (b.id ?? 0));
    },
    async remove(id) {
      await run('readwrite', (s) => s.delete(id));
    },
  };
}

/** IndexedDB when the browser lets us, memory otherwise (private windows): answers are still kept for as long as the tab lives. */
export function defaultStore(): QueueStore {
  try {
    if (typeof indexedDB !== 'undefined') return createIndexedDbStore();
  } catch {
    /* fall through */
  }
  return createMemoryStore();
}

export type SendResult = 'sent' | 'rejected' | 'retry';
export interface FlushResult {
  sent: number;
  rejected: number;
  remaining: number;
}

/**
 * Every answer goes through here: written first, then sent, so a lost connection or a reload never loses one. `flush` replays IN ORDER and stops at the
 * first answer that cannot be sent now (`retry`: offline, 5xx, session): the ones after it wait, so an older answer never overwrites a newer one.
 * A `rejected` answer (the server said no for good, e.g. 409 after the section ended) is dropped and the replay goes on.
 */
export class OfflineQueue {
  private flushing: Promise<unknown> | null = null;
  constructor(private readonly store: QueueStore, private readonly send: (w: QueuedWrite) => Promise<SendResult>) {}

  async enqueue(w: Omit<QueuedWrite, 'id'>): Promise<void> {
    await this.store.add(w);
  }
  async pending(attemptId: string): Promise<number> {
    return (await this.store.all(attemptId)).length;
  }
  async peek(attemptId: string): Promise<QueuedWrite[]> {
    return this.store.all(attemptId);
  }
  flush(attemptId: string): Promise<FlushResult> {
    // one replay at a time, in the order asked: a flush asked during another waits for it, then looks at the store again
    const p = (this.flushing ?? Promise.resolve()).catch(() => undefined).then(() => this.run(attemptId));
    this.flushing = p;
    void p.finally(() => {
      if (this.flushing === p) this.flushing = null;
    });
    return p;
  }
  private async run(attemptId: string): Promise<FlushResult> {
    let sent = 0;
    let rejected = 0;
    let rows = await this.store.all(attemptId);
    let i = 0;
    for (; i < rows.length; i++) {
      const w = rows[i]!;
      const r = await this.send(w);
      if (r === 'retry') break;
      await this.store.remove(w.id!);
      if (r === 'sent') sent++;
      else rejected++;
    }
    rows = await this.store.all(attemptId);
    return { sent, rejected, remaining: rows.length };
  }
}
