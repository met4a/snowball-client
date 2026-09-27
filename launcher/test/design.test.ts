import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The launcher, the website and the client share one set of colours and one typeface
 * (design/tokens.json). Each keeps its own stylesheet, so this is what stops them drifting apart.
 */
const root = join(__dirname, '..', '..');
const tokens = JSON.parse(readFileSync(join(root, 'design', 'tokens.json'), 'utf8')) as { color: Record<string, string>; font: { family: string } };

/** The custom properties declared in a stylesheet's first :root block. */
function rootVariables(css: string): Record<string, string> {
  const block = /:root\s*{([^}]*)}/.exec(css)?.[1] ?? '';
  const out: Record<string, string> = {};
  for (const m of block.matchAll(/--([\w-]+)\s*:\s*([^;]+);/g)) out[m[1]] = m[2].trim();
  return out;
}

describe('the shared design tokens', () => {
  for (const [name, file] of [['launcher', 'launcher/src/renderer/styles.css'], ['website', 'website/styles.css']] as const) {
    it(`are the colours the ${name} uses`, () => {
      const css = readFileSync(join(root, file), 'utf8');
      const vars = rootVariables(css);
      for (const [token, value] of Object.entries(tokens.color)) expect(vars[token], `${name} --${token}`).toBe(value);
      expect(css).toContain(`'${tokens.font.family}'`);
    });
  }

  it("are the client's default accent", () => {
    const theme = readFileSync(join(root, 'client/src/main/java/dev/snowballclient/client/gui/theme/Theme.java'), 'utf8');
    const accent = /accentColor = 0xFF([0-9A-Fa-f]{6});/.exec(theme)?.[1];
    expect(`#${accent?.toLowerCase()}`).toBe(tokens.color.accent);
  });
});
