// Builds Snowball Client for every supported Minecraft version so the launcher can bundle the jars
// (see package.json "build.extraResources"). The launcher picks the right build per instance.
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const clientDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'client');
const windows = process.platform === 'win32';
const gradlew = join(clientDir, windows ? 'gradlew.bat' : 'gradlew');
if (!existsSync(gradlew)) {
  console.error(`Gradle wrapper not found at ${gradlew}`);
  process.exit(1);
}
// Start from an empty output folder so jars from earlier versions are never bundled into the installer.
const outDir = join(clientDir, 'build', 'libs', 'launcher');
rmSync(outDir, { recursive: true, force: true });
// .bat files need cmd.exe on Windows. Call it directly with a relative ".\gradlew.bat" and cwd set:
// a full path with spaces would be split by cmd, and npm's shell does not search the current folder.
const gradle = (args) =>
  windows
    ? spawnSync('cmd.exe', ['/d', '/c', '.\\gradlew.bat', ...args], { cwd: clientDir, stdio: 'inherit' })
    : spawnSync('./gradlew', args, { cwd: clientDir, stdio: 'inherit' });

// The modern versions come from the Stonecutter build; Minecraft 1.8.9 is its own build next to it.
let result = gradle(['build', 'buildAndCollect']);
if (result.status === 0) result = gradle(['-p', 'legacy', 'build', 'buildAndCollect']);
const jars = existsSync(outDir) ? readdirSync(outDir).filter((f) => f.endsWith('.jar')) : [];
if (result.status !== 0 || jars.length === 0) {
  console.error('Client build failed; the launcher cannot be packaged without the Snowball Client jars.');
  process.exit(result.status || 1);
}

// A mixin selector that Stonecutter failed to rewrite still compiles and still packages - it only
// fails when Minecraft starts. Check the generated sources before the jars go into an installer.
const check = spawnSync(process.execPath, [join(clientDir, 'tools', 'check-generated.mjs')], { stdio: 'inherit' });
if (check.status !== 0) {
  console.error('Refusing to package: the generated sources are not right for every Minecraft version.');
  process.exit(check.status || 1);
}

// Then actually start Minecraft. 1.6.0 shipped a client that crashed on launch while every build
// step above passed, because none of them ran the game: the jars existed and were well-formed, and
// the mod was dead on startup. The gametest loads the client, opens the menus and the HUD editor
// and takes screenshots, and it catches that in about a minute per version.
// SNOWBALL_SKIP_GAMETEST=1 skips it while iterating locally; a release should never skip it.
if (process.env.SNOWBALL_SKIP_GAMETEST === '1') {
  console.warn('SNOWBALL_SKIP_GAMETEST=1: the client was NOT started. Do not publish this build.');
} else {
  for (const version of ['1.21.11', '26.2']) {
    console.log(`Starting Minecraft ${version} to check the client actually loads...`);
    const test = gradle([`:${version}:runClientGameTest`]);
    if (test.status !== 0) {
      console.error(`Refusing to package: the Snowball client failed its gametest on Minecraft ${version}.`);
      process.exit(test.status || 1);
    }
  }
}
console.log(`Snowball Client builds ready in ${outDir}:\n${jars.map((j) => `  ${j}`).join('\n')}`);
