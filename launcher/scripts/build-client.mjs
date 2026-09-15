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
const args = ['build', 'buildAndCollect'];
const result = windows
  ? spawnSync('cmd.exe', ['/d', '/c', '.\\gradlew.bat', ...args], { cwd: clientDir, stdio: 'inherit' })
  : spawnSync('./gradlew', args, { cwd: clientDir, stdio: 'inherit' });
const jars = existsSync(outDir) ? readdirSync(outDir).filter((f) => f.endsWith('.jar')) : [];
if (result.status !== 0 || jars.length === 0) {
  console.error('Client build failed; the launcher cannot be packaged without the Snowball Client jars.');
  process.exit(result.status || 1);
}
console.log(`Snowball Client builds ready in ${outDir}:\n${jars.map((j) => `  ${j}`).join('\n')}`);
