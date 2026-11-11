/**
 * The server's clock as this tab estimates it: the `serverNow` of an answer plus the time elapsed since, measured with performance.now() (monotonic),
 * never Date.now(): changing the computer's clock must not move the exam clock.
 */
export class ServerClock {
  private readonly serverMs: number;
  private readonly perfAtSync: number;
  constructor(serverNow: string | number, private readonly perf: () => number = () => performance.now()) {
    this.serverMs = typeof serverNow === 'number' ? serverNow : Date.parse(serverNow);
    this.perfAtSync = perf();
  }
  /** Epoch ms on the server's clock, now. */
  now(): number {
    return this.serverMs + (this.perf() - this.perfAtSync);
  }
  iso(): string {
    return new Date(this.now()).toISOString();
  }
}
