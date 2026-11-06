#!/usr/bin/env node
// Storybook axe run: build Storybook, serve it, run every story through the test-runner (see .storybook/test-runner.ts).
// Needs Playwright Chromium:  pnpm exec playwright install chromium   (CI does this; verify.sh does not require it).
import { spawn, spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const port = process.env.STORYBOOK_PORT ?? '6016';
const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm';

const run = (args, opts = {}) => spawnSync(pnpm, args, { cwd: root, stdio: 'inherit', ...opts });

if (!process.env.E9X_SKIP_STORYBOOK_BUILD) {
  const build = run(['run', 'build-storybook']);
  if (build.status !== 0) process.exit(build.status ?? 1);
}

const server = spawn(pnpm, ['exec', 'http-server', 'storybook-static', '-p', port, '-s', '-c-1'], { cwd: root, stdio: 'ignore' });
let code;
try {
  const wait = run(['exec', 'wait-on', '-t', '30000', `http-get://127.0.0.1:${port}/index.json`]);
  if (wait.status !== 0) throw new Error('storybook-static did not start');
  const test = run(['exec', 'test-storybook', '--url', `http://127.0.0.1:${port}`, '--index-json', '--maxWorkers=2']);
  code = test.status ?? 1;
} catch (e) {
  console.error(String(e));
  code = 1;
} finally {
  server.kill();
}
process.exit(code);
