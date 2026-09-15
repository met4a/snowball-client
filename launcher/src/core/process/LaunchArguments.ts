import { delimiter, join } from 'node:path';
import type { InstanceConfig } from '../instance/InstanceManager.js';
import { resolveLibraries } from '../minecraft/VersionManager.js';
import { defaultRuleContext, rulesAllow, type RuleContext } from '../minecraft/rules.js';
import type { ArgumentValue, VersionJson } from '../minecraft/types.js';
import type { LauncherPaths } from '../util/paths.js';

export interface LaunchAccount {
  name: string;
  uuid: string;
  accessToken: string;
  type: 'msa' | 'offline';
  xuid?: string;
}

export interface LaunchInput {
  instance: InstanceConfig;
  gameDir: string;
  version: VersionJson;
  paths: LauncherPaths;
  account: LaunchAccount;
  nativesDir: string;
  launcherName: string;
  launcherVersion: string;
  /** Launcher-controlled JVM arguments (e.g. loading Snowball Client), placed before the player's own. */
  extraJvmArgs?: string[];
  ruleContext?: RuleContext;
}

export interface LaunchPlan {
  mainClass: string;
  classpath: string[];
  jvmArgs: string[];
  gameArgs: string[];
  /** Full argument vector for the java executable. */
  args: string[];
}

const SAFE_ARG = /^[^\0\r\n]*$/;

/** Splits a user-provided argument string, honouring double quotes ("-Dfoo=a b" stays one argument). */
export function splitArgs(text: string): string[] {
  const out: string[] = [];
  const re = /"([^"]*)"|(\S+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) out.push(m[1] ?? m[2]);
  return out;
}

function expand(values: ArgumentValue[] | undefined, ctx: RuleContext): string[] {
  const out: string[] = [];
  for (const v of values ?? []) {
    if (typeof v === 'string') out.push(v);
    else if (rulesAllow(v.rules, ctx)) out.push(...(Array.isArray(v.value) ? v.value : [v.value]));
  }
  return out;
}

function substitute(arg: string, vars: Record<string, string>): string {
  return arg.replace(/\$\{([a-zA-Z_]+)\}/g, (whole, key: string) => (key in vars ? vars[key] : whole));
}

/** Builds the java command line for a resolved (inheritance-flattened) version. */
export function buildLaunchPlan(input: LaunchInput): LaunchPlan {
  const { instance, version, paths, account } = input;
  const windowed = !instance.window.fullscreen;
  const ctx = input.ruleContext ?? defaultRuleContext({ has_custom_resolution: windowed, is_demo_user: false });

  const seen = new Set<string>();
  const classpath: string[] = [];
  for (const lib of resolveLibraries(version, paths.libraries, ctx)) {
    if (lib.isNative && !/:natives-/.test(lib.name)) continue; // legacy natives are extracted, not put on the classpath
    if (seen.has(lib.path)) continue;
    seen.add(lib.path);
    classpath.push(lib.path);
  }
  const jarId = version.jar ?? version.id;
  classpath.push(join(paths.versions, jarId, `${jarId}.jar`));

  const assetsId = version.assetIndex?.id ?? version.assets ?? 'legacy';
  const vars: Record<string, string> = {
    auth_player_name: account.name,
    auth_uuid: account.uuid.replace(/-/g, ''),
    auth_access_token: account.accessToken,
    auth_session: `token:${account.accessToken}:${account.uuid.replace(/-/g, '')}`,
    auth_xuid: account.xuid ?? '0',
    clientid: '0',
    user_type: account.type === 'msa' ? 'msa' : 'legacy',
    user_properties: '{}',
    version_name: version.id,
    version_type: version.type ?? 'release',
    game_directory: input.gameDir,
    assets_root: paths.assets,
    game_assets: join(paths.assets, 'virtual', assetsId),
    assets_index_name: assetsId,
    resolution_width: String(instance.window.width),
    resolution_height: String(instance.window.height),
    natives_directory: input.nativesDir,
    launcher_name: input.launcherName,
    launcher_version: input.launcherVersion,
    classpath: classpath.join(delimiter),
    classpath_separator: delimiter,
    library_directory: paths.libraries,
  };

  const jvmArgs = [`-Xms${instance.memory.minMb}M`, `-Xmx${instance.memory.maxMb}M`];
  if (version.arguments?.jvm) {
    jvmArgs.push(...expand(version.arguments.jvm, ctx).map((a) => substitute(a, vars)));
  } else {
    jvmArgs.push(`-Djava.library.path=${input.nativesDir}`, '-cp', vars.classpath);
  }
  if (version.logging?.client?.argument) {
    const configPath = join(paths.assets, 'log_configs', version.logging.client.file.id);
    jvmArgs.push(version.logging.client.argument.replace('${path}', configPath));
  }
  jvmArgs.push(...(input.extraJvmArgs ?? []), ...instance.jvmArgs);

  let gameArgs: string[];
  if (version.arguments?.game) gameArgs = expand(version.arguments.game, ctx).map((a) => substitute(a, vars));
  else gameArgs = splitArgs(version.minecraftArguments ?? '').map((a) => substitute(a, vars));
  if (!version.arguments?.game && windowed) gameArgs.push('--width', vars.resolution_width, '--height', vars.resolution_height);
  if (instance.window.fullscreen) gameArgs.push('--fullscreen');
  gameArgs.push(...instance.gameArgs);

  const args = [...jvmArgs, version.mainClass, ...gameArgs];
  for (const a of args) {
    if (!SAFE_ARG.test(a)) throw new Error('Launch arguments contain invalid control characters.');
  }
  if (!/^[\w.$]+$/.test(version.mainClass)) throw new Error(`Invalid main class in version metadata: ${version.mainClass}`);
  return { mainClass: version.mainClass, classpath, jvmArgs, gameArgs, args };
}
