// Starts Minecraft for real, through the same code the launcher runs when Play is pressed, and
// reports whether the game window came up. It exists for machines nobody can sit at - the macOS
// runners in CI - and it is the only check that proves a platform can actually play, rather than
// just build.
//
//   npm run build && node scripts/smoke-launch.mjs 1.21.11 fabric
//
// The account is an offline stand-in: nothing here signs in to Microsoft or joins a server.
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const { Launcher } = await import(new URL('../dist/core/Launcher.js', import.meta.url));

const [minecraftVersion = '1.21.11', loader = 'fabric'] = process.argv.slice(2);
const timeoutMs = Number(process.env.SMOKE_TIMEOUT_MS ?? 15 * 60_000);
const root = process.env.SMOKE_ROOT || mkdtempSync(join(tmpdir(), 'snowball-smoke-'));
const cipher = { isAvailable: () => false, encrypt: (s) => s, decrypt: (s) => s };

const launcher = await Launcher.create({
  root,
  cipher,
  clientBuildDirs: [join(here, '..', '..', 'client', 'build', 'libs', 'launcher')],
  launcherVersion: 'smoke',
  consoleLogs: false,
});
const name = 'SnowballCI';
Object.defineProperty(launcher, 'auth', {
  value: {
    list: () => [{ id: 'smoke', type: 'offline', name }],
    session: async () => ({ name, uuid: '0'.repeat(31) + '1', accessToken: '0', type: 'offline' }),
  },
});

const instance = await launcher.createInstance({ name: `Smoke ${minecraftVersion}`, minecraftVersion, loader });
console.log(`[smoke] ${process.platform}/${process.arch}: Minecraft ${minecraftVersion} (${loader}), instance ${instance.id}, data in ${root}`);
const support = launcher.snowballSupport(minecraftVersion, loader);
console.log(`[smoke] Snowball Client for this version: ${support.supported ? support.version : 'not available'}`);

const tail = [];
launcher.processes.on('log', (entry) => {
  const line = typeof entry === 'string' ? entry : entry?.message ?? JSON.stringify(entry);
  tail.push(line);
  if (tail.length > 80) tail.shift();
});
launcher.activity?.on?.('entry', (e) => console.log(`[launcher] ${e.level ?? ''} ${e.message ?? ''}`));

const outcome = new Promise((resolve) => {
  const timer = setTimeout(() => resolve({ ok: false, why: `no game window within ${timeoutMs / 1000}s` }), timeoutMs);
  launcher.on('phase', (e) => {
    console.log(`[smoke] phase: ${e.phase ?? 'none'}`);
    if (e.phase === 'running') {
      clearTimeout(timer);
      resolve({ ok: true });
    }
  });
  launcher.processes.on('exit', (exit) => {
    clearTimeout(timer);
    resolve({ ok: false, why: `the game exited first (code ${exit.code ?? exit.exitCode ?? '?'})` });
  });
});

const started = Date.now();
try {
  await launcher.launch(instance.id);
} catch (err) {
  console.error(`[smoke] launch failed: ${err?.stack ?? err}`);
  process.exit(1);
}
const result = await outcome;
const seconds = ((Date.now() - started) / 1000).toFixed(1);
if (result.ok) {
  // Let it run a little, so a crash right after the window opens is caught too.
  const crashed = await new Promise((resolve) => {
    const t = setTimeout(() => resolve(false), 20_000);
    launcher.processes.once('exit', () => {
      clearTimeout(t);
      resolve(true);
    });
  });
  console.log(tail.slice(-25).join('\n'));
  launcher.stop(instance.id);
  if (crashed) {
    console.error(`[smoke] FAILED: the window came up after ${seconds}s but the game closed within 20 seconds`);
    process.exit(1);
  }
  console.log(`[smoke] OK: Minecraft ${minecraftVersion} opened its window after ${seconds}s and kept running`);
  setTimeout(() => process.exit(0), 3_000);
} else {
  console.log(tail.join('\n'));
  launcher.stop(instance.id);
  console.error(`[smoke] FAILED after ${seconds}s: ${result.why}`);
  setTimeout(() => process.exit(1), 3_000);
}
