/**
 * Seed and auth helpers whose backend does not exist yet. They throw instead of returning fixtures that pass:
 * a spec that needs them fails loudly until the task that builds the feature fills them in.
 */
export class NotImplementedError extends Error {
  constructor(what: string, until: string) {
    super(`${what} is not implemented until ${until}. Build it there (backend seed endpoint under /api/test/seed/ + this function); do not fake it with a fixture.`);
    this.name = 'NotImplementedError';
  }
}

export function notImplemented(what: string, until: string): never {
  throw new NotImplementedError(what, until);
}
