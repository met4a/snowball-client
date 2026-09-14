import { arch, platform, release } from 'node:os';
import { assertSafeRelativePath } from '../util/paths.js';

/** Mojang's OS identifiers used in version JSON rules and Java runtime manifests. */
export type MojangOs = 'windows' | 'osx' | 'linux';

export interface RuleContext {
  os: MojangOs;
  arch: string;
  osVersion: string;
  features: Record<string, boolean>;
}

export interface Rule {
  action: 'allow' | 'disallow';
  os?: { name?: string; arch?: string; version?: string };
  features?: Record<string, boolean>;
}

export function currentOs(): MojangOs {
  const p = platform();
  if (p === 'win32') return 'windows';
  if (p === 'darwin') return 'osx';
  return 'linux';
}

export function defaultRuleContext(features: Record<string, boolean> = {}): RuleContext {
  return { os: currentOs(), arch: arch() === 'ia32' ? 'x86' : arch(), osVersion: release(), features };
}

/**
 * Mojang rule semantics: no rules means allowed; otherwise start disallowed and let the
 * last matching rule decide.
 */
export function rulesAllow(rules: Rule[] | undefined, ctx: RuleContext): boolean {
  if (!rules || rules.length === 0) return true;
  let allowed = false;
  for (const rule of rules) {
    if (ruleMatches(rule, ctx)) allowed = rule.action === 'allow';
  }
  return allowed;
}

function ruleMatches(rule: Rule, ctx: RuleContext): boolean {
  if (rule.os) {
    if (rule.os.name && rule.os.name !== ctx.os) return false;
    if (rule.os.arch && rule.os.arch !== ctx.arch) return false;
    if (rule.os.version) {
      try {
        if (!new RegExp(rule.os.version).test(ctx.osVersion)) return false;
      } catch {
        return false;
      }
    }
  }
  if (rule.features) {
    for (const [key, value] of Object.entries(rule.features)) {
      if ((ctx.features[key] ?? false) !== value) return false;
    }
  }
  return true;
}

export interface MavenCoordinate {
  group: string;
  artifact: string;
  version: string;
  classifier?: string;
  extension: string;
}

export function parseMaven(name: string): MavenCoordinate {
  const [coords, ext] = name.split('@');
  const parts = coords.split(':');
  if (parts.length < 3 || parts.some((p) => !p)) throw new Error(`Invalid maven coordinate: ${name}`);
  return { group: parts[0], artifact: parts[1], version: parts[2], classifier: parts[3], extension: ext ?? 'jar' };
}

export function mavenPath(name: string): string {
  const c = parseMaven(name);
  const file = `${c.artifact}-${c.version}${c.classifier ? `-${c.classifier}` : ''}.${c.extension}`;
  return assertSafeRelativePath(`${c.group.replace(/\./g, '/')}/${c.artifact}/${c.version}/${file}`);
}

/** Key identifying a library independent of version, used when merging inherited profiles. */
export function libraryKey(name: string): string {
  const c = parseMaven(name);
  return `${c.group}:${c.artifact}${c.classifier ? `:${c.classifier}` : ''}`;
}
