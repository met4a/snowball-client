import { existsSync, mkdirSync, readFileSync, statSync, utimesSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { AuthManager, offlineUuid, type SecretCipher } from '../src/core/auth/AuthManager.js';
import { DownloadManager } from '../src/core/download/DownloadManager.js';
import { VerifiedFiles } from '../src/core/download/VerifiedFiles.js';
import { InstanceManager, normalizeInstanceConfig } from '../src/core/instance/InstanceManager.js';
import { parseJavaMajor, parseJavaProperties, recommendMemory, validateMemory, JavaManager } from '../src/core/java/JavaManager.js';
import { LogSink, Logger, redactData } from '../src/core/logging/Logger.js';
import { mergeVersionJson, resolveLibraries } from '../src/core/minecraft/VersionManager.js';
import { mavenPath, rulesAllow } from '../src/core/minecraft/rules.js';
import { CONFLICT_RULES, getProfile, resolveProfile } from '../src/core/performance/PerformanceProfiles.js';
import { normalizeSettings, SettingsStore } from '../src/core/settings/LauncherSettings.js';
import { sha1File } from '../src/core/util/fsutil.js';
import { assertSafeRelativePath, safeJoin, sanitizeFileName } from '../src/core/util/paths.js';
import { ZipReader } from '../src/core/util/zip.js';
import { makeZip, tempDir } from './helpers.js';

describe('paths', () => {
  it('prevents traversal and sanitises names', () => {
    const base = tempDir();
    expect(safeJoin(base, 'a', 'b.txt')).toBe(join(base, 'a', 'b.txt'));
    expect(() => safeJoin(base, '..', 'x')).toThrow();
    expect(() => safeJoin(base, 'C:\\Windows')).toThrow();
    expect(() => assertSafeRelativePath('../../evil.jar')).toThrow();
    expect(sanitizeFileName('My: "Survival" World?')).toBe('My_ _Survival_ World_');
    expect(sanitizeFileName('CON')).toBe('_CON');
    expect(sanitizeFileName('   ')).toBe('unnamed');
  });
});

describe('instances', () => {
  it('creates isolated layout, loads, updates, clones and deletes', async () => {
    const dir = tempDir();
    const manager = new InstanceManager(dir);
    const created = await manager.create({ name: 'Survival', minecraftVersion: '26.2', loader: 'fabric', loaderVersion: '0.19.5' });
    expect(existsSync(join(dir, 'Survival', 'minecraft', 'mods'))).toBe(true);
    expect(existsSync(join(dir, 'Survival', 'minecraft', 'saves'))).toBe(true);
    const second = await manager.create({ name: 'Survival', minecraftVersion: '1.21.4' });
    expect(second.id).toBe('Survival (2)');

    const updated = await manager.update(created.id, (c) => {
      c.memory = { minMb: 2048, maxMb: 6144 };
    });
    expect(updated.memory.maxMb).toBe(6144);
    const clone = await manager.clone(created.id, 'Survival Copy');
    expect(clone.loader).toBe('fabric');
    await manager.delete(second.id);
    expect((await manager.list()).map((i) => i.config.id).sort()).toEqual(['Survival', 'Survival Copy']);
  });

  it('keeps malformed instances visible without crashing', async () => {
    const dir = tempDir();
    mkdirSync(join(dir, 'Broken'));
    writeFileSync(join(dir, 'Broken', 'instance.json'), '{ nope');
    const list = await new InstanceManager(dir).list();
    expect(list[0].error).toMatch(/malformed/);
  });

  it('normalises hostile config values', () => {
    const c = normalizeInstanceConfig({ minecraftVersion: '26.2', loader: 'evil', memory: { minMb: 99999999, maxMb: -5 }, jvmArgs: ['-Xss1M', 'bad\narg', 3] }, 'X');
    expect(c.loader).toBe('vanilla');
    expect(c.memory.maxMb).toBe(512);
    expect(c.memory.minMb).toBeLessThanOrEqual(c.memory.maxMb);
    expect(c.jvmArgs).toEqual(['-Xss1M']);
    expect(() => normalizeInstanceConfig({ minecraftVersion: '../../x' }, 'X')).toThrow();
  });
});

describe('java', () => {
  it('parses versions and properties', () => {
    expect(parseJavaMajor('1.8.0_491')).toBe(8);
    expect(parseJavaMajor('21.0.12')).toBe(21);
    expect(parseJavaMajor('25-ea')).toBe(25);
    const props = parseJavaProperties('    java.version = 21.0.12\n    java.vendor = Eclipse Adoptium\n    os.arch = amd64\n    sun.arch.data.model = 64\n');
    expect(props).toMatchObject({ version: '21.0.12', vendor: 'Eclipse Adoptium', arch: 'amd64', dataModel: '64' });
  });

  it('recommends and validates safe memory', () => {
    const gb16 = 16 * 1024 ** 3;
    const rec = recommendMemory(gb16);
    expect(rec.recommendedMaxMb).toBe(4096);
    expect(validateMemory(1024, 4096, gb16).ok).toBe(true);
    expect(validateMemory(1024, 15000, gb16).ok).toBe(false);
    expect(validateMemory(8192, 4096, gb16).errors[0]).toMatch(/Minimum/);
  });

  it('validates the selected java against the required version', async () => {
    const dir = tempDir();
    const exe = join(dir, 'java.exe');
    writeFileSync(exe, '');
    const manager = new JavaManager(dir, undefined, async () => 'java.version = 17.0.2\nsun.arch.data.model = 64\nos.arch = amd64');
    expect((await manager.validateFor(exe, 21)).ok).toBe(false);
    expect((await manager.validateFor(exe, 17)).ok).toBe(true);
    expect((await manager.validateFor(join(dir, 'missing.exe'), 17)).ok).toBe(false);
  });
});

describe('version metadata', () => {
  it('merges loader profiles onto vanilla and evaluates rules', () => {
    const vanilla = { id: '26.2', mainClass: 'net.minecraft.client.main.Main', libraries: [{ name: 'org.ow2.asm:asm:9.6' }, { name: 'com.mojang:brigadier:1.0' }], arguments: { game: ['--version', '${version_name}'], jvm: [] } };
    const fabric = { id: 'fabric-26.2', inheritsFrom: '26.2', mainClass: 'Knot', libraries: [{ name: 'org.ow2.asm:asm:9.9', url: 'https://maven.fabricmc.net/' }], arguments: { game: [], jvm: ['-DFabricMcEmu=x'] } };
    const merged = mergeVersionJson(vanilla, fabric);
    expect(merged.mainClass).toBe('Knot');
    expect(merged.jar).toBe('26.2');
    expect(merged.libraries.map((l) => l.name)).toEqual(['org.ow2.asm:asm:9.9', 'com.mojang:brigadier:1.0']);
    expect(mavenPath('org.ow2.asm:asm:9.9')).toBe('org/ow2/asm/asm/9.9/asm-9.9.jar');
    const libs = resolveLibraries(merged, '/libs');
    expect(libs[0].task?.url).toBe('https://maven.fabricmc.net/org/ow2/asm/asm/9.9/asm-9.9.jar');

    const ctx = { os: 'osx' as const, arch: 'arm64', osVersion: '14', features: {} };
    expect(rulesAllow([{ action: 'allow' }, { action: 'disallow', os: { name: 'osx' } }], ctx)).toBe(false);
    expect(rulesAllow([{ action: 'allow', os: { name: 'osx' } }], ctx)).toBe(true);
    expect(rulesAllow(undefined, ctx)).toBe(true);
  });

  it('keeps the natives of a library a loader profile replaces (Legacy Fabric LWJGL 2)', () => {
    const natives = { windows: 'natives-windows', linux: 'natives-linux', osx: 'natives-osx' };
    const vanilla = {
      id: '1.8.9',
      mainClass: 'net.minecraft.client.main.Main',
      libraries: [
        { name: 'org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209' },
        {
          name: 'org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209',
          natives,
          extract: { exclude: ['META-INF/'] },
          rules: [{ action: 'allow' as const }, { action: 'disallow' as const, os: { name: 'osx' } }],
          downloads: { classifiers: { 'natives-windows': { path: 'org/lwjgl/lwjgl/lwjgl-platform/2.9.4-nightly-20150209/lwjgl-platform-2.9.4-nightly-20150209-natives-windows.jar', url: 'https://libraries.minecraft.net/natives.jar', sha1: 'b'.repeat(40), size: 1 } } },
        },
      ],
    };
    const legacyFabric = {
      id: 'fabric-loader-0.19.3-1.8.9',
      inheritsFrom: '1.8.9',
      mainClass: 'net.fabricmc.loader.impl.launch.knot.KnotClient',
      libraries: [
        { name: 'org.lwjgl.lwjgl:lwjgl:2.9.4+legacyfabric.17', url: 'https://maven.legacyfabric.net/' },
        { name: 'org.lwjgl.lwjgl:lwjgl-platform:2.9.4+legacyfabric.17', url: 'https://maven.legacyfabric.net/' },
      ],
    };
    const windows = { os: 'windows' as const, arch: 'x64', osVersion: '10.0', features: {} };
    const libs = resolveLibraries(mergeVersionJson(vanilla, legacyFabric), '/libs', windows);
    expect(libs.map((l) => [l.name, l.isNative, l.task?.url])).toEqual([
      ['org.lwjgl.lwjgl:lwjgl:2.9.4+legacyfabric.17', false, 'https://maven.legacyfabric.net/org/lwjgl/lwjgl/lwjgl/2.9.4+legacyfabric.17/lwjgl-2.9.4+legacyfabric.17.jar'],
      ['org.lwjgl.lwjgl:lwjgl-platform:2.9.4+legacyfabric.17:natives-windows', true, 'https://maven.legacyfabric.net/org/lwjgl/lwjgl/lwjgl-platform/2.9.4+legacyfabric.17/lwjgl-platform-2.9.4+legacyfabric.17-natives-windows.jar'],
    ]);
    expect(libs[1].extractExclude).toEqual(['META-INF/']);
  });
});

describe('downloads', () => {
  it('retries on checksum mismatch and verifies the final file', async () => {
    const dir = tempDir();
    let calls = 0;
    const good = Buffer.from('hello world');
    const manager = new DownloadManager({
      retries: 2,
      retryBaseDelayMs: 1,
      fetchImpl: async () => {
        calls++;
        return new Response(calls === 1 ? Buffer.from('corrupt!') : good, { status: 200 });
      },
    });
    const dest = join(dir, 'a', 'file.bin');
    await manager.download({ url: 'https://example.invalid/file', dest, sha1: '2aae6c35c94fcfb415dbe95f408b9ce91ee846ed' });
    expect(calls).toBe(2);
    expect(readFileSync(dest, 'utf8')).toBe('hello world');
    expect(existsSync(dest + '.part')).toBe(false);
    expect(await manager.download({ url: 'https://example.invalid/file', dest, sha1: await sha1File(dest) })).toBe('cached');
  });

  it('hashes a game file once and then trusts it until it changes', async () => {
    const dir = tempDir();
    const dest = join(dir, 'asset.bin');
    const sha1 = '2aae6c35c94fcfb415dbe95f408b9ce91ee846ed';
    writeFileSync(dest, 'hello world');
    const verified = new VerifiedFiles(join(dir, 'verified.json'));
    const manager = new DownloadManager({ retries: 0, verified, fetchImpl: async () => new Response(Buffer.from('hello world'), { status: 200 }) });
    const task = { url: 'https://example.invalid/a', dest, sha1 };

    expect(await manager.download(task)).toBe('cached');
    await verified.save();

    // Taken as verified without hashing again: the content changed but its size and timestamp did not.
    const before = statSync(dest);
    writeFileSync(dest, 'HELLO WORLD');
    utimesSync(dest, before.atime, before.mtime);
    expect(await manager.download(task)).toBe('cached');

    // A different size is noticed, so the file is fetched again.
    writeFileSync(dest, 'a different length');
    expect(await manager.download(task)).toBe('downloaded');
    expect(readFileSync(dest, 'utf8')).toBe('hello world');

    // Records are kept across launcher restarts, and a file written since is checked again.
    const reloaded = new VerifiedFiles(join(dir, 'verified.json'));
    await reloaded.load();
    expect(await reloaded.isVerified(dest, sha1)).toBe(false);
    await reloaded.record(dest, sha1);
    expect(await reloaded.isVerified(dest, sha1)).toBe(true);
    expect(await reloaded.isVerified(dest, 'f'.repeat(40))).toBe(false);
  });

  it('aggregates failures and refuses plain HTTP', async () => {
    const manager = new DownloadManager({ retries: 0, fetchImpl: async () => new Response('nope', { status: 404 }) });
    await expect(manager.downloadAll([{ url: 'https://example.invalid/a', dest: join(tempDir(), 'a') }])).rejects.toThrow(/1 download\(s\) failed/);
    await expect(manager.download({ url: 'http://example.invalid/a', dest: join(tempDir(), 'a') })).rejects.toThrow(/non-HTTPS/);
  });
});

describe('zip', () => {
  it('reads entries and blocks traversal on extract', async () => {
    const zip = ZipReader.fromBuffer(makeZip({ 'fabric.mod.json': '{"id":"x"}', '../evil.txt': 'x' }));
    expect(zip.readText('fabric.mod.json')).toBe('{"id":"x"}');
    await expect(zip.extractAll(tempDir())).rejects.toThrow();
    expect(() => ZipReader.fromBuffer(Buffer.from('not a zip'))).toThrow();
  });
});

describe('logging', () => {
  it('redacts secrets in text and structured data', () => {
    const sink = new LogSink();
    sink.consoleOutput = false;
    const records: string[] = [];
    sink.subscribe((r) => records.push(JSON.stringify(r)));
    new Logger('test', sink).info('launch --accessToken abc.def', { refresh_token: 'xyz', nested: { password: 'p' }, ok: 'fine' });
    expect(records[0]).not.toContain('abc.def');
    expect(records[0]).not.toContain('xyz');
    expect(records[0]).toContain('fine');
    expect(redactData({ Authorization: 'Bearer 123' })).toEqual({ Authorization: '<redacted>' });
  });
});

describe('settings', () => {
  it('normalises and persists settings', async () => {
    expect(normalizeSettings({ downloads: { concurrency: 999 }, accounts: { microsoftClientId: 'not valid!' } })).toMatchObject({ downloads: { concurrency: 32 }, accounts: { microsoftClientId: '' } });
    const file = join(tempDir(), 'settings.json');
    writeFileSync(file, '{{{');
    const store = new SettingsStore(file);
    expect((await store.load()).downloads.concurrency).toBe(8);
    await store.update((s) => {
      s.game.closeLauncherOnLaunch = true;
    });
    expect(JSON.parse(readFileSync(file, 'utf8')).game.closeLauncherOnLaunch).toBe(true);
  });
});

describe('auth', () => {
  const cipher: SecretCipher = { isAvailable: () => true, encrypt: (s) => `enc:${Buffer.from(s).toString('base64')}`, decrypt: (s) => Buffer.from(s.slice(4), 'base64').toString() };

  it('computes vanilla offline UUIDs', () => {
    expect(offlineUuid('Notch')).toBe('b50ad385-829d-3141-a216-7e7d7539ba7f');
  });

  it('requires a Microsoft account before offline profiles', async () => {
    const auth = new AuthManager(join(tempDir(), 'accounts.json'), cipher, () => '', undefined, false);
    await auth.load();
    await expect(auth.addOffline('Steve')).rejects.toThrow(/Microsoft account/);
    await expect(auth.signInWithMicrosoft(async () => '')).rejects.toThrow(/client id/);
  });

  it('rejects cancelled or tampered sign-in redirects', async () => {
    const auth = new AuthManager(join(tempDir(), 'accounts.json'), cipher, () => '11111111-2222-3333-4444-555555555555', undefined, false);
    await auth.load();
    await expect(auth.signInWithMicrosoft(async (r) => `${r.redirectUri}?error=access_denied&state=${new URL(r.url).searchParams.get('state')}`)).rejects.toThrow(/cancelled/);
    await expect(auth.signInWithMicrosoft(async (r) => `${r.redirectUri}?code=x&state=forged`)).rejects.toThrow(/unexpected/);
    await expect(auth.signInWithMicrosoft(async () => {
      throw new Error('Sign-in was cancelled.');
    })).rejects.toThrow(/cancelled/);
  });

  it('completes the Microsoft chain and never stores plain refresh tokens', async () => {
    const file = join(tempDir(), 'accounts.json');
    const responses: Record<string, unknown> = {
      token: { access_token: 'ms-access', refresh_token: 'ms-refresh-secret' },
      authenticate: { Token: 'xbl', DisplayClaims: { xui: [{ uhs: 'userhash' }] } },
      authorize: { Token: 'xsts', DisplayClaims: { xui: [{ xid: '123' }] } },
      login_with_xbox: { access_token: 'mc-access' },
      profile: { id: '0123456789abcdef0123456789abcdef', name: 'Snowy' },
    };
    const fetchImpl = async (url: string) => {
      const key = Object.keys(responses).find((k) => url.includes(k))!;
      return new Response(JSON.stringify(responses[key]), { status: 200 });
    };
    const auth = new AuthManager(file, cipher, () => '11111111-2222-3333-4444-555555555555', fetchImpl, false);
    await auth.load();
    let shown = '';
    // Stands in for Microsoft's login window: the user signs in and Microsoft redirects back with a code.
    const account = await auth.signInWithMicrosoft(async (request) => {
      shown = request.url;
      const state = new URL(request.url).searchParams.get('state');
      return `${request.redirectUri}?code=auth-code&state=${state}`;
    });
    const params = new URL(shown).searchParams;
    expect(params.get('code_challenge_method')).toBe('S256');
    expect(params.get('client_id')).toBe('11111111-2222-3333-4444-555555555555');
    expect(account.uuid).toBe('01234567-89ab-cdef-0123-456789abcdef');
    const saved = readFileSync(file, 'utf8');
    expect(saved).not.toContain('ms-refresh-secret');
    expect(saved).not.toContain('mc-access');
    expect((await auth.session(account.id)).accessToken).toBe('mc-access');
    expect(auth.canAddOffline()).toBe(true);
  });
});

describe('performance profiles', () => {
  it('has Sodium-based stacks and conflict rules', () => {
    expect(getProfile('fps-boost')?.mods.map((m) => m.modId)).toContain('sodium');
    expect(CONFLICT_RULES.some((r) => r.modB === 'optifabric')).toBe(true);
  });

  it('resolves Modrinth files and reports unavailable mods', async () => {
    const profile = getProfile('maximum-fps')!;
    const fetchJson = async <T>(url: string): Promise<T> => {
      if (url.includes('modernfix')) return [] as T;
      const slug = /project\/([^/]+)\//.exec(url)![1];
      return [{ version_number: '1', files: [{ url: `https://cdn.modrinth.com/data/${slug}.jar`, filename: `${slug}-1.0.jar`, primary: true, size: 10, hashes: { sha1: 'c'.repeat(40) } }] }] as T;
    };
    const result = await resolveProfile(profile, '26.2', 'fabric', { fetchJson } as never);
    expect(result.unavailable.map((m) => m.slug)).toEqual(['modernfix']);
    expect(result.files.find((f) => f.mod.slug === 'sodium')?.fileName).toBe('sodium-1.0.jar');
  });
});
