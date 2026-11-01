// The worker only passes on capes and emotes the client ships: both lists must match the client's.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const client = join(here, '..', '..', '..', 'client', 'src', 'main', 'java', 'dev', 'snowballclient', 'client');
const worker = readFileSync(join(here, '..', 'src', 'worker.js'), 'utf8');
const list = (name) => JSON.parse(worker.match(new RegExp(`const ${name} = (\\[[^\\]]*\\])`))[1].replace(/'/g, '"'));

test('cape ids match the client', () => {
  const looks = readFileSync(join(client, 'social', 'Looks.java'), 'utf8');
  const ids = [...looks.match(/CAPES = java\.util\.List\.of\(([^)]*)\)/)[1].matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
  assert.deepEqual(ids, list('CAPES'));
});

test('emote ids match the client', () => {
  const emotes = readFileSync(join(client, 'emote', 'Emote.java'), 'utf8');
  const ids = [...emotes.matchAll(/^\t[A-Z_]+\("([a-z_]+)", "/gm)].map((m) => m[1]);
  assert.deepEqual(ids, list('EMOTES'));
});

test('name colours match the client and the launcher', () => {
  const looks = readFileSync(join(client, 'social', 'Looks.java'), 'utf8');
  const javaColours = [...looks.matchAll(/colours\.put\("([a-z_]+)", 0x([0-9A-F]{6})\)/g)];
  assert.deepEqual(javaColours.map((m) => m[1]), list('COLOURS'));
  const app = readFileSync(join(here, '..', '..', '..', 'launcher', 'src', 'renderer', 'app.ts'), 'utf8');
  const launcher = Object.fromEntries([...app.match(/NAME_COLOURS[^{]*\{([^}]*)\}/)[1].matchAll(/([a-z_]+): '#([0-9a-f]{6})'/g)].map((m) => [m[1], m[2]]));
  assert.deepEqual(launcher, Object.fromEntries(javaColours.map((m) => [m[1], m[2].toLowerCase()])));
});
