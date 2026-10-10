/**
 * Paints Snowball's capes: each one is a function from a point on the cape to a colour, rendered at
 * four times Minecraft's cape resolution (a 256x128 texture, which the cape model maps exactly as it
 * does the usual 64x32) so gradients and stars stay smooth in game.
 *
 * Minecraft's cape layout, in 64x32 units (x4 here):
 *   outer face (1,1)-(11,17), inner face (12,1)-(22,17), sides (0,1) and (11,1), 1 wide,
 *   top (1,0)-(11,1), bottom (11,0)-(21,1), and the elytra's wings from (22,0) to (46,22).
 * The outer face is what other players see on your back. Capes are drawn solid, so every pixel of
 * the cape area is opaque.
 *
 * Also writes a preview of each outer face for the picker.
 *
 *   node client/tools/make-capes.mjs
 */
import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = join(here, '..', 'src', 'main', 'resources', 'assets', 'snowballclient', 'textures', 'cosmetics');
const S = 4;
const W = 64 * S;
const H = 32 * S;

// ------------------------------------------------------------- PNG

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
function encode(w, h, rgba) {
  const raw = Buffer.alloc(h * (w * 4 + 1));
  let p = 0;
  for (let y = 0; y < h; y++) {
    raw[p++] = 0;
    for (let x = 0; x < w * 4; x++) raw[p++] = rgba[y * w * 4 + x];
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ------------------------------------------------------------- colour helpers

const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const mix = (a, b, t) => a.map((v, i) => v + (b[i] - v) * Math.max(0, Math.min(1, t)));
const add = (a, b, t) => a.map((v, i) => Math.min(255, v + b[i] * Math.max(0, t)));
const shade = (c, k) => c.map((v) => v * k);
const smooth = (e0, e1, x) => { const t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); };
/** A repeatable random number for a cell, so stars land in the same places every build. */
const rnd = (x, y, seed) => {
  let n = (x * 374761393 + y * 668265263 + seed * 2147483647) | 0;
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967296;
};
/** Stars: a few bright points on a grid of cells, with a soft halo. u and v run 0..1 over the face. */
function stars(u, v, density, seed, size = 0.012) {
  const cells = 26;
  const cx = Math.floor(u * cells), cy = Math.floor(v * cells * 1.6);
  let light = 0;
  for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
    const gx = cx + dx, gy = cy + dy;
    if (rnd(gx, gy, seed) > density) continue;
    const sx = (gx + rnd(gx, gy, seed + 1)) / cells, sy = (gy + rnd(gx, gy, seed + 2)) / (cells * 1.6);
    const d = Math.hypot((u - sx) * 1, (v - sy) * 1.6);
    const s = size * (0.6 + rnd(gx, gy, seed + 3));
    light = Math.max(light, 1 - smooth(s * 0.4, s, d));
  }
  return light;
}

// ------------------------------------------------------------- the capes

const CAPES = {
  snowball(u, v) {
    let c = mix(hex('#8FD3FF'), hex('#2F5E9E'), v);
    c = add(c, [255, 255, 255], stars(u, v, 0.18, 3, 0.01) * 0.8);
    // The snowball: a lit sphere in the upper middle, with a cool shadow under it.
    const dx = (u - 0.5) / 0.32, dy = (v - 0.36) / (0.32 / 1.6);
    const r = Math.hypot(dx, dy);
    if (r < 1.12) c = mix(c, shade(c, 0.6), (1 - smooth(1.0, 1.12, r)) * 0.5);
    if (r < 1) {
      const lit = Math.max(0, -dx * 0.45 - dy * 0.55 + Math.sqrt(Math.max(0, 1 - r * r)) * 0.75);
      const snow = mix(hex('#B9D6EE'), hex('#FFFFFF'), lit);
      c = mix(c, snow, 1 - smooth(0.94, 1, r));
    }
    return c;
  },
  aurora(u, v) {
    let c = mix(hex('#140A30'), hex('#05020F'), v);
    c = add(c, [255, 255, 255], stars(u, v, 0.22, 7) * 0.9);
    // Three curtains of light, each a band that waves across the cape.
    const bands = [
      { y: 0.28, amp: 0.07, freq: 2.2, phase: 0.3, colour: hex('#6FF2C8'), width: 0.09 },
      { y: 0.46, amp: 0.06, freq: 2.9, phase: 1.9, colour: hex('#B794FF'), width: 0.08 },
      { y: 0.62, amp: 0.05, freq: 1.7, phase: 4.1, colour: hex('#57C7FF'), width: 0.06 },
    ];
    for (const b of bands) {
      const centre = b.y + Math.sin(u * Math.PI * b.freq + b.phase) * b.amp;
      const d = v - centre;
      // Bright at the lower edge, fading upward like real aurora curtains.
      const glow = d < 0 ? Math.exp(-((d / (b.width * 2.4)) ** 2)) : Math.exp(-((d / (b.width * 0.5)) ** 2));
      const shimmer = 0.75 + 0.25 * Math.sin(u * 60 + b.phase * 3);
      c = add(c, b.colour, glow * 0.85 * shimmer);
    }
    return c;
  },
  northern_lights(u, v) {
    let c = mix(hex('#03201C'), hex('#010807'), v);
    c = add(c, [220, 255, 240], stars(u, v, 0.14, 11) * 0.6);
    // Vertical rays that brighten towards the top.
    const rays = 0.5 + 0.5 * Math.sin(u * 38 + Math.sin(u * 7) * 2);
    const curtain = Math.exp(-(((v - 0.35 - Math.sin(u * 6.3) * 0.08) / 0.22) ** 2));
    c = add(c, hex('#4DFFB0'), curtain * (0.35 + 0.65 * rays) * 0.8);
    c = add(c, hex('#2AB7FF'), curtain * 0.25 * (1 - rays));
    return c;
  },
  frost(u, v) {
    let c = mix(hex('#EAF6FF'), hex('#8CC5F0'), v);
    // A snowflake: six arms with side branches, drawn as distance to line segments.
    const px = (u - 0.5), py = (v - 0.42) * 1.6;
    let d = Infinity;
    for (let k = 0; k < 6; k++) {
      const a = (k * Math.PI) / 3;
      const ax = Math.cos(a), ay = Math.sin(a);
      const t = Math.max(0, Math.min(0.3, px * ax + py * ay));
      d = Math.min(d, Math.hypot(px - ax * t, py - ay * t));
      for (const s of [0.12, 0.2]) {
        for (const side of [-1, 1]) {
          const b = a + side * 0.7;
          const bx = Math.cos(b), by = Math.sin(b);
          const ox = px - ax * s, oy = py - ay * s;
          const tb = Math.max(0, Math.min(0.07, ox * bx + oy * by));
          d = Math.min(d, Math.hypot(ox - bx * tb, oy - by * tb));
        }
      }
    }
    const line = 1 - smooth(0.006, 0.014, d);
    c = mix(c, hex('#3F86C9'), line * 0.85);
    // Faint crystals in the background.
    c = mix(c, hex('#FFFFFF'), stars(u, v, 0.3, 19, 0.015) * 0.7);
    return c;
  },
  ember(u, v) {
    let c = mix(hex('#140403'), hex('#4A1205'), v);
    // Fire rising from the bottom edge.
    const flame = smooth(0.45, 1.0, v + 0.08 * Math.sin(u * 17 + v * 9) + 0.05 * Math.sin(u * 41));
    c = mix(c, hex('#FF5A1F'), flame * 0.85);
    c = mix(c, hex('#FFC34D'), smooth(0.8, 1.05, v + 0.06 * Math.sin(u * 23)) * 0.8);
    // Sparks drifting up.
    c = add(c, hex('#FFB347'), stars(u, v, 0.2, 23, 0.011) * (1.1 - v));
    return c;
  },
  sakura(u, v) {
    let c = mix(hex('#FFD3E6'), hex('#E67BA8'), v);
    // A dark branch across the upper part.
    const branchY = 0.22 + 0.12 * u + 0.03 * Math.sin(u * 9);
    const branch = 1 - smooth(0.012, 0.022, Math.abs(v - branchY) * 1.6 * (1.4 - u * 0.6));
    c = mix(c, hex('#4A2730'), branch);
    // Five-petal blossoms placed on a grid, more of them near the branch.
    const cells = 4;
    const gx = Math.floor(u * cells), gy = Math.floor(v * cells * 1.6);
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const x = gx + dx, y = gy + dy;
      if (rnd(x, y, 31) > 0.55) continue;
      const cx = (x + rnd(x, y, 32)) / cells, cy = (y + rnd(x, y, 33)) / (cells * 1.6);
      const ox = u - cx, oy = (v - cy) * 1.6;
      const r = Math.hypot(ox, oy), a = Math.atan2(oy, ox) + rnd(x, y, 34) * 6;
      const petal = 0.1 * (0.6 + 0.4 * Math.cos(a * 5));
      if (r < petal) c = mix(c, mix(hex('#FFF1F6'), hex('#FF9CC8'), r / petal), 0.95);
      if (r < 0.018) c = hex('#C2185B');
    }
    return c;
  },
  midnight(u, v) {
    let c = mix(hex('#0B1636'), hex('#03050E'), v);
    c = add(c, [255, 255, 255], stars(u, v, 0.32, 41, 0.009));
    // A crescent moon: one disc with another cut out of it.
    const mx = (u - 0.62), my = (v - 0.24) * 1.6;
    const moon = Math.hypot(mx, my) < 0.16 && Math.hypot(mx - 0.07, my + 0.03) > 0.14;
    if (moon) c = mix(hex('#FFF3C4'), hex('#E8D27A'), Math.hypot(mx, my) / 0.16);
    return c;
  },
  void(u, v) {
    let c = hex('#020104');
    // A spiral pulled into a point, violet at its arms.
    const px = u - 0.5, py = (v - 0.45) * 1.6;
    const r = Math.hypot(px, py), a = Math.atan2(py, px);
    const arm = 0.5 + 0.5 * Math.sin(a * 3 + Math.log(r + 0.001) * 9);
    c = mix(c, hex('#5B1FA8'), arm * smooth(0.45, 0.02, r) * 0.9);
    c = add(c, hex('#C79BFF'), Math.max(0, arm - 0.8) * 4 * smooth(0.35, 0.05, r));
    c = mix(c, hex('#000000'), smooth(0.06, 0.0, r));
    c = add(c, [200, 170, 255], stars(u, v, 0.1, 53, 0.008) * 0.6);
    return c;
  },
};

// ------------------------------------------------------------- laying it out

function paint(design) {
  const rgba = new Uint8ClampedArray(W * H * 4);
  const put = (x, y, c, k = 1) => {
    const i = (y * W + x) * 4;
    rgba[i] = c[0] * k; rgba[i + 1] = c[1] * k; rgba[i + 2] = c[2] * k; rgba[i + 3] = 255;
  };
  // A rectangle of the texture (in 64x32 units) filled from the design, u and v over the rectangle.
  const face = (x0, y0, w, h, k = 1, mirror = false) => {
    for (let y = y0 * S; y < (y0 + h) * S; y++) for (let x = x0 * S; x < (x0 + w) * S; x++) {
      let u = (x - x0 * S + 0.5) / (w * S);
      const v = (y - y0 * S + 0.5) / (h * S);
      if (mirror) u = 1 - u;
      put(x, y, design(u, v), k);
    }
  };
  // The edges take the colour of the face's border, a little darker.
  const edge = (x0, y0, w, h, sample, k) => {
    for (let y = y0 * S; y < (y0 + h) * S; y++) for (let x = x0 * S; x < (x0 + w) * S; x++) {
      const t = h > w ? (y - y0 * S + 0.5) / (h * S) : (x - x0 * S + 0.5) / (w * S);
      put(x, y, sample(t), k);
    }
  };
  face(1, 1, 10, 16);
  face(12, 1, 10, 16, 0.72, true);
  edge(0, 1, 1, 16, (t) => design(0, t), 0.8);
  edge(11, 1, 1, 16, (t) => design(1, t), 0.8);
  edge(1, 0, 10, 1, (t) => design(t, 0), 0.85);
  edge(11, 0, 10, 1, (t) => design(t, 1), 0.7);
  // The elytra's wings: the design again, over the whole wing area.
  face(22, 0, 24, 22, 0.95);
  return rgba;
}

function preview(design) {
  const w = 10 * S, h = 16 * S;
  const rgba = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const c = design((x + 0.5) / w, (y + 0.5) / h);
    const i = (y * w + x) * 4;
    rgba[i] = c[0]; rgba[i + 1] = c[1]; rgba[i + 2] = c[2]; rgba[i + 3] = 255;
  }
  return { w, h, rgba };
}

mkdirSync(join(OUT, 'cape'), { recursive: true });
mkdirSync(join(OUT, 'cape_preview'), { recursive: true });
for (const [id, design] of Object.entries(CAPES)) {
  writeFileSync(join(OUT, 'cape', `${id}.png`), encode(W, H, paint(design)));
  const p = preview(design);
  writeFileSync(join(OUT, 'cape_preview', `${id}.png`), encode(p.w, p.h, p.rgba));
  console.log(`${id}: cape ${W}x${H}, preview ${p.w}x${p.h}`);
}
