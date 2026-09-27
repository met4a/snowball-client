/**
 * Draws the staff icons: a snowball flying right, with an ice trail and a few flakes behind it.
 * Owner, Developer and Staff wear it instead of a written rank - the icon is the rank - and the
 * trail's tint tells the three apart.
 *
 * It is pixel art at its real size, 11x7, rather than a larger picture scaled down. Seven rows is
 * the height of Minecraft's capital letters, so the icon lines up with the name beside it, and at
 * every GUI scale each of its pixels becomes a whole number of screen pixels: it cannot blur.
 *
 * Writes the client's font bitmaps, the launcher's copies, and (with --preview) a sheet at 1x-6x
 * on the backgrounds it is shown on.
 *
 *   node client/tools/make-staff-icons.mjs [--preview <file>]
 */
import { crc32, deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const CLIENT = join(here, '..', 'src', 'main', 'resources', 'assets', 'snowballclient', 'textures', 'gui', 'rank');
const LAUNCHER = join(here, '..', '..', 'launcher', 'src', 'renderer', 'assets', 'ranks');

/**
 * The comet. The ball is the same in every variant; letters in the trail take the variant's tint.
 *   W highlight   A snow   B snow in shade   S ice   D deep ice
 *   T trail   t faint trail   p flake
 */
const COMET = [
  '..p........',
  '.......BWW.',
  '...tTTBAWWA',
  '.tTTTTSAAAB',
  'p..tTtSBABS',
  '.......DSS.',
  '...p.......',
];
// The ball is five pixels round on rows 1-5, so its middle is row 3: the middle of a capital letter,
// which the font draws on rows 0-6. The flakes above and below keep the glyph balanced.

const BALL = {
  W: [0xff, 0xff, 0xff, 0xff],
  A: [0xe4, 0xf5, 0xff, 0xff],
  B: [0xb8, 0xe2, 0xff, 0xff],
  S: [0x7f, 0xcb, 0xff, 0xff],
  D: [0x4e, 0x9c, 0xd6, 0xff],
};

/** Trail tints. Close to each other on purpose: all three are staff, all three are snow. */
const VARIANTS = {
  owner: { T: [0xc8, 0xec, 0xff, 0xff], t: [0x8f, 0xd2, 0xff, 0xb0], p: [0xff, 0xff, 0xff, 0xe6] },
  developer: { T: [0xd6, 0xcc, 0xff, 0xff], t: [0xa9, 0x96, 0xff, 0xb0], p: [0xf0, 0xec, 0xff, 0xe6] },
  staff: { T: [0xb2, 0xc9, 0xff, 0xff], t: [0x72, 0x9c, 0xff, 0xb0], p: [0xe6, 0xee, 0xff, 0xe6] },
};

function pixels(variant) {
  const palette = { ...BALL, ...VARIANTS[variant] };
  const h = COMET.length;
  const w = COMET[0].length;
  const out = Buffer.alloc(w * h * 4);
  COMET.forEach((row, y) => {
    if (row.length !== w) throw new Error(`row ${y} is ${row.length} wide, not ${w}`);
    [...row].forEach((ch, x) => {
      if (ch === '.') return;
      const c = palette[ch];
      if (!c) throw new Error(`no colour for "${ch}"`);
      c.forEach((v, i) => (out[(y * w + x) * 4 + i] = v));
    });
  });
  return { w, h, rgba: out };
}

function png({ w, h, rgba }) {
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** Every variant at 1x to 6x, on the player list's grey, Minecraft's sky and the launcher's dark. */
function preview(file) {
  const scales = [1, 2, 3, 4, 5, 6];
  const backgrounds = [[0x33, 0x33, 0x33], [0x78, 0x9d, 0xd4], [0x0b, 0x0f, 0x14]];
  const icon = pixels('owner');
  const cellW = (icon.w + 3) * 6 + 4;
  const cellH = (icon.h + 3) * 6 + 4;
  const variants = Object.keys(VARIANTS);
  const W = cellW * scales.length;
  const H = cellH * backgrounds.length * variants.length;
  const out = Buffer.alloc(W * H * 4);
  variants.forEach((variant, vi) => {
    const p = pixels(variant);
    backgrounds.forEach((bg, bi) => {
      const top = (vi * backgrounds.length + bi) * cellH;
      for (let y = top; y < top + cellH; y++) {
        for (let x = 0; x < W; x++) out.set([...bg, 0xff], (y * W + x) * 4);
      }
      scales.forEach((s, si) => {
        const ox = si * cellW + 4;
        const oy = top + 4;
        for (let y = 0; y < p.h * s; y++) {
          for (let x = 0; x < p.w * s; x++) {
            const src = ((Math.floor(y / s)) * p.w + Math.floor(x / s)) * 4;
            const a = p.rgba[src + 3] / 255;
            const dst = ((oy + y) * W + ox + x) * 4;
            for (let i = 0; i < 3; i++) out[dst + i] = Math.round(p.rgba[src + i] * a + out[dst + i] * (1 - a));
          }
        }
      });
    });
  });
  writeFileSync(file, png({ w: W, h: H, rgba: out }));
}

for (const variant of Object.keys(VARIANTS)) {
  const image = png(pixels(variant));
  writeFileSync(join(CLIENT, `${variant}.png`), image);
  writeFileSync(join(LAUNCHER, `${variant}.png`), image);
}
const at = process.argv.indexOf('--preview');
if (at > 0) preview(process.argv[at + 1]);
console.log(`Staff icons written: ${Object.keys(VARIANTS).join(', ')} (11x7)`);
