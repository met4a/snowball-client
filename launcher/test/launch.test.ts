import { EventEmitter } from 'node:events';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { PassThrough } from 'node:stream';
import { describe, expect, it } from 'vitest';
import { normalizeInstanceConfig } from '../src/core/instance/InstanceManager.js';
import type { VersionJson } from '../src/core/minecraft/types.js';
import { buildLaunchPlan, splitArgs } from '../src/core/process/LaunchArguments.js';
import { ProcessManager, type GameExit, type GameLogLine } from '../src/core/process/ProcessManager.js';
import { createLauncherPaths } from '../src/core/util/paths.js';
import { tempDir } from './helpers.js';

const ctx = { os: 'windows' as const, arch: 'x64', osVersion: '10.0', features: { has_custom_resolution: true } };

const modernVersion: VersionJson = {
  id: 'fabric-loader-0.19.5-26.2',
  jar: '26.2',
  mainClass: 'net.fabricmc.loader.impl.launch.knot.KnotClient',
  type: 'release',
  assetIndex: { id: '30', url: 'https://example.invalid/30.json', sha1: 'a'.repeat(40), size: 1 },
  arguments: {
    jvm: ['-Djava.library.path=${natives_directory}', '-cp', '${classpath}', { rules: [{ action: 'allow', os: { name: 'osx' } }], value: '-XstartOnFirstThread' }],
    game: ['--username', '${auth_player_name}', '--accessToken', '${auth_access_token}', '--gameDir', '${game_directory}', { rules: [{ action: 'allow', features: { has_custom_resolution: true } }], value: ['--width', '${resolution_width}', '--height', '${resolution_height}'] }],
  },
  libraries: [
    { name: 'net.fabricmc:fabric-loader:0.19.5', url: 'https://maven.fabricmc.net/' },
    { name: 'org.lwjgl:lwjgl:3.3.6:natives-windows', downloads: { artifact: { path: 'org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6-natives-windows.jar', url: 'https://libraries.minecraft.net/x', sha1: 'b'.repeat(40), size: 1 } }, rules: [{ action: 'allow', os: { name: 'windows' } }] },
    { name: 'org.lwjgl:lwjgl:3.3.6:natives-linux', downloads: { artifact: { path: 'org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6-natives-linux.jar', url: 'https://libraries.minecraft.net/y' } }, rules: [{ action: 'allow', os: { name: 'linux' } }] },
  ],
};

describe('launch arguments', () => {
  it('builds classpath, substitutes variables and honours rules', () => {
    const root = tempDir();
    const paths = createLauncherPaths(root);
    const instance = normalizeInstanceConfig({ id: 'Test', name: 'Test', minecraftVersion: '26.2', loader: 'fabric', memory: { minMb: 1024, maxMb: 4096 }, jvmArgs: ['-XX:+UseZGC'], window: { width: 1600, height: 900 } }, 'Test');
    const plan = buildLaunchPlan({ instance, gameDir: join(root, 'game'), version: modernVersion, paths, account: { name: 'Steve', uuid: '0123-4567', accessToken: 'secret-token', type: 'msa' }, nativesDir: join(root, 'natives'), launcherName: 'snowball', launcherVersion: '1.0.0', ruleContext: ctx });

    expect(plan.args.slice(0, 2)).toEqual(['-Xms1024M', '-Xmx4096M']);
    expect(plan.classpath.some((p) => p.includes('natives-windows'))).toBe(true);
    expect(plan.classpath.some((p) => p.includes('natives-linux'))).toBe(false);
    expect(plan.classpath.at(-1)).toContain(join('versions', '26.2', '26.2.jar'));
    expect(plan.jvmArgs).not.toContain('-XstartOnFirstThread');
    expect(plan.jvmArgs).toContain('-XX:+UseZGC');
    expect(plan.gameArgs).toEqual(expect.arrayContaining(['--username', 'Steve', '--width', '1600', '--height', '900']));
    expect(plan.args).toContain(modernVersion.mainClass);
    expect(plan.args.join(' ')).not.toContain('${');
  });

  it('rejects control characters from instance arguments', () => {
    const root = tempDir();
    const instance = normalizeInstanceConfig({ id: 'T', name: 'T', minecraftVersion: '26.2' }, 'T');
    instance.gameArgs = ['--evil\nline'];
    expect(() => buildLaunchPlan({ instance, gameDir: root, version: modernVersion, paths: createLauncherPaths(root), account: { name: 'a', uuid: 'b', accessToken: 'c', type: 'offline' }, nativesDir: root, launcherName: 'x', launcherVersion: '1', ruleContext: ctx })).toThrow();
  });

  it('splits quoted user arguments', () => {
    expect(splitArgs('-Xss2M "-Dname=a b" -Dx=1')).toEqual(['-Xss2M', '-Dname=a b', '-Dx=1']);
  });
});

class FakeChild extends EventEmitter {
  stdout = new PassThrough();
  stderr = new PassThrough();
  pid = 4242;
  kill() {
    return true;
  }
}

describe('ProcessManager', () => {
  it('captures output, redacts tokens, prevents duplicates and detects crashes', async () => {
    const game = tempDir();
    const child = new FakeChild();
    const spawns: string[] = [];
    const manager = new ProcessManager(((cmd: string) => {
      spawns.push(cmd);
      return child;
    }) as never);
    const lines: GameLogLine[] = [];
    manager.on('log', (l) => lines.push(l));
    const exited = new Promise<GameExit>((resolve) => manager.on('exit', resolve));

    manager.launch('inst', 'java', ['--accessToken', 'abc'], game);
    expect(() => manager.launch('inst', 'java', [], game)).toThrow(/already running/);
    child.stdout.write('[main] Hello --accessToken supersecret\npartial');
    child.stdout.end();
    child.stderr.end();
    mkdirSync(join(game, 'crash-reports'));
    writeFileSync(join(game, 'crash-reports', 'crash-1.txt'), '---- Minecraft Crash Report ----');
    await new Promise((r) => setTimeout(r, 10));
    child.emit('close', 1, null);
    const exit = await exited;

    expect(lines.map((l) => l.line)).toEqual(['[main] Hello --accessToken <redacted>', 'partial']);
    expect(exit.crashed).toBe(true);
    expect(exit.crashReport).toContain('crash-1.txt');
    expect(manager.isRunning('inst')).toBe(false);
  });

  it('treats a user stop as a normal exit', async () => {
    const child = new FakeChild();
    const manager = new ProcessManager((() => child) as never);
    const exited = new Promise<GameExit>((resolve) => manager.on('exit', resolve));
    manager.launch('inst', 'java', [], tempDir());
    expect(manager.kill('inst')).toBe(true);
    child.emit('close', 1, null);
    const exit = await exited;
    expect(exit.killedByUser).toBe(true);
    expect(exit.crashed).toBe(false);
  });
});
