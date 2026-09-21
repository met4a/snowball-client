/**
 * Publishes Snowball's rank badges into the resource trees and the launcher.
 *
 * Every Snowball player wears the snowball in the player list, the way a Lunar player wears the
 * moon. A ranked player wears the snowball *and* their rank's logo, as two separate glyphs:
 *
 *     [snowball][crown] Met4a
 *
 * Two glyphs rather than one composite, because at the size the player list draws a badge there is
 * no room to put a crown inside a snowball and still have either read. Side by side, each gets its
 * full height and stays sharp.
 *
 * The art in source-logos/ is the original hand-drawn set and this tool does not redraw it — it
 * copies it into the places that need it. Sharpness comes from the font declaring height 8 against
 * 16px art, an exact 2:1 reduction; the old declaration of 9 was fractional, and that is what made
 * the badges look muddy.
 *
 *   node client/tools/make-rank-badges.mjs [--preview]
 */
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(here, 'source-logos');

const TREES = [
  join(here, '..', 'src', 'main', 'resources', 'assets', 'snowballclient'),
  join(here, '..', 'legacy', 'src', 'main', 'resources', 'assets', 'snowballclient'),
];
const LAUNCHER = join(here, '..', '..', 'launcher', 'src', 'renderer', 'assets', 'ranks');

/**
 * `logo` is null for the two ranks that are just the snowball: an unranked player wears the plain
 * ball, and Snowball+ wears the ball that already carries its own plus.
 */
const RANKS = [
  { id: 'snowball', name: 'Snowball', ball: '_ball.png', logo: null },
  { id: 'plus', name: 'Snowball+', ball: '_ball_plus.png', logo: null },
  { id: 'tester', name: 'Tester', ball: '_ball.png', logo: 'tester.png' },
  { id: 'bug_hunter', name: 'Bug Hunter', ball: '_ball.png', logo: 'bug_hunter.png' },
  { id: 'partner', name: 'Partner', ball: '_ball.png', logo: 'partner.png' },
  { id: 'staff', name: 'Staff', ball: '_ball.png', logo: 'staff.png' },
  { id: 'developer', name: 'Developer', ball: '_ball.png', logo: 'developer.png' },
  { id: 'owner', name: 'Owner', ball: '_ball.png', logo: 'owner.png' },
];

let count = 0;
const put = (from, to) => {
  mkdirSync(dirname(to), { recursive: true });
  copyFileSync(from, to);
  count++;
};

for (const tree of TREES) {
  const gui = join(tree, 'textures', 'gui');
  put(join(SOURCE, '_ball.png'), join(gui, 'tab_snowball.png'));
  put(join(SOURCE, '_ball_plus.png'), join(gui, 'tab_snowball_plus.png'));
  for (const rank of RANKS) {
    if (rank.logo) put(join(SOURCE, rank.logo), join(gui, 'rank', rank.logo));
  }

  /**
   * One bitmap provider per glyph. ascent 7 with height 8 sits the badge on the text baseline,
   * and 16px art at height 8 is an exact halving, so no texel lands between two pixels.
   */
  const providers = [
    { file: 'gui/tab_snowball.png', char: '\\uE000' },
    { file: 'gui/tab_snowball_plus.png', char: '\\uE001' },
    ...RANKS.filter((r) => r.logo).map((r, i) => ({ file: `gui/rank/${r.logo}`, char: `\\uE00${i + 2}` })),
  ];
  const json = `{
\t"providers": [
${providers.map((p) => `\t\t{ "type": "bitmap", "file": "snowballclient:${p.file}", "ascent": 7, "height": 8, "chars": ["${p.char}"] }`).join(',\n')}
\t]
}
`;
  const dest = join(tree, 'font', 'icons.json');
  mkdirSync(dirname(dest), { recursive: true });
  writeFileSync(dest, json);
  count++;
}

// The launcher shows the same two marks in chat, the people list and the admin panel.
mkdirSync(LAUNCHER, { recursive: true });
for (const rank of RANKS) {
  put(join(SOURCE, rank.ball), join(LAUNCHER, `${rank.id}-ball.png`));
  if (rank.logo) put(join(SOURCE, rank.logo), join(LAUNCHER, `${rank.id}.png`));
}

for (const rank of RANKS) console.log(`${rank.name.padEnd(12)} ${rank.ball}${rank.logo ? ` + ${rank.logo}` : ''}`);
console.log(`\n${count} files written.`);

if (process.argv.includes('--preview')) {
  // A page showing the badges at the size the player list draws them, and magnified.
  const b64 = (f) => readFileSync(join(SOURCE, f)).toString('base64');
  const row = (rank) => {
    const marks = [rank.ball, rank.logo].filter(Boolean)
      .map((f) => `<img src="data:image/png;base64,${b64(f)}">`).join('');
    return `<tr>
      <td class="s8">${marks}</td>
      <td class="s48">${marks}</td>
      <td class="tab"><span class="s16">${marks}</span>${rank.logo || rank.id === 'plus' ? `<span class="tag">[${rank.name}]</span> ` : ''}<span class="pl">Met4a</span></td>
      <td class="f">${rank.logo ?? '—'}</td>
    </tr>`;
  };
  writeFileSync(join(here, 'rank-badges-preview.html'), `<!doctype html><meta charset=utf-8><title>Snowball rank badges</title>
<style>
 body{background:#0d1014;color:#e8edf4;font:14px 'Segoe UI',system-ui,sans-serif;margin:0;padding:28px 32px}
 h1{font-size:17px;margin:0 0 4px}
 p.sub{color:#8b95a3;margin:0 0 22px;font-size:13px;max-width:620px;line-height:1.5}
 table{border-collapse:collapse}
 th{text-align:left;font-size:10px;letter-spacing:.09em;text-transform:uppercase;color:#6e7987;font-weight:600;padding:0 18px 8px 0;border-bottom:1px solid #222831}
 td{padding:10px 18px 10px 0;border-bottom:1px solid #1a1f27;vertical-align:middle}
 img{image-rendering:pixelated;display:inline-block;vertical-align:middle}
 .s8 img{width:8px;height:8px}
 .s16 img{width:16px;height:16px;margin-right:1px}
 .s48 img{width:48px;height:48px;margin-right:4px}
 .tab{font:15px Consolas,monospace;white-space:nowrap}
 .tab .s16{margin-right:5px}
 .tag{color:#9fb0c4}
 .pl{color:#fff}
 .f{font:12px Consolas,monospace;color:#6e7987}
</style>
<h1>Snowball rank badges</h1>
<p class=sub>Every player wears the snowball. A ranked player wears the snowball and their rank's logo, as two glyphs side by side &mdash; so both stay sharp instead of one being squeezed inside the other.</p>
<table><tr><th>In game</th><th>Magnified</th><th>As it reads in TAB</th><th>Logo</th></tr>${RANKS.map(row).join('')}</table>`);
  console.log('preview: client/tools/rank-badges-preview.html');
}
