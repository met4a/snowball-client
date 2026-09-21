/**
 * Builds Snowball's rank badges: the snowball, with the rank's emblem set into it.
 *
 * Every Snowball player wears the snowball in the player list, the way a Lunar player wears the
 * moon, so a Snowball player is recognisable before you read a word. The rank's emblem is seated
 * into the lower-right of the ball, overlapping its edge, so the two read as one mark rather than
 * as two things standing next to each other.
 *
 * The emblems are drawn from geometry in rank-icons.mjs rather than stored as bitmaps, so one
 * definition renders sharply at the three sizes this needs: the player list's, the launcher's,
 * and the preview sheet's.
 *
 * The badge is composed at 32x32 and the font draws it at 8, an exact quarter, so nothing lands
 * between pixels. The old badges were 16px art declared at height 9 - a fractional reduction, and
 * that is what made them look muddy.
 *
 *   node client/tools/make-rank-badges.mjs [--preview]
 */
import { deflateSync, inflateSync } from 'node:zlib';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderEmblem } from './rank-icons.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(here, 'source-logos');

// Only the main tree. The 1.8.9 build takes its assets from here through its own
// copySharedAssets task, so writing a second copy there makes processResources see duplicates.
const TREES = [join(here, '..', 'src', 'main', 'resources', 'assets', 'snowballclient')];
const LAUNCHER = join(here, '..', '..', 'launcher', 'src', 'renderer', 'assets', 'ranks');

/** The badge canvas. 32 is an exact 4x of the height 8 the font draws it at. */
const SIZE = 32;
/** How wide the emblem is drawn. Big enough to read, small enough to stay an attachment. */
const EMBLEM = 15;

/** `emblem` is null for the two ranks that are only the snowball. Colours match Rank.java. */
const RANKS = [
  { id: 'snowball', name: 'Snowball', file: 'tab_snowball.png', ball: '_ball.png', emblem: null, color: '#C7D2DD' },
  { id: 'plus', name: 'Snowball+', file: 'tab_snowball_plus.png', ball: '_ball_plus.png', emblem: null, color: '#9FD8FF' },
  { id: 'tester', name: 'Tester', file: 'rank/tester.png', ball: '_ball.png', emblem: 'tester', color: '#5CD6A8' },
  { id: 'bug_hunter', name: 'Bug Hunter', file: 'rank/bug_hunter.png', ball: '_ball.png', emblem: 'bug_hunter', color: '#FFD166' },
  { id: 'partner', name: 'Partner', file: 'rank/partner.png', ball: '_ball.png', emblem: 'partner', color: '#FFA24D' },
  { id: 'staff', name: 'Staff', file: 'rank/staff.png', ball: '_ball.png', emblem: 'staff', color: '#5C8CFF' },
  { id: 'developer', name: 'Developer', file: 'rank/developer.png', ball: '_ball.png', emblem: 'developer', color: '#B57BFF' },
  { id: 'owner', name: 'Owner', file: 'rank/owner.png', ball: '_ball.png', emblem: 'owner', color: '#7FCBFF', emblemColor: '#EAF4FF' },
];

// ------------------------------------------------------------- PNG in/out

function decode(file) {
  const b = readFileSync(file);
  let p = 8, w = 0, h = 0, depth = 8, ct = 6, plte = null, trns = null;
  const idat = [];
  while (p < b.length) {
    const len = b.readUInt32BE(p);
    const type = b.toString('ascii', p + 4, p + 8);
    const d = b.subarray(p + 8, p + 8 + len);
    if (type === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); depth = d[8]; ct = d[9]; }
    else if (type === 'PLTE') plte = d;
    else if (type === 'tRNS') trns = d;
    else if (type === 'IDAT') idat.push(d);
    else if (type === 'IEND') break;
    p += 12 + len;
  }
  const raw = inflateSync(Buffer.concat(idat));
  const ch = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[ct];
  const bpp = Math.max(1, (ch * depth) / 8);
  const stride = Math.ceil((w * ch * depth) / 8);
  const out = Buffer.alloc(h * stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? out[y * stride + i - bpp] : 0;
      const up = y > 0 ? out[(y - 1) * stride + i] : 0;
      const ul = y > 0 && i >= bpp ? out[(y - 1) * stride + i - bpp] : 0;
      let v = line[i];
      if (f === 1) v += a;
      else if (f === 2) v += up;
      else if (f === 3) v += (a + up) >> 1;
      else if (f === 4) {
        const pa = Math.abs(up - ul), pb = Math.abs(a - ul), pc = Math.abs(a + up - 2 * ul);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? up : ul;
      }
      out[y * stride + i] = v & 255;
    }
  }
  const px = [];
  for (let y = 0; y < h; y++) {
    const row = [];
    for (let x = 0; x < w; x++) {
      if (ct === 6) { const o = y * stride + x * 4; row.push([out[o], out[o + 1], out[o + 2], out[o + 3]]); }
      else if (ct === 2) { const o = y * stride + x * 3; row.push([out[o], out[o + 1], out[o + 2], 255]); }
      else if (ct === 3) { const i = out[y * stride + x]; row.push([plte[i * 3], plte[i * 3 + 1], plte[i * 3 + 2], trns && i < trns.length ? trns[i] : 255]); }
      else if (ct === 4) { const o = y * stride + x * 2; row.push([out[o], out[o], out[o], out[o + 1]]); }
      else { const v = out[y * stride + x]; row.push([v, v, v, 255]); }
    }
    px.push(row);
  }
  return { w, h, px };
}

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

// ------------------------------------------------------------- compositing

const blank = (w, h) => Array.from({ length: h }, () => Array.from({ length: w }, () => [0, 0, 0, 0]));

/** Trims to the pixels actually drawn, so scaling works on the art and not on the margin. */
function crop({ w, h, px }) {
  let x0 = w, y0 = h, x1 = -1, y1 = -1;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (px[y][x][3] > 24) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  }
  if (x1 < 0) return { w: 1, h: 1, px: [[[0, 0, 0, 0]]] };
  const out = [];
  for (let y = y0; y <= y1; y++) out.push(px[y].slice(x0, x1 + 1));
  return { w: x1 - x0 + 1, h: y1 - y0 + 1, px: out };
}

/** Nearest neighbour, the only honest way to resize pixel art. */
function scaleTo(sprite, box) {
  const ratio = Math.min(box / sprite.w, box / sprite.h);
  const w = Math.max(1, Math.round(sprite.w * ratio));
  const h = Math.max(1, Math.round(sprite.h * ratio));
  const out = blank(w, h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    out[y][x] = sprite.px[Math.min(sprite.h - 1, Math.floor((y * sprite.h) / h))][Math.min(sprite.w - 1, Math.floor((x * sprite.w) / w))];
  }
  return { w, h, px: out };
}

/** Accepts either a {w,h,px} sprite or a plain RGBA grid. */
function draw(target, sprite, ox, oy) {
  const px = sprite.px ?? sprite;
  const h = px.length;
  const w = px[0].length;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const [r, g, b, a] = px[y][x];
    const ty = oy + y, tx = ox + x;
    if (a < 24 || ty < 0 || tx < 0 || ty >= target.length || tx >= target[0].length) continue;
    target[ty][tx] = [r, g, b, 255];
  }
}

const ball = scaleTo(crop(decode(join(SOURCE, '_ball.png'))), SIZE);
const ballPlus = scaleTo(crop(decode(join(SOURCE, '_ball_plus.png'))), SIZE);

function badge(rank) {
  const canvas = blank(SIZE, SIZE);
  const base = rank.ball === '_ball_plus.png' ? ballPlus : ball;
  draw(canvas, base, Math.floor((SIZE - base.w) / 2), Math.floor((SIZE - base.h) / 2));
  if (!rank.emblem) return canvas;

  const emblem = renderEmblem(rank.emblem, rank.emblemColor ?? rank.color, EMBLEM);
  const w = emblem[0].length;
  const h = emblem.length;
  // Seated on the lower-right of the ball rather than hung off its corner: centred at 68% of the
  // canvas, the emblem overlaps the ball across most of its width and still clears the edge.
  const ox = Math.min(SIZE - w, Math.round(SIZE * 0.68 - w / 2));
  const oy = Math.min(SIZE - h, Math.round(SIZE * 0.68 - h / 2));
  draw(canvas, emblem, ox, oy);
  return canvas;
}

let count = 0;
const write = (to, data) => { mkdirSync(dirname(to), { recursive: true }); writeFileSync(to, data); count++; };

const built = RANKS.map((rank) => ({ rank, data: encode(badge(rank)) }));

for (const tree of TREES) {
  for (const { rank, data } of built) write(join(tree, 'textures', 'gui', rank.file), data);

  /** One bitmap provider per rank. ascent 7 with height 8 sits the badge on the text baseline. */
  const providers = built.map(({ rank }, i) => ({ file: `gui/${rank.file}`, char: `\\uE00${i}` }));
  const json = `{\n\t"providers": [\n${providers
    .map((p) => `\t\t{ "type": "bitmap", "file": "snowballclient:${p.file}", "ascent": 7, "height": 8, "chars": ["${p.char}"] }`)
    .join(',\n')}\n\t]\n}\n`;
  write(join(tree, 'font', 'icons.json'), json);
}

// The launcher shows the same badge in chat, the people list and the admin panel, and the emblem
// on its own where there is room for it.
for (const { rank, data } of built) {
  write(join(LAUNCHER, `${rank.id}.png`), data);
  if (rank.emblem) write(join(LAUNCHER, `${rank.id}-emblem.png`), encode(renderEmblem(rank.emblem, rank.emblemColor ?? rank.color, 32)));
}

for (const { rank } of built) console.log(`${rank.name.padEnd(12)} ${rank.emblem ? `ball + ${rank.emblem} at ${EMBLEM}px` : 'ball only'}`);
console.log(`\n${count} files written.`);

if (process.argv.includes('--preview')) {
  // A page showing each badge at the size the player list draws it, magnified, and the emblem on
  // its own, which is how it appears in profiles and on the website.
  const b64 = (rank) => built.find((b) => b.rank.id === rank.id).data.toString('base64');
  const solo = (rank) => (rank.emblem ? encode(renderEmblem(rank.emblem, rank.emblemColor ?? rank.color, 32)).toString('base64') : null);
  const row = (rank) => {
    const img = `<img src="data:image/png;base64,${b64(rank)}">`;
    const only = solo(rank);
    return `<tr>
      <td class="s8">${img}</td>
      <td class="s64">${img}</td>
      <td class="s32">${only ? `<img src="data:image/png;base64,${only}">` : '<span class="f">—</span>'}</td>
      <td class="tab"><span class="s16">${img}</span><span style="color:${rank.color}">[${rank.name}]</span> <span class="pl">Met4a</span></td>
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
 .s8 img{width:8px;height:8px}
 .s16 img{width:16px;height:16px;margin-right:6px}
 .s32 img{width:32px;height:32px}
 .s64 img{width:64px;height:64px}
 .tab{font:15px Consolas,monospace;white-space:nowrap}
 .pl{color:#fff}
 .f{font:12px Consolas,monospace;color:#6e7987}
</style>
<h1>Snowball rank badges</h1>
<p class=sub>Every player wears the snowball. The rank's emblem is seated into its lower-right, overlapping the edge so the two read as one mark. Emblems are drawn from geometry, so they stay sharp at every size.</p>
<table><tr><th>In game</th><th>Badge</th><th>Emblem</th><th>As it reads in TAB</th></tr>${RANKS.map(row).join('')}</table>`);
  console.log('preview: client/tools/rank-badges-preview.html');
}
