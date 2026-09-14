// Builds the Snowball Client Fabric mod so the launcher can bundle it (see package.json "build.extraResources").
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const clientDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'client');
const windows = process.platform === 'win32';
const gradlew = join(clientDir, windows ? 'gradlew.bat' : 'gradlew');
if (!existsSync(gradlew)) {
  console.error(`Gradle wrapper not found at ${gradlew}`);
  process.exit(1);
}
// .bat files need cmd.exe on Windows. Call it directly with a relative ".\gradlew.bat" and cwd set:
// a full path with spaces would be split by cmd, and npm's shell does not search the current folder.
const result = windows
  ? spawnSync('cmd.exe', ['/d', '/c', '.\\gradlew.bat', 'build'], { cwd: clientDir, stdio: 'inherit' })
  : spawnSync('./gradlew', ['build'], { cwd: clientDir, stdio: 'inherit' });
const jar = join(clientDir, 'build', 'libs', 'snowball-client-1.1.0.jar');
if (result.status !== 0 || !existsSync(jar)) {
  console.error('Client build failed; the launcher cannot be packaged without the mod jar.');
  process.exit(result.status || 1);
}
console.log(`Client mod ready: ${jar}`);
