/**
 * Builds Snowball's rank badges.
 *
 * Every badge is a Snowglass chip: a small rounded tile of glass with a mark in it, the way an app
 * shows a role. The tile says the tier and the mark says the rank:
 *   - Snowball, worn by every Snowball player: the snowball from the logo, in the logo's own
 *     colours, on dark glass like the HUD tiles. Quiet, so a server full of Snowball players is not
 *     a wall of colour.
 *   - Snowball+: the same snowball and a plus on polished ice, the accent blue, in a wider chip.
 *     The jump from dark glass to colour is what makes Plus read as the richer badge.
 *   - Every rank above: polished glass in the rank's own colour, with its own mark - a check for
 *     Tester, a target for Bug Hunter, a star for Partner, a shield for Staff, a prompt for
 *     Developer, a crown for Owner. No rank is written out as a [Tag] beside the name, so the mark
 *     has to say who someone is on its own.
 *
 * The chips are pixel art at their real size, 7 tall, and the font draws them at height 7: one texel
 * is one font pixel, so at every GUI scale each texel becomes a whole block of screen pixels and
 * nothing can blur or drop a row. Seven rows is the height of Minecraft's capitals, so a chip lines
 * up with the name beside it. (The old snowball was 32px art drawn at 8, which only came out clean
 * at GUI scale 4.) Every chip is glass the same way: a lighter top edge catches the light, the fill
 * darkens downward, the corners are half see-through so the tile reads as rounded, and coloured
 * chips put a soft shadow under their mark.
 *
 * Writes the client's font bitmaps and icons.json; with --preview, a page showing each badge.
 * The launcher keeps its own copies of the older badges (client/tools/make-staff-icons.mjs and
 * launcher/src/renderer/assets/ranks), sized by its own CSS, so this does not touch them.
 *
 *   node client/tools/make-rank-badges.mjs [--preview]
 */
import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

// Only the main tree. The 1.8.9 build takes its assets from here through its own
// copySharedAssets task, so writing a second copy there makes processResources see duplicates.
const TREES = [join(here, '..', 'src', 'main', 'resources', 'assets', 'snowballclient')];

/** The chip, in font pixels. Seven is the height of Minecraft's capitals. */
const SIZE = 7;

/**
 * The marks, 5x5, set one pixel in from the chip's edge. x is white; s is the mark's second tone,
 * a pale tint of the chip. The snowball has its own letters, for the logo's colours (see BALL).
 */
const MARKS = {
  snowball: ['.pwp.', 'pwwwg', 'pwwgb', 'gggbc', '.bcc.'],
  // Snowball+'s plus, beside the snowball in its wider chip.
  plus: ['.....', '.x...', 'xxx..', '.x...', '.....'],
  check: ['.....', '....x', '...x.', 'x.x..', '.x...'],
  target: ['.xxx.', 'x...x', 'x.x.x', 'x...x', '.xxx.'],
  star: ['..x..', '..x..', 'xxxxx', '.xxx.', '.x.x.'],
  // Halved like a heraldic shield, so it still reads as one when it is only seven pixels tall.
  shield: ['xxxss', 'xxxss', 'xxxss', '.xxs.', '..x..'],
  prompt: ['.....', 'x....', '.x...', 'x.xxx', '.....'],
  crown: ['.....', '..x..', 'x.x.x', 'xxxxx', 'xxxxx'],
};

/**
 * In the font's order, which Rank.java follows. `color` is the chip's; it matches Rank.java except
 * for Snowball+, whose chip uses the deeper Snowglass accent (#3A9BFF) because white on its pale
 * rank colour would not read. Snowball has no colour: it is dark glass.
 */
const RANKS = [
  { id: 'snowball', name: 'Snowball', file: 'tab_snowball.png', mark: 'snowball', color: null },
  { id: 'plus', name: 'Snowball+', file: 'tab_snowball_plus.png', mark: 'snowball', plus: true, color: '#3A9BFF' },
  { id: 'tester', name: 'Tester', file: 'rank/tester.png', mark: 'check', color: '#5CD6A8' },
  { id: 'bug_hunter', name: 'Bug Hunter', file: 'rank/bug_hunter.png', mark: 'target', color: '#FFD166' },
  { id: 'partner', name: 'Partner', file: 'rank/partner.png', mark: 'star', color: '#FFA24D' },
  { id: 'staff', name: 'Staff', file: 'rank/staff.png', mark: 'shield', color: '#5C8CFF' },
  { id: 'developer', name: 'Developer', file: 'rank/developer.png', mark: 'prompt', color: '#B57BFF' },
  { id: 'owner', name: 'Owner', file: 'rank/owner.png', mark: 'crown', color: '#7FCBFF' },
];

// ------------------------------------------------------------- PNG out

let CRC = null;
function crc32(buf) {
  if (!CRC) {
    CRC = new Int32Array(256);
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; CRC[n] = c; }
  }
  let c = -1;
  for (const byte of buf) c = CRC[(c ^ byte) & 0xff] ^ (c >>> 8);
  return c ^ -1;
}
function chunk(type, data) {
  const out = Buffer.alloc(data.length + 12);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'ascii');
  data.copy(out, 8);
  out.writeInt32BE(crc32(Buffer.concat([Buffer.from(type, 'ascii'), data])), data.length + 8);
  return out;
}
function encode(grid) {
  const h = grid.length, w = grid[0].length;
  const raw = Buffer.alloc(h * (w * 4 + 1));
  let p = 0;
  for (let y = 0; y < h; y++) {
    raw[p++] = 0;
    for (let x = 0; x < w; x++) { const [r, g, b, a] = grid[y][x]; raw[p++] = r; raw[p++] = g; raw[p++] = b; raw[p++] = a; }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ------------------------------------------------------------- the chip

const WHITE = [255, 255, 255];
const BLACK = [0, 0, 0];
const hexColour = (c) => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
/** The logo's snowball: a white core, pale ice, a grey-cyan body and a cyan rim. */
const BALL = { w: WHITE, p: hexColour('#E2FFFF'), g: hexColour('#CFE3E3'), b: hexColour('#A9D3D6'), c: hexColour('#7FC6CF') };

/** Source-over, for the shadow under a mark. */
function over(grid, x, y, [r, g, b, a]) {
  const d = grid[y][x], k = a / 255, da = d[3] / 255, oa = k + da * (1 - k);
  grid[y][x] = [r, g, b].map((v, i) => Math.round((v * k + d[i] * da * (1 - k)) / oa)).concat(Math.round(oa * 255));
}

/**
 * The tile: `top` fading to `bottom`, the first row lit as `shine`. A corner pixel keeps 96/255 of
 * its colour, which is what rounds the tile without cutting it into a cross.
 */
function tile(width, { top, bottom, shine }) {
  return Array.from({ length: SIZE }, (_, y) => Array.from({ length: width }, (_, x) => {
    const corner = (x === 0 || x === width - 1) && (y === 0 || y === SIZE - 1);
    return [...(y === 0 ? shine : mix(top, bottom, y / (SIZE - 1))), corner ? 96 : 255];
  }));
}

/**
 * Sets a mark into the tile, `ox` pixels further right than the first; with `shadow`, a pixel of it
 * under every edge the mark has below.
 */
function setMark(grid, rows, tones, shadow, ox = 0) {
  const at = (x, y) => rows[y]?.[x] ?? '.';
  if (shadow) {
    rows.forEach((row, y) => [...row].forEach((ch, x) => {
      if (ch !== '.' && at(x, y + 1) === '.') over(grid, ox + x + 1, y + 2, shadow);
    }));
  }
  rows.forEach((row, y) => [...row].forEach((ch, x) => {
    if (ch !== '.') grid[y + 1][ox + x + 1] = [...tones[ch], 255];
  }));
  return grid;
}

function badge(rank) {
  // Snowball+ is four pixels wider, for the plus beside its snowball.
  const width = rank.plus ? SIZE + 4 : SIZE;
  let grid, tones, shadow;
  if (!rank.color) {
    // Dark glass, the colours of the Snowglass HUD tiles.
    grid = tile(width, { top: hexColour('#2B3746'), bottom: hexColour('#121921'), shine: hexColour('#55657A') });
    tones = { ...BALL, x: hexColour('#F2FAFF'), s: hexColour('#9FD8FF') };
    shadow = null;
  } else {
    const c = hexColour(rank.color);
    grid = tile(width, { top: mix(c, BLACK, 0.12), bottom: mix(c, BLACK, 0.5), shine: mix(c, WHITE, 0.45) });
    tones = { ...BALL, x: WHITE, s: mix(c, WHITE, 0.62) };
    shadow = [...mix(c, BLACK, 0.72), 120];
  }
  setMark(grid, MARKS[rank.mark], tones, shadow);
  if (rank.plus) setMark(grid, MARKS.plus, tones, shadow, 6);
  return grid;
}

// ------------------------------------------------------------- output

let count = 0;
const write = (to, data) => { mkdirSync(dirname(to), { recursive: true }); writeFileSync(to, data); count++; };

const built = RANKS.map((rank) => ({ rank, data: encode(badge(rank)) }));

for (const tree of TREES) {
  for (const { rank, data } of built) write(join(tree, 'textures', 'gui', rank.file), data);

  /**
   * One bitmap provider per rank, in RANKS order, each drawn at its own height of 7 with ascent 7 so
   * it sits on the baseline beside the capitals. The last entry is the two-pixel gap RankText puts
   * between the badge and the name.
   */
  const json = `{\n\t"providers": [\n${RANKS.map((rank, i) => `\t\t{ "type": "bitmap", "file": "snowballclient:gui/${rank.file}", "ascent": 7, "height": ${SIZE}, "chars": ["\\uE00${i}"] }`)
    .concat(['\t\t{ "type": "space", "advances": { "\\uE0F0": 2 } }'])
    .join(',\n')}\n\t]\n}\n`;
  write(join(tree, 'font', 'icons.json'), json);
}

for (const { rank } of built) console.log(`${rank.name.padEnd(12)} ${rank.color ? `${rank.color} glass` : 'dark glass'}, ${rank.mark}`);
console.log(`\n${count} files written.`);

if (process.argv.includes('--preview')) {
  // Each badge at the size the player list draws it at GUI scales 1 to 4, and magnified.
  const src = (rank) => `data:image/png;base64,${built.find((b) => b.rank.id === rank.id).data.toString('base64')}`;
  const row = (rank) => {
    const img = `<img src="${src(rank)}">`;
    return `<tr>
      <td class="scales">${[1, 2, 3, 4].map((s) => `<img src="${src(rank)}" style="height:${SIZE * s}px">`).join('')}</td>
      <td class="big">${img}</td>
      <td class="tab"><span class="s16">${img}</span><span class="pl">Met4a</span></td>
    </tr>`;
  };
  writeFileSync(join(here, 'rank-badges-preview.html'), `<!doctype html><meta charset=utf-8><title>Snowball rank badges</title>
<style>
 body{background:#0d1014;color:#e8edf4;font:14px 'Segoe UI',system-ui,sans-serif;margin:0;padding:28px 32px}
 h1{font-size:17px;margin:0 0 4px}
 p.sub{color:#8b95a3;margin:0 0 22px;font-size:13px;max-width:640px;line-height:1.5}
 table{border-collapse:collapse}
 th{text-align:left;font-size:10px;letter-spacing:.09em;text-transform:uppercase;color:#6e7987;font-weight:600;padding:0 20px 8px 0;border-bottom:1px solid #222831}
 td{padding:10px 20px 10px 0;border-bottom:1px solid #1a1f27;vertical-align:middle}
 img{image-rendering:pixelated;display:inline-block;vertical-align:middle}
 .scales img{margin-right:10px}
 .s16 img{height:14px;margin-right:6px}
 .big img{height:56px}
 .tab{font:15px Consolas,monospace;white-space:nowrap}
 .pl{color:#fff}
</style>
<h1>Snowball rank badges</h1>
<p class=sub>Snowglass chips: the logo's snowball on dark glass for every Snowball player, the snowball and a plus on polished ice for Snowball+, and the rank's own colour and mark above that. Each is pixel art seven pixels tall, drawn one texel per font pixel.</p>
<table><tr><th>GUI scale 1-4</th><th>Badge</th><th>As it reads in TAB</th></tr>${built.map(({ rank }) => row(rank)).join('')}</table>`);
  console.log('preview: client/tools/rank-badges-preview.html');
}
