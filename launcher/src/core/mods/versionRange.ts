/**
 * Version comparison and dependency-range matching for the formats mods use:
 * Fabric/Quilt semver-style predicates and Maven ranges (Forge/NeoForge mods.toml).
 */

interface ParsedVersion {
  nums: number[];
  pre: string | null;
}

export function parseVersion(version: string): ParsedVersion {
  const core = version.trim().replace(/^v/i, '').split('+')[0];
  const m = /^(\d+(?:\.\d+)*)(?:[-. ]?(.*))?$/.exec(core);
  if (!m) return { nums: [], pre: core || null };
  const rest = m[2]?.trim();
  return { nums: m[1].split('.').map(Number), pre: rest ? rest : null };
}

/** Negative when a < b. Pre-releases sort before the matching release ("26.3-rc-1" < "26.3"). */
export function compareVersions(a: string, b: string): number {
  const va = parseVersion(a);
  const vb = parseVersion(b);
  const len = Math.max(va.nums.length, vb.nums.length);
  for (let i = 0; i < len; i++) {
    const d = (va.nums[i] ?? 0) - (vb.nums[i] ?? 0);
    if (d !== 0) return d < 0 ? -1 : 1;
  }
  if (va.pre === vb.pre) return 0;
  if (va.pre === null) return 1;
  if (vb.pre === null) return -1;
  return va.pre.localeCompare(vb.pre, undefined, { numeric: true });
}

function bump(target: string, index: number): string {
  const nums = parseVersion(target).nums;
  const out = nums.slice(0, index + 1);
  while (out.length <= index) out.push(0);
  out[index] += 1;
  return out.join('.');
}

function singlePredicate(version: string, predicate: string): boolean {
  if (predicate === '*' || predicate === '') return true;
  const m = /^(>=|<=|>|<|=|~|\^)?(.+)$/.exec(predicate);
  if (!m) return false;
  const op = m[1] ?? '=';
  const target = m[2];
  if (op === '=' && /(^|\.)[xX*]$/.test(target)) {
    const prefix = target.replace(/\.?[xX*]$/, '');
    return prefix === '' || version === prefix || version.startsWith(prefix + '.');
  }
  const c = compareVersions(version, target);
  switch (op) {
    case '>=':
      return c >= 0;
    case '<=':
      return c <= 0;
    case '>':
      return c > 0;
    case '<':
      return c < 0;
    case '~':
      return c >= 0 && compareVersions(version, bump(target, parseVersion(target).nums.length > 1 ? 1 : 0)) < 0;
    case '^':
      return c >= 0 && compareVersions(version, bump(target, 0)) < 0;
    default:
      return c === 0;
  }
}

/** Fabric dependency value: string (space separated = AND) or array (OR). */
export function matchesFabricPredicate(version: string, predicate: unknown): boolean {
  if (predicate === undefined || predicate === null) return true;
  if (Array.isArray(predicate)) return predicate.length === 0 || predicate.some((p) => matchesFabricPredicate(version, p));
  if (typeof predicate !== 'string') return true;
  const parts = predicate.trim().split(/\s+/).filter(Boolean);
  return parts.length === 0 || parts.every((p) => singlePredicate(version, p));
}

/** Maven version range such as "[1.21,1.22)", "[1.21.1]", "(,1.20]" or a soft "1.21.1" (at least). */
export function matchesMavenRange(version: string, range: string | undefined): boolean {
  if (!range || range.trim() === '' || range.trim() === '*') return true;
  const r = range.replace(/\s+/g, '');
  if (!/^[[(]/.test(r)) return compareVersions(version, r) >= 0;
  const re = /([[(])([^,\])]*)(?:,([^\])]*))?([\])])/g;
  let any = false;
  let m: RegExpExecArray | null;
  while ((m = re.exec(r))) {
    any = true;
    const [, open, lo, hi, close] = m;
    if (hi === undefined) {
      if (compareVersions(version, lo) === 0) return true;
      continue;
    }
    const okLo = !lo || (open === '[' ? compareVersions(version, lo) >= 0 : compareVersions(version, lo) > 0);
    const okHi = !hi || (close === ']' ? compareVersions(version, hi) <= 0 : compareVersions(version, hi) < 0);
    if (okLo && okHi) return true;
  }
  return !any;
}
