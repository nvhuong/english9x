// @vitest-environment node
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, describe, expect, it } from 'vitest';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const committed = join(root, 'src', 'api', 'generated');
const scratch = join(root, `.tmp-api-gen-${process.pid}`);

function generate(out: string) {
  execFileSync(process.execPath, [join(root, 'scripts', 'gen-api.mjs'), '--out', out], { cwd: root, stdio: 'pipe' });
}

function files(dir: string): string[] {
  return readdirSync(dir).sort();
}

afterAll(() => rmSync(scratch, { recursive: true, force: true }));

describe('ApiClientGenTest', () => {
  it('generates a client that type-checks and has a typed call for the ping endpoint', () => {
    const out = join(scratch, 'generated');
    generate(out);
    expect(files(out)).toEqual(['client.ts', 'schema.ts']);

    // A consumer of the generated client, compiled under the strictest settings the app uses.
    writeFileSync(
      join(scratch, 'consumer.ts'),
      `import { createApiClient, type components } from './generated/client';

export async function ping(): Promise<string | undefined> {
  const client = createApiClient('http://localhost:8080');
  const { data, error } = await client.GET('/api/v1/platform/ping');
  const status: string | undefined = data?.status;                 // typed response body
  const problem: components['schemas']['Problem'] | undefined = error; // typed RFC 7807 error body
  void problem;
  return status;
}

export async function unknownPathIsRejectedByTheCompiler() {
  const client = createApiClient();
  // @ts-expect-error -- not an endpoint of the API
  return client.GET('/api/v1/does-not-exist');
}
`,
    );
    const tsc = join(root, 'node_modules', 'typescript', 'bin', 'tsc');
    execFileSync(
      process.execPath,
      [tsc, '--noEmit', '--strict', '--noUncheckedIndexedAccess', '--target', 'ES2022', '--module', 'ESNext',
        '--moduleResolution', 'bundler', '--skipLibCheck', '--lib', 'ES2022,DOM', join(scratch, 'consumer.ts')],
      { cwd: root, stdio: 'pipe' },
    );
  }, 120_000);

  it('the committed client is what gen-api produces from the committed OpenAPI snapshot', () => {
    expect(existsSync(committed)).toBe(true);
    const out = join(scratch, 'fresh');
    mkdirSync(scratch, { recursive: true });
    generate(out);
    for (const f of files(out)) {
      expect(readFileSync(join(committed, f), 'utf8'), `${f} is stale; run pnpm run gen-api`).toBe(
        readFileSync(join(out, f), 'utf8'),
      );
    }
    expect(files(committed)).toEqual(files(out));
  }, 60_000);
});
