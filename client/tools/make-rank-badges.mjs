/**
 * Builds Snowball's rank badges: the snowball, with the rank's logo attached to it.
 *
 * Every Snowball player wears the snowball in the player list, the way a Lunar player wears the
 * moon, so a Snowball player is recognisable before you read anything. The rank's own logo is
 * shrunk and set into the lower-right of the ball, overlapping its edge so the two read as one
 * mark rather than as two things that happen to be next to each other.
 *
 * The art in source-logos/ is the original hand-drawn set; this tool places it and does not
 * redraw it. The badge is 32x32 and the font draws it at 8, an exact quarter, so nothing lands
 * between pixels - the old badges were 16px art declared at height 9, and that fractional
 * reduction is what made them look muddy. Working at 32 also leaves the shrunken logo enough
 * room to keep its shape.
 *
 *   node client/tools/make-rank-badges.mjs [--preview]
 */
import { deflateSync, inflateSync } from 'node:zlib';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(here, 'source-logos');

// Only the main tree. The 1.8.9 build takes its assets from here through its own
// copySharedAssets task, so writing a second copy there makes processResources see duplicates.
const TREES = [join(here, '..', 'src', 'main', 'resources', 'assets', 'snowballclient')];
const LAUNCHER = join(here, '..', '..', 'launcher', 'src', 'renderer', 'assets', 'ranks');

/** The badge canvas. 32 is an exact 4x of the height 8 the font draws it at. */
const SIZE = 32;
/** How wide the shrunken logo is. Small enough to read as an attachment, not a second badge. */
const LOGO = 15;

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

function draw(target, sprite, ox, oy) {
  for (let y = 0; y < sprite.h; y++) for (let x = 0; x < sprite.w; x++) {
    const [r, g, b, a] = sprite.px[y][x];
    const ty = oy + y, tx = ox + x;
    if (a < 24 || ty < 0 || tx < 0 || ty >= target.length || tx >= target[0].length) continue;
    target[ty][tx] = [r, g, b, 255];
  }
}

/**
 * A dark rim traced around whatever was drawn last, so the logo separates from the pale ball it
 * overlaps while still touching it. Without this the two just smear into each other.
 */
function rim(target, before) {
  const added = [];
  for (let y = 0; y < target.length; y++) for (let x = 0; x < target[0].length; x++) {
    if (target[y][x][3] !== 255 || before[y][x]) continue;
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, -1], [1, -1], [-1, 1]]) {
      const nx = x + dx, ny = y + dy;
      if (ny < 0 || nx < 0 || ny >= target.length || nx >= target[0].length) continue;
      if (target[ny][nx][3] === 255 && !before[ny][nx]) continue;
      added.push([nx, ny]);
    }
  }
  for (const [x, y] of added) target[y][x] = [8, 12, 18, 255];
}

const ball = scaleTo(crop(decode(join(SOURCE, '_ball.png'))), SIZE);
const ballPlus = scaleTo(crop(decode(join(SOURCE, '_ball_plus.png'))), SIZE);

function badge(rank) {
  const canvas = blank(SIZE, SIZE);
  const base = rank.ball === '_ball_plus.png' ? ballPlus : ball;
  draw(canvas, base, Math.floor((SIZE - base.w) / 2), Math.floor((SIZE - base.h) / 2));
  if (!rank.logo) return canvas;

  // Which pixels were the ball, so the rim only traces the logo.
  const wasBall = canvas.map((row) => row.map((p) => p[3] === 255));
  const logo = scaleTo(crop(decode(join(SOURCE, rank.logo))), LOGO);
  // Seated on the lower-right of the ball rather than hung off its corner: centred at 68% of
  // the canvas, the logo overlaps the ball across most of its width and still clears the edge.
  const ox = Math.min(SIZE - logo.w, Math.round(SIZE * 0.68 - logo.w / 2));
  const oy = Math.min(SIZE - logo.h, Math.round(SIZE * 0.68 - logo.h / 2));
  draw(canvas, logo, ox, oy);
  rim(canvas, wasBall);
  draw(canvas, logo, ox, oy);
  return canvas;
}

let count = 0;
const write = (to, data) => { mkdirSync(dirname(to), { recursive: true }); writeFileSync(to, data); count++; };

const built = RANKS.map((rank) => ({ rank, data: encode(badge(rank)) }));

for (const tree of TREES) {
  const gui = join(tree, 'textures', 'gui');
  for (const { rank, data } of built) {
    write(join(gui, rank.id === 'snowball' ? 'tab_snowball.png' : rank.id === 'plus' ? 'tab_snowball_plus.png' : `rank/${rank.logo}`), data);
  }

  /**
   * One bitmap provider per rank. ascent 7 with height 8 sits the badge on the text baseline.
   */
  const providers = built.map(({ rank }, i) => ({
    file: rank.id === 'snowball' ? 'gui/tab_snowball.png' : rank.id === 'plus' ? 'gui/tab_snowball_plus.png' : `gui/rank/${rank.logo}`,
    char: `\\uE00${i}`,
  }));
  const json = `{\n\t"providers": [\n${providers
    .map((p) => `\t\t{ "type": "bitmap", "file": "snowballclient:${p.file}", "ascent": 7, "height": 8, "chars": ["${p.char}"] }`)
    .join(',\n')}\n\t]\n}\n`;
  write(join(tree, 'font', 'icons.json'), json);
}

// The launcher shows the same badge in chat, the people list and the admin panel.
for (const { rank, data } of built) write(join(LAUNCHER, `${rank.id}.png`), data);

for (const { rank } of built) console.log(`${rank.name.padEnd(12)} ${rank.logo ? `ball + ${rank.logo} at ${LOGO}px` : 'ball only'}`);
console.log(`\n${count} files written.`);

if (process.argv.includes('--preview')) {
  // A page showing each badge at the size the player list draws it, and magnified.
  const b64 = (rank) => built.find((b) => b.rank.id === rank.id).data.toString('base64');
  const row = (rank) => {
    const img = `<img src="data:image/png;base64,${b64(rank)}">`;
    return `<tr>
      <td class="s8">${img}</td>
      <td class="s64">${img}</td>
      <td class="tab"><span class="s16">${img}</span><span class="tag">[${rank.name}]</span> <span class="pl">Met4a</span></td>
      <td class="f">${rank.logo ?? '\u2014'}</td>
    </tr>`;
  };
  writeFileSync(join(here, 'rank-badges-preview.html'), `<!doctype html><meta charset=utf-8><title>Snowball rank badges</title>
<style>
 body{background:#0d1014;color:#e8edf4;font:14px 'Segoe UI',system-ui,sans-serif;margin:0;padding:28px 32px}
 h1{font-size:17px;margin:0 0 4px}
 p.sub{color:#8b95a3;margin:0 0 22px;font-size:13px;max-width:640px;line-height:1.5}
 table{border-collapse:collapse}
 th{text-align:left;font-size:10px;letter-spacing:.09em;text-transform:uppercase;color:#6e7987;font-weight:600;padding:0 18px 8px 0;border-bottom:1px solid #222831}
 td{padding:10px 18px 10px 0;border-bottom:1px solid #1a1f27;vertical-align:middle}
 img{image-rendering:pixelated;display:inline-block;vertical-align:middle}
 .s8 img{width:8px;height:8px}
 .s16 img{width:16px;height:16px;margin-right:5px}
 .s64 img{width:64px;height:64px}
 .tab{font:15px Consolas,monospace;white-space:nowrap}
 .tag{color:#9fb0c4}
 .pl{color:#fff}
 .f{font:12px Consolas,monospace;color:#6e7987}
</style>
<h1>Snowball rank badges</h1>
<p class=sub>Every player wears the snowball. The rank's logo is shrunk and set into its lower-right, overlapping the edge so the two read as one mark.</p>
<table><tr><th>In game</th><th>Magnified</th><th>As it reads in TAB</th><th>Logo</th></tr>${RANKS.map(row).join('')}</table>`);
  console.log('preview: client/tools/rank-badges-preview.html');
}
