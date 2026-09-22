/**
 * Checks whether Snowball is behind Minecraft.
 *
 * Minecraft ships a release every few months and Snowball has to be told about each one by hand,
 * so the only way this stays current is if something looks. This reads Mojang's own manifest,
 * compares it with the versions this repository builds for, and says what is missing and whether
 * Fabric is ready for it yet - because a release Fabric has not caught up with cannot be supported
 * no matter how much anyone would like it to be.
 *
 * Exits 1 when a supported-worthy release is missing, so CI can fail on it.
 *
 *   node client/tools/check-minecraft-versions.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const MANIFEST = 'https://launchermeta.mojang.com/mc/game/version_manifest_v2.json';
const FABRIC_API = 'https://api.modrinth.com/v2/project/fabric-api/version?game_versions=';

/** What this repository builds for today, read from the build rather than restated here. */
function supported() {
  const settings = readFileSync(join(here, '..', 'settings.gradle.kts'), 'utf8');
  const list = /versions\(([^)]*)\)/.exec(settings)?.[1] ?? '';
  const modern = [...list.matchAll(/"([^"]+)"/g)].map((m) => m[1]);

  // The 1.8.9 build lives beside the Stonecutter one, with its own Gradle project.
  const legacy = /minecraft_version\s*=\s*([^\s]+)/.exec(
    readFileSync(join(here, '..', 'legacy', 'gradle.properties'), 'utf8'),
  )?.[1];

  return { modern, legacy, all: [...(legacy ? [legacy] : []), ...modern] };
}

/** Sorts Minecraft versions: 26.x is newer than 1.21.x despite what a string compare thinks. */
function rank(id) {
  const parts = id.split('.').map((n) => Number.parseInt(n, 10) || 0);
  // The year-based scheme (26.x) supersedes the old 1.x scheme entirely.
  return parts[0] === 1 ? [0, parts[1] ?? 0, parts[2] ?? 0] : [1, parts[0], parts[1] ?? 0];
}

const newer = (a, b) => {
  const [x, y] = [rank(a), rank(b)];
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] > y[i];
  return false;
};

async function fabricReady(version) {
  try {
    const response = await fetch(`${FABRIC_API}${encodeURIComponent(JSON.stringify([version]))}`);
    if (!response.ok) return null;
    const versions = await response.json();
    return versions[0]?.version_number ?? null;
  } catch {
    return null;
  }
}

const have = supported();
const manifest = await fetch(MANIFEST).then((r) => r.json());
const releases = manifest.versions.filter((v) => v.type === 'release');
const latest = manifest.latest.release;

console.log(`Minecraft's latest release is ${latest} (${releases[0].releaseTime.slice(0, 10)}).`);
console.log(`Snowball builds for: ${have.all.join(', ')}.\n`);

const newest = have.modern.reduce((a, b) => (newer(b, a) ? b : a));
const missing = releases
  .map((v) => v.id)
  .filter((id) => newer(id, newest))
  .reverse();

if (!missing.length) {
  console.log('Up to date: nothing has been released that Snowball does not build for.');
  process.exit(0);
}

console.log(`Behind by ${missing.length} release${missing.length === 1 ? '' : 's'}:\n`);
let blocking = 0;
for (const id of missing) {
  const api = await fabricReady(id);
  if (api) {
    blocking++;
    console.log(`  ${id.padEnd(10)} Fabric API ${api} is out - Snowball can support this.`);
  } else {
    console.log(`  ${id.padEnd(10)} no Fabric API yet - nothing to do until there is.`);
  }
}

console.log(
  blocking
    ? '\nAdd the version in client/settings.gradle.kts and give it a block in\n' +
        'stonecutter.properties.toml. Note that the replacement rules in stonecutter.gradle.kts are\n' +
        'written to go from the active version down to 1.21.x, so adding a version above the active\n' +
        'one needs the rules revisited, not just a line in the list.'
    : '\nNothing actionable yet.',
);
process.exit(blocking ? 1 : 0);
