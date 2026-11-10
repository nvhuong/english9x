// F12 — Media (spec §16.2): "Upload audio/ảnh/video → URL hết hạn sau thời hạn → người không có quyền không mở được".
//
// Everything here is the REAL stack: the browser uploads through Caddy to the app, the app checks the bytes, runs ffprobe and stores the
// file in MinIO; the signed link it returns is fetched back THROUGH CADDY with the Host the browser sent, which is exactly what MinIO
// verifies in the SigV4 signature (infra/Caddyfile). The compose overlay sets e9x.media.url-ttl to 10s so that expiry can be seen; the default
// (10m) is asserted by MediaAccessIT.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from '../../fixtures';
import { env } from '../../helpers/env';

const FILES = join(__dirname, '..', '..', 'fixtures', 'media');
const bytes = (name: string) => readFileSync(join(FILES, name));
const MP3 = 'tone.mp3';

type Person = { email: string; password: string; userId: string };

async function upload(request: import('@playwright/test').APIRequestContext, token: string, opts: { exam?: string; org?: string | null; name: string; type: string; data: Buffer }) {
  return request.post(`${env.baseURL}/api/cms/media?examCode=${opts.exam ?? 'IELTS'}`, {
    headers: { authorization: `Bearer ${token}`, ...(opts.org ? { 'x-org-context': opts.org } : {}) },
    multipart: { file: { name: opts.name, mimeType: opts.type, buffer: opts.data } },
  });
}

test.describe('F12 media', () => {
  test('F12 an author uploads audio, an image and a video through the screen and opens each through its signed link, served through Caddy', async ({ page, seed, auth, uniqueEmail }) => {
    const author = await seed.verifiedLearner({ email: uniqueEmail('f12-author'), displayName: 'Tác giả F12' });
    await seed.staffGrant({ userId: author.userId, role: 'AUTHOR', examCode: 'IELTS' });
    await auth.loginAs(page, author);
    await page.goto('/cms/media');
    await expect(page.getByRole('heading', { level: 1, name: 'Tệp media' })).toBeVisible();
    await expect(page.getByText('Tối đa 200 MB mỗi tệp.')).toBeVisible();

    const input = page.getByLabel('Tệp media');
    for (const [file, kind, shown] of [[MP3, 'Âm thanh', /Thời lượng 0:0[12]/], ['pixel.png', 'Hình ảnh', null], ['clip.mp4', 'Video', /Thời lượng 0:01/]] as const) {
      await input.setInputFiles(join(FILES, file));
      await expect(page.getByText(`Đã chọn: ${file}`)).toBeVisible();
      await page.getByRole('button', { name: 'Tải lên', exact: true }).click();
      const done = page.getByRole('status').filter({ hasText: `${kind} · ${file}` });
      await expect(done).toBeVisible();
      if (shown) await expect(done).toContainText(shown);
    }

    // each upload is listed; open them one by one through their signed links
    const items = page.locator('li', { hasText: 'Mã tệp' });
    await expect(items).toHaveCount(3);
    const served: Array<{ name: string; src: string; id: string }> = [];
    for (const [file, label, tag] of [['clip.mp4', 'Xem thử clip.mp4', 'video'], ['pixel.png', null, 'img'], [MP3, 'Nghe thử tone.mp3', 'audio']] as const) {
      const row = items.filter({ hasText: file });
      await row.getByRole('button', { name: 'Xem trước' }).click();
      const el = tag === 'img' ? row.getByRole('img', { name: file }) : row.getByLabel(label!);
      await expect(el).toBeVisible();
      const src = (await el.getAttribute('src'))!;
      const id = (await row.locator('code').textContent())!;
      served.push({ name: file, src, id });
    }
    const base = new URL(env.baseURL);
    for (const s of served) {
      const url = new URL(s.src);
      expect(url.origin, 'the link is on the page\'s own origin: no CSP source was added').toBe(base.origin);
      expect(url.pathname).toBe(`/e9x-media/${s.id}`);
      expect(url.searchParams.get('X-Amz-Signature')).toMatch(/^[0-9a-f]{64}$/);
      expect(url.searchParams.get('X-Amz-Expires')).toBe('10');
      const res = await page.request.get(s.src);
      expect(res.status(), `${s.name} through Caddy with a real SigV4 signature`).toBe(200);
      expect(Buffer.compare(await res.body(), bytes(s.name)), `${s.name}: the very bytes that were uploaded`).toBe(0);
      expect(res.headers()['content-security-policy']).toBe("default-src 'none'; sandbox");
      expect(res.headers()['x-content-type-options']).toBe('nosniff');
      expect(res.headers().server).toBeUndefined();
    }
    expect((await (await page.request.get(served[2]!.src)).headers())['content-type']).toBe('audio/mpeg');
    // the audio element really loaded it (a CSP or signature problem would leave it in error)
    const audioState = await page.locator('audio').evaluate((a: HTMLAudioElement) => new Promise<string>((resolve) => {
      if (a.readyState >= 1) return resolve(`ready ${a.duration.toFixed(1)}`);
      a.addEventListener('loadedmetadata', () => resolve(`ready ${a.duration.toFixed(1)}`));
      a.addEventListener('error', () => resolve(`error ${a.error?.code}`));
    }));
    expect(audioState).toMatch(/^ready 1\.[5-6]/);
  });

  test('F12 the link expires after the configured time, and a link cannot be altered', async ({ request, seed, org, uniqueEmail }) => {
    const author = await seed.verifiedLearner({ email: uniqueEmail('f12-expiry') });
    await seed.staffGrant({ userId: author.userId, role: 'AUTHOR', examCode: 'IELTS' });
    const { token } = await org.apiLogin(author);
    const up = await upload(request, token, { name: MP3, type: 'audio/mpeg', data: bytes(MP3) });
    expect(up.status(), await up.text()).toBe(200);
    const media = await up.json();
    expect(media.kind).toBe('AUDIO');
    expect(media.durationMs).toBeGreaterThan(1400);
    expect(media.durationMs).toBeLessThan(1700);

    const asked = Date.now();
    const link = (await org.api(token, 'GET', `/api/media/${media.id}/url`)).body as { url: string; expiresAt: string };
    expect(new Date(link.expiresAt).getTime() - asked).toBeLessThan(11_000);
    const first = await request.get(link.url);
    expect(first.status()).toBe(200);
    expect(Buffer.compare(await first.body(), bytes(MP3))).toBe(0);

    // altered: another key under the same signature, a longer life, no signature at all
    const u = new URL(link.url);
    const longer = new URL(link.url);
    longer.searchParams.set('X-Amz-Expires', '86400');
    expect((await request.get(longer.toString())).status()).toBe(403);
    const unsigned = await request.get(`${u.origin}${u.pathname}`);
    expect(Buffer.compare(await unsigned.body(), bytes(MP3)), 'no signature, no bytes').not.toBe(0);
    expect(unsigned.headers()['content-type'] ?? '').not.toContain('audio');

    // expired: wait for the clock, not for a fixed time
    await expect.poll(async () => (await request.get(link.url)).status(), { timeout: 30_000, intervals: [500] }).toBe(403);
    const late = await request.get(link.url);
    expect(await late.text()).toContain('expired');
    // a new link for the same file works again
    const fresh = (await org.api(token, 'GET', `/api/media/${media.id}/url`)).body as { url: string };
    expect((await request.get(fresh.url)).status()).toBe(200);
  });

  test('F12 whoever may not open a file cannot get a link: learners, another exam\'s author, another organisation, nobody signed in', async ({ request, seed, org, uniqueEmail }) => {
    const owner = await seed.organization({ type: 'CENTER' });
    const other = await seed.organization({ type: 'CENTER' });
    const author = await seed.verifiedLearner({ email: uniqueEmail('f12-own-author') });
    const reviewer = await seed.verifiedLearner({ email: uniqueEmail('f12-reviewer') });
    const toeicAuthor = await seed.verifiedLearner({ email: uniqueEmail('f12-toeic') });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f12-learner') });
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f12-stranger') });
    await seed.member({ orgId: owner.orgId, userId: author.userId, roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] });
    await seed.member({ orgId: owner.orgId, userId: reviewer.userId, roles: [{ role: 'REVIEWER', examCode: 'IELTS' }] });
    await seed.member({ orgId: owner.orgId, userId: toeicAuthor.userId, roles: [{ role: 'AUTHOR', examCode: 'TOEIC' }] });
    await seed.member({ orgId: owner.orgId, userId: learner.userId, roles: [{ role: 'LEARNER' }] });
    await seed.member({ orgId: other.orgId, userId: stranger.userId, roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] });

    const tokenOf = async (p: Person) => (await org.apiLogin(p)).token;
    const up = await upload(request, await tokenOf(author), { org: owner.orgId, name: 'part1.mp3', type: 'audio/mpeg', data: bytes(MP3) });
    expect(up.status(), await up.text()).toBe(200);
    const id = (await up.json()).id as string;

    const open = async (p: Person, ctx: string | null) => org.api(await tokenOf(p), 'GET', `/api/media/${id}/url`, { org: ctx });
    expect((await open(author, owner.orgId)).status, 'the exam\'s author').toBe(200);
    expect((await open(reviewer, owner.orgId)).status, 'its reviewer, who has to listen').toBe(200);
    for (const [who, p, ctx] of [
      ['a learner of the organisation (until P3.1 every learner is refused)', learner, owner.orgId],
      ['the author of another exam in the same organisation', toeicAuthor, owner.orgId],
      ['an author of another organisation, in that organisation', stranger, other.orgId],
      ['the owner\'s author in the personal context', author, null],
    ] as const) {
      const r = await open(p, ctx);
      expect(r.status, who).toBe(404);
      expect(r.text, who).not.toContain('X-Amz');
    }
    // the stranger cannot name the owner's context either
    expect((await open(stranger, owner.orgId)).status).toBe(403);
    expect((await org.api('not-a-token', 'GET', `/api/media/${id}/url`)).status).toBe(401);

    // and the learner, the stranger and the TOEIC author cannot upload into the owner's organisation
    for (const p of [learner, toeicAuthor]) {
      expect((await upload(request, await tokenOf(p), { org: owner.orgId, name: 'x.mp3', type: 'audio/mpeg', data: bytes(MP3) })).status()).toBe(403);
    }
    expect((await upload(request, await tokenOf(stranger), { org: owner.orgId, name: 'x.mp3', type: 'audio/mpeg', data: bytes(MP3) })).status()).toBe(403);
  });

  test('F12 the route to the object store is not an open proxy', async ({ request, seed, org, uniqueEmail }) => {
    const author = await seed.verifiedLearner({ email: uniqueEmail('f12-proxy') });
    await seed.staffGrant({ userId: author.userId, role: 'AUTHOR', examCode: 'IELTS' });
    const { token } = await org.apiLogin(author);
    const id = (await (await upload(request, token, { name: MP3, type: 'audio/mpeg', data: bytes(MP3) })).json()).id as string;
    const link = new URL((await org.api(token, 'GET', `/api/media/${id}/url`)).body.url as string);
    const sig = link.search;
    const looksLikeMinio = (t: string) => t.includes('<?xml') || t.includes('<Error>') || t.includes('minio');
    for (const [what, path, init] of [
      ['the bucket listing', `/e9x-media/${sig}`, {}],
      ['a nested key', `/e9x-media/a/${id}${sig}`, {}],
      ['another bucket', `/other/${id}${sig}`, {}],
      ['the MinIO admin API', `/minio/admin/v3/info${sig}`, {}],
      ['the MinIO health endpoint', `/minio/health/live${sig}`, {}],
      ['the MinIO console', `/minio/console/${sig}`, {}],
    ] as const) {
      const r = await request.get(`${link.origin}${path}`, init);
      expect(looksLikeMinio(await r.text()), what).toBe(false);
    }
    // the signed GET link used for another method is never forwarded
    for (const method of ['PUT', 'DELETE', 'POST'] as const) {
      const r = await request.fetch(link.toString(), { method, data: 'x' });
      expect(looksLikeMinio(await r.text()), `${method} with a valid GET link`).toBe(false);
      // and the object is still there
      expect((await request.get(link.toString())).status()).toBe(200);
    }
  });

  test('F12 the screen explains a file the server refuses: a PNG named .mp3, a disallowed type, a file that cannot be read', async ({ page, request, seed, auth, org, uniqueEmail }) => {
    const author = await seed.verifiedLearner({ email: uniqueEmail('f12-refused') });
    await seed.staffGrant({ userId: author.userId, role: 'AUTHOR', examCode: 'IELTS' });
    await auth.loginAs(page, author);
    await page.goto('/cms/media');
    await expect(page.getByText('Tối đa 200 MB mỗi tệp.')).toBeVisible();
    const input = page.getByLabel('Tệp media');
    const send = () => page.getByRole('button', { name: 'Tải lên', exact: true });

    // the name says mp3, the bytes are a PNG: the screen accepts the name, the server reads the bytes and refuses
    await input.setInputFiles({ name: 'answer.mp3', mimeType: 'audio/mpeg', buffer: bytes('pixel.png') });
    await send().click();
    await expect(page.getByRole('alert')).toContainText('Đuôi tệp không khớp với nội dung thật của tệp.');
    // text dressed as audio
    await input.setInputFiles({ name: 'lesson.mp3', mimeType: 'audio/mpeg', buffer: Buffer.from('#!/bin/sh\necho hi\n') });
    await send().click();
    await expect(page.getByRole('alert')).toContainText('Loại tệp này không được phép.');
    // an mp3 header with nothing behind it
    await input.setInputFiles({ name: 'broken.mp3', mimeType: 'audio/mpeg', buffer: Buffer.concat([Buffer.from([0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 0]), Buffer.alloc(2048)]) });
    await send().click();
    await expect(page.getByRole('alert')).toContainText('Không đọc được nội dung âm thanh hoặc video của tệp');
    // a type the screen already knows is not allowed: no request is sent
    let requests = 0;
    page.on('request', (r) => { if (r.url().includes('/api/cms/media') && r.method() === 'POST') requests += 1; });
    await input.setInputFiles({ name: 'run.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('MZ') });
    await expect(page.getByRole('alert')).toContainText('Đuôi tệp .exe không nằm trong danh sách được phép.');
    expect(requests).toBe(0);
    // the same refusals over HTTP carry the problem types the screen reads
    const { token } = await org.apiLogin(author);
    const spoof = await upload(request, token, { name: 'answer.mp3', type: 'audio/mpeg', data: bytes('pixel.png') });
    expect(spoof.status()).toBe(415);
    expect((await spoof.json()).type).toBe('urn:e9x:problem:media-extension-mismatch');
  });

  test('F12 a person with no author role has no upload screen worth the name', async ({ page, seed, auth, uniqueEmail }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f12-nobody') });
    await auth.loginAs(page, learner);
    await page.goto('/cms/media');
    await expect(page.getByRole('heading', { level: 1, name: 'Bạn chưa có quyền xem trang này' })).toBeVisible();
    await expect(page.getByLabel('Tệp media')).toHaveCount(0);
  });
});
