import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The launcher decides which admin tabs to show from permission strings, and the chat server
 * decides whether to allow the action from the same strings. Nothing links the two but spelling.
 *
 * A typo does not fail loudly: the tab simply never appears, or appears and every click is
 * refused. These tests read both sources and check the vocabularies still agree.
 */
const root = join(__dirname, '..', '..');
const worker = readFileSync(join(root, 'server', 'chat-worker', 'src', 'worker.js'), 'utf8');
const renderer = readFileSync(join(root, 'launcher', 'src', 'renderer', 'app.ts'), 'utf8');

/** Every permission the worker grants to some rank, from its PERMISSIONS table. */
function workerPermissions(): Set<string> {
  const block = /const PERMISSIONS = \{([\s\S]*?)\n\};/.exec(worker);
  expect(block, 'worker.js no longer has a PERMISSIONS table').not.toBeNull();
  return new Set([...block![1].matchAll(/'([a-z_.]+)'/g)].map((m) => m[1]));
}

/** Every permission the worker requires for an admin action, from onAdmin's `needed` map. */
function workerRequirements(): Set<string> {
  const block = /const needed = \{([\s\S]*?)\}\[payload\.action\];/.exec(worker);
  expect(block, 'worker.js no longer has a needed map in onAdmin').not.toBeNull();
  return new Set([...block![1].matchAll(/'([a-z_.]+)'/g)].map((m) => m[1]));
}

/** Every permission the launcher gates on, from ADMIN_TABS and can(...) calls. */
function launcherPermissions(): Set<string> {
  const used = new Set<string>();
  for (const m of renderer.matchAll(/can\('([a-z_.]+)'\)/g)) used.add(m[1]);
  const tabs = /const ADMIN_TABS[\s\S]*?\n  \];/.exec(renderer);
  expect(tabs, 'app.ts no longer has an ADMIN_TABS table').not.toBeNull();
  for (const m of tabs![0].matchAll(/'([a-z_.]+\.[a-z_]+)'/g)) used.add(m[1]);
  return used;
}

describe('the launcher and the chat server agree on permission names', () => {
  it('every permission the launcher gates on is one the server grants', () => {
    const granted = workerPermissions();
    const unknown = [...launcherPermissions()].filter((p) => !granted.has(p));
    expect(unknown, `the launcher checks permissions the server never grants: ${unknown.join(', ')}`).toEqual([]);
  });

  it('every permission the server requires is one it also grants', () => {
    const granted = workerPermissions();
    const unknown = [...workerRequirements()].filter((p) => !granted.has(p));
    expect(unknown, `onAdmin requires permissions no rank has: ${unknown.join(', ')}`).toEqual([]);
  });

  it('the admin tabs cover the actions the server exposes', () => {
    // Every admin action the server gates should be reachable from some tab, otherwise the
    // permission exists but nothing in the launcher ever uses it.
    const launcher = launcherPermissions();
    const required = [...workerRequirements()];
    const unreachable = required.filter((p) => !launcher.has(p));
    expect(unreachable, `no part of the launcher gates on: ${unreachable.join(', ')}`).toEqual([]);
  });
});

describe('the rank ladder is the same on both sides', () => {
  it('lists the same eight ranks in the same order', () => {
    const workerRanks = /const RANKS = \[([\s\S]*?)\];/.exec(worker);
    expect(workerRanks).not.toBeNull();
    const fromWorker = [...workerRanks![1].matchAll(/'([a-z_]+)'/g)].map((m) => m[1]);

    const table = /const RANKS: \{[\s\S]*?\n  \];/.exec(renderer);
    expect(table).not.toBeNull();
    const fromLauncher = [...table![0].matchAll(/id: '([a-z_]+)'/g)].map((m) => m[1]);

    expect(fromLauncher).toEqual(fromWorker);
    expect(fromWorker).toHaveLength(8);
    // Order is the ladder: outranks() and permissionsOf() both depend on it.
    expect(fromWorker[0]).toBe('snowball');
    expect(fromWorker[fromWorker.length - 1]).toBe('owner');
  });
});
