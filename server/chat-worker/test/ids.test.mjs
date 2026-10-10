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
