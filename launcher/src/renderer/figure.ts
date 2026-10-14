// A plain script, loaded before app.js: no imports or exports, so it needs no module loader.

/** A skin's pixels for the figure: straight RGBA as a canvas gives them, 64 wide and 64 or 32 high. */
interface FigureSkin {
  width: number;
  height: number;
  data: ArrayLike<number>;
  slim: boolean;
}

/**
 * Draws a player wearing a skin, in plain pixels: a port of the game's emote figure
 * (client/src/main/java/dev/snowballclient/client/emote/Figure.java). The same boxes, pivots and
 * skin layout as Minecraft's player model, slim arms and the outer layer, seen from a little above,
 * lit from the upper left, depth-tested, with 2x2 samples a pixel for smooth edges, standing on a
 * soft shadow. Pixels in, pixels out: no 3D library, and it can be tested away from a window.
 */
const SkinFigure = (() => {
  /** Model pixels the picture's height covers: the top of the hat down to the shadow at the feet. */
  const SPAN = 38;
  /** The model's middle over that height. */
  const MIDDLE_Y = 8.5;
  const VIEW_YAW = (-18 * Math.PI) / 180;
  const VIEW_PITCH = (12 * Math.PI) / 180;
  /** Towards the light, in view space (x right, y down, z away from the viewer). */
  const LIGHT = normalize(-0.5, -0.7, -0.6);
  /** How dark the faces turned away from the light get. */
  const AMBIENT = 0.42;
  /** Pixels are worked out at this many times the size and averaged, for smooth edges. */
  const SAMPLES = 2;
  /** The figure's colour without a skin. */
  const PLAIN = [0xe6, 0xe9, 0xf0];
  /** The arms hang a little away from the body, so slim and classic arms tell apart at a glance. */
  const ARM_SPREAD = 0.07;

  const HEAD = 0, BODY = 1, RIGHT_ARM = 2, LEFT_ARM = 3, RIGHT_LEG = 4, LEFT_LEG = 5;

  /** One box of the model: where it turns, its corner and size, where its skin starts, and how it is drawn. */
  interface Box {
    part: number;
    px: number; py: number; pz: number;
    x: number; y: number; z: number;
    w: number; h: number; d: number;
    u: number; v: number;
    grow: number;
    outer: boolean;
    mirror: boolean;
  }

  /** Box corners, as bits of the index: x from bit 0, y from bit 1, z from bit 2; then the face's normal. */
  const FACES = [
    [0, 2, 6, 4, -1, 0, 0], [1, 5, 7, 3, 1, 0, 0],
    [0, 4, 5, 1, 0, -1, 0], [2, 3, 7, 6, 0, 1, 0],
    [0, 1, 3, 2, 0, 0, -1], [4, 6, 7, 5, 0, 0, 1],
  ];

  const box = (part: number, px: number, py: number, pz: number, x: number, y: number, z: number, w: number, h: number, d: number,
    u: number, v: number, grow = 0, outer = false, mirror = false): Box => ({ part, px, py, pz, x, y, z, w, h, d, u, v, grow, outer, mirror });

  /** The model's boxes: the six parts, and with a skin their outer layer, with slim arms where the skin uses them. */
  function boxes(skin: Pick<FigureSkin, 'height' | 'slim'> | null): Box[] {
    const slim = skin !== null && skin.slim;
    const legacy = skin !== null && skin.height === 32;
    const arm = slim ? 3 : 4;
    const armY = slim ? 2.5 : 2;
    const out = [
      box(HEAD, 0, 0, 0, -4, -8, -4, 8, 8, 8, 0, 0),
      box(BODY, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16),
      box(RIGHT_ARM, -5, armY, 0, -arm + 1, -2, -2, arm, 12, 4, 40, 16),
      box(LEFT_ARM, 5, armY, 0, -1, -2, -2, arm, 12, 4, legacy ? 40 : 32, legacy ? 16 : 48, 0, false, legacy),
      box(RIGHT_LEG, -1.9, 12, 0, -2, 0, -2, 4, 12, 4, 0, 16),
      box(LEFT_LEG, 1.9, 12, 0, -2, 0, -2, 4, 12, 4, legacy ? 0 : 16, legacy ? 16 : 48, 0, false, legacy),
    ];
    if (skin === null) return out;
    out.push(box(HEAD, 0, 0, 0, -4, -8, -4, 8, 8, 8, 32, 0, 0.5, true));
    if (legacy) return out;
    out.push(
      box(BODY, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 32, 0.25, true),
      box(RIGHT_ARM, -5, armY, 0, -arm + 1, -2, -2, arm, 12, 4, 40, 32, 0.25, true),
      box(LEFT_ARM, 5, armY, 0, -1, -2, -2, arm, 12, 4, 48, 48, 0.25, true),
      box(RIGHT_LEG, -1.9, 12, 0, -2, 0, -2, 4, 12, 4, 0, 32, 0.25, true),
      box(LEFT_LEG, 1.9, 12, 0, -2, 0, -2, 4, 12, 4, 0, 48, 0.25, true),
    );
    return out;
  }

  /** How each part is turned (x, y, z): standing, arms a touch apart. */
  function pose(part: number): [number, number, number] {
    if (part === RIGHT_ARM) return [0, 0, ARM_SPREAD];
    if (part === LEFT_ARM) return [0, 0, -ARM_SPREAD];
    return [0, 0, 0];
  }

  /**
   * Fills `st` with where each corner of face `f` reads the skin, and returns that face's area of
   * the skin (left, top, right, bottom). Minecraft lays a box out with its top and bottom above,
   * then its right side, front, left side and back in a row.
   */
  function uv(b: Box, f: number, st: Float32Array): [number, number, number, number] {
    const { u, v, w, h, d } = b;
    // A mirrored box (the left limbs of old skins) reads its right-hand twin back to front.
    const face = b.mirror && f < 2 ? 1 - f : f;
    for (let corner = 0; corner < 8; corner++) {
      let x = corner & 1 ? w : 0;
      const y = corner & 2 ? h : 0;
      const z = corner & 4 ? d : 0;
      if (b.mirror) x = w - x;
      let s: number;
      let t: number;
      switch (face) {
        case 0: s = u + (d - z); t = v + d + y; break;
        case 1: s = u + d + w + z; t = v + d + y; break;
        case 2: s = u + d + x; t = v + (d - z); break;
        case 3: s = u + d + w + x; t = v + (d - z); break;
        case 4: s = u + d + x; t = v + d + y; break;
        default: s = u + 2 * d + w + (w - x); t = v + d + y;
      }
      st[corner * 2] = s;
      st[corner * 2 + 1] = t;
    }
    switch (face) {
      case 0: return [u, v + d, u + d, v + d + h];
      case 1: return [u + d + w, v + d, u + 2 * d + w, v + d + h];
      case 2: return [u + d, v, u + d + w, v + d];
      case 3: return [u + d + w, v, u + d + 2 * w, v + d];
      case 4: return [u + d, v + d, u + d + w, v + d + h];
      default: return [u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h];
    }
  }

  /** Minecraft's part rotation: z, then y, then x (applied to the point in the reverse order). */
  function rotate(x: number, y: number, z: number, rx: number, ry: number, rz: number): [number, number, number] {
    let c = Math.cos(rx), s = Math.sin(rx);
    const y1 = y * c - z * s;
    const z1 = y * s + z * c;
    c = Math.cos(ry);
    s = Math.sin(ry);
    const x2 = x * c + z1 * s;
    const z2 = -x * s + z1 * c;
    c = Math.cos(rz);
    s = Math.sin(rz);
    return [x2 * c - y1 * s, x2 * s + y1 * c, z2];
  }

  /** Turns a model point into view space: the spin, then a three-quarter view from a little above. */
  function view(v: [number, number, number], spin: number): [number, number, number] {
    const r = rotate(v[0], v[1], v[2], 0, spin + VIEW_YAW, 0);
    return rotate(r[0], r[1], r[2], VIEW_PITCH, 0, 0);
  }

  function normalize(x: number, y: number, z: number): [number, number, number] {
    const len = Math.hypot(x, y, z);
    return [x / len, y / len, z / len];
  }

  /**
   * Working buffers by size, kept between frames so turning the figure makes no garbage. The cards
   * and the preview are different sizes, so a few are kept rather than one swapped back and forth.
   */
  const scratch = new Map<number, { depth: Float32Array; colour: Uint32Array }>();

  function buffers(size: number): { depth: Float32Array; colour: Uint32Array } {
    let found = scratch.get(size);
    if (!found) {
      if (scratch.size >= 3) scratch.delete(scratch.keys().next().value!);
      found = { depth: new Float32Array(size), colour: new Uint32Array(size) };
      scratch.set(size, found);
    }
    return found;
  }

  /**
   * Fills one triangle, keeping whatever is nearest; the outer layer leaves its see-through pixels
   * out. The weights, depth and skin position all change by a fixed step from one pixel to the
   * next, so each is stepped rather than worked out afresh: dragging the preview needs the speed.
   */
  function fill(s: Float32Array, st: Float32Array, i0: number, i1: number, i2: number, bw: number, bh: number,
    depth: Float32Array, colour: Uint32Array, lit: number, skin: FigureSkin | null, outer: boolean, region: number[] | null): void {
    const x0 = s[i0 * 3], y0 = s[i0 * 3 + 1], z0 = s[i0 * 3 + 2];
    const x1 = s[i1 * 3], y1 = s[i1 * 3 + 1], z1 = s[i1 * 3 + 2];
    const x2 = s[i2 * 3], y2 = s[i2 * 3 + 1], z2 = s[i2 * 3 + 2];
    const area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
    if (Math.abs(area) < 1e-6) return;
    const minX = Math.max(0, Math.floor(Math.min(x0, x1, x2)));
    const maxX = Math.min(bw - 1, Math.ceil(Math.max(x0, x1, x2)));
    const minY = Math.max(0, Math.floor(Math.min(y0, y1, y2)));
    const maxY = Math.min(bh - 1, Math.ceil(Math.max(y0, y1, y2)));
    // w0 = a0 * px + b0 * py + c0, and likewise w1; w2 is what is left of 1.
    const a0 = (y1 - y2) / area, b0 = (x2 - x1) / area, c0 = (x1 * y2 - x2 * y1) / area;
    const a1 = (y2 - y0) / area, b1 = (x0 - x2) / area, c1 = (x2 * y0 - x0 * y2) / area;
    // Depth and the skin position are flat across the triangle as well: ax * px + ay * py + ac.
    const plane = (q0: number, q1: number, q2: number): [number, number, number] =>
      [a0 * (q0 - q2) + a1 * (q1 - q2), b0 * (q0 - q2) + b1 * (q1 - q2), c0 * (q0 - q2) + c1 * (q1 - q2) + q2];
    const [zx, zy, zc] = plane(z0, z1, z2);
    const [ux, uy, uc] = plane(st[i0 * 2], st[i1 * 2], st[i2 * 2]);
    const [vx, vy, vc] = plane(st[i0 * 2 + 1], st[i1 * 2 + 1], st[i2 * 2 + 1]);
    const texels = skin && region ? skin.data : null;
    const texWidth = skin ? skin.width : 0;
    // Stay inside the face's own area so neighbouring faces do not bleed in at the edges.
    const uMin = region ? region[0] : 0, uMax = region ? region[2] - 0.001 : 0;
    const vMin = region ? region[1] : 0, vMax = region ? region[3] - 0.001 : 0;
    const plain = shade(PLAIN[0], PLAIN[1], PLAIN[2], lit);
    for (let y = minY; y <= maxY; y++) {
      const py = y + 0.5;
      const px = minX + 0.5;
      let w0 = a0 * px + b0 * py + c0;
      let w1 = a1 * px + b1 * py + c1;
      let z = zx * px + zy * py + zc;
      let u = ux * px + uy * py + uc;
      let v = vx * px + vy * py + vc;
      let i = y * bw + minX;
      for (let x = minX; x <= maxX; x++, i++, w0 += a0, w1 += a1, z += zx, u += ux, v += vx) {
        if (w0 < 0 || w1 < 0 || 1 - w0 - w1 < 0 || z >= depth[i]) continue;
        let rgb = plain;
        if (texels) {
          const k = (((v < vMin ? vMin : v > vMax ? vMax : v) | 0) * texWidth + ((u < uMin ? uMin : u > uMax ? uMax : u) | 0)) * 4;
          if (outer && texels[k + 3] < 128) continue;
          rgb = shade(texels[k], texels[k + 1], texels[k + 2], lit);
        }
        depth[i] = z;
        colour[i] = rgb;
      }
    }
  }

  /**
   * A colour darkened by the light falling on its face, packed as 0xRRGGBB. `lit` is never above 1,
   * so nothing passes 255, and adding a half before the bits are cut off rounds to the nearest.
   */
  function shade(r: number, g: number, b: number, lit: number): number {
    return ((r * lit + 0.5) << 16) | ((g * lit + 0.5) << 8) | ((b * lit + 0.5) | 0);
  }

  /**
   * The player as straight RGBA pixels, `width` by `height` (about 3 wide to 5 high suits it),
   * turned `yaw` radians from the usual three-quarter view. `skin` may be null for a plain figure.
   * `into`, when given, must hold width * height * 4 bytes and is filled and returned.
   */
  function draw(skin: FigureSkin | null, width: number, height: number, yaw = 0, into?: Uint8ClampedArray): Uint8ClampedArray {
    const bw = width * SAMPLES;
    const bh = height * SAMPLES;
    const { depth, colour } = buffers(bw * bh);
    depth.fill(Infinity);
    const scale = bh / SPAN;
    const screen = new Float32Array(8 * 3);
    const st = new Float32Array(8 * 2);
    for (const b of boxes(skin)) {
      const [rx, ry, rz] = pose(b.part);
      for (let corner = 0; corner < 8; corner++) {
        const x = b.x - b.grow + (corner & 1 ? b.w + 2 * b.grow : 0);
        const y = b.y - b.grow + (corner & 2 ? b.h + 2 * b.grow : 0);
        const z = b.z - b.grow + (corner & 4 ? b.d + 2 * b.grow : 0);
        const r = rotate(x, y, z, rx, ry, rz);
        const v = view([r[0] + b.px, r[1] + b.py, r[2] + b.pz], yaw);
        screen[corner * 3] = bw / 2 + v[0] * scale;
        screen[corner * 3 + 1] = bh / 2 + (v[1] - MIDDLE_Y) * scale;
        screen[corner * 3 + 2] = v[2];
      }
      for (let f = 0; f < FACES.length; f++) {
        const face = FACES[f];
        const n = view(rotate(face[4], face[5], face[6], rx, ry, rz), yaw);
        // Faces turned away from the viewer are hidden behind the box's other faces.
        if (n[2] >= 0) continue;
        const lit = AMBIENT + (1 - AMBIENT) * Math.max(0, n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]);
        const region = skin ? uv(b, f, st) : null;
        fill(screen, st, face[0], face[1], face[2], bw, bh, depth, colour, lit, skin, b.outer, region);
        fill(screen, st, face[0], face[2], face[3], bw, bh, depth, colour, lit, skin, b.outer, region);
      }
    }
    const ground = view([0, 24, 0], 0);
    return resolve(colour, depth, width, height, bw / 2 + ground[0] * scale, bh / 2 + (ground[1] - MIDDLE_Y) * scale, scale, into);
  }

  /** Averages each block of samples into one pixel (coverage becomes alpha), over a soft oval shadow at the feet. */
  function resolve(colour: Uint32Array, depth: Float32Array, width: number, height: number, groundX: number, groundY: number,
    scale: number, into?: Uint8ClampedArray): Uint8ClampedArray {
    const bw = width * SAMPLES;
    const out = into && into.length === width * height * 4 ? into : new Uint8ClampedArray(width * height * 4);
    const rx = (8.5 * scale) / SAMPLES;
    const ry = (2.6 * scale) / SAMPLES;
    const gx = groundX / SAMPLES;
    const gy = groundY / SAMPLES;
    for (let y = 0; y < height; y++) {
      const dy = (y + 0.5 - gy) / ry;
      // Rows above and below the shadow's oval skip working it out pixel by pixel.
      const shadowRow = dy * dy < 1;
      for (let x = 0; x < width; x++) {
        let covered = 0;
        let r = 0, g = 0, b = 0;
        for (let sy = 0; sy < SAMPLES; sy++) {
          for (let sx = 0; sx < SAMPLES; sx++) {
            const i = (y * SAMPLES + sy) * bw + x * SAMPLES + sx;
            if (depth[i] === Infinity) continue;
            covered++;
            r += (colour[i] >> 16) & 0xff;
            g += (colour[i] >> 8) & 0xff;
            b += colour[i] & 0xff;
          }
        }
        const a = covered / (SAMPLES * SAMPLES);
        let shadow = 0;
        if (shadowRow) {
          const dx = (x + 0.5 - gx) / rx;
          const falloff = Math.max(0, 1 - (dx * dx + dy * dy));
          shadow = 0.5 * falloff * falloff;
        }
        // The figure over its shadow (straight alpha, source over).
        const outA = a + shadow * (1 - a);
        const o = (y * width + x) * 4;
        if (outA <= 0.002) {
          out[o] = out[o + 1] = out[o + 2] = out[o + 3] = 0;
          continue;
        }
        const k = covered === 0 ? 0 : a / outA / covered;
        out[o] = Math.round(r * k);
        out[o + 1] = Math.round(g * k);
        out[o + 2] = Math.round(b * k);
        out[o + 3] = Math.round(outA * 255);
      }
    }
    return out;
  }

  return { draw, boxes };
})();

// The window finds SkinFigure as a global of this script; the tests, which load it as a module, find it here.
(globalThis as unknown as { SkinFigure: typeof SkinFigure }).SkinFigure = SkinFigure;
