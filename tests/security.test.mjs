import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, symlink, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { execFileSync } from 'node:child_process';
import vm from 'node:vm';
import { createStaticServer } from '../server/index.js';
import { collect, exportablePath } from '../scripts/export-zip.mjs';

test('malformed requests cannot stop the server or expose sibling directories/symlinks', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gamestock-http-test-'));
  const root = join(dir, 'dist');
  await mkdir(root);
  await mkdir(join(dir, 'dist-private'));
  await writeFile(join(root, 'index.html'), 'APP');
  await writeFile(join(dir, 'dist-private', 'private.txt'), 'PRIVATE');
  await symlink(join(dir, 'dist-private', 'private.txt'), join(root, 'linked.txt'));
  const server = createStaticServer(root).listen(0, '127.0.0.1');
  try {
    await once(server, 'listening');
    const base = `http://127.0.0.1:${server.address().port}`;
    for (const url of ['/%', '/%E0%A4%A', '/%GG']) assert.equal((await fetch(base + url)).status, 400);
    for (const url of ['/..%2fdist-private/private.txt', '/linked.txt']) assert.notEqual(await (await fetch(base + url)).text(), 'PRIVATE');
    assert.equal(await (await fetch(base + '/trade/UMA')).text(), 'APP');
    assert.equal((await fetch(base + '/healthz')).status, 200);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    await rm(dir, { recursive: true, force: true });
  }
});

test('export excludes secrets, untracked files and symlinks before reading any content', async () => {
  for (const name of ['.env', '.env.local', 'backend/secrets/firebase-admin.json', '.aws/config', 'logs/debug.log', 'private.key', 'serviceAccount.json', '../escape'])
    assert.equal(exportablePath(name), false, name);
  const dir = await mkdtemp(join(tmpdir(), 'gamestock-export-test-'));
  try {
    execFileSync('git', ['init', '-q', dir]);
    await writeFile(join(dir, 'source.ts'), 'export const x = 1;');
    await writeFile(join(dir, 'local.json'), 'untracked');
    await symlink('local.json', join(dir, 'linked.json'));
    execFileSync('git', ['-C', dir, 'add', 'source.ts', 'linked.json']);
    assert.deepEqual(collect(dir), [join(dir, 'source.ts')]);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('native auth bridge rejects foreign origins even if navigation races injection', async () => {
  const source = await readFile(new URL('../mobile/src/web-origin.js', import.meta.url), 'utf8');
  const { isTrustedAppUrl, authInjection } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  const app = 'https://app.example';
  assert.equal(isTrustedAppUrl(app + '/trade/UMA', app), true);
  for (const url of ['https://app.example.evil.test', 'http://app.example', 'https://app.example@evil.test', 'javascript:alert(1)', 'about:blank'])
    assert.equal(isTrustedAppUrl(url, app), false, url);
  for (const origin of [app, 'https://evil.test']) {
    const events = [];
    const window = { location: { origin }, dispatchEvent: event => events.push(event) };
    window.top = window;
    vm.runInNewContext(authInjection({ customToken: 'TEST' }, app), { window, CustomEvent: class { constructor(type, init) { Object.assign(this, { type, ...init }); } } });
    assert.equal(events.length, origin === app ? 1 : 0);
  }
});
