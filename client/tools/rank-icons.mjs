/**
 * The rank emblems, drawn from geometry rather than stored as bitmaps.
 *
 * Hand-drawn pixel art has to be redrawn for every size it is used at, and the badges need three:
 * the player list draws them tiny, the launcher draws them at chip size, and the preview sheet
 * draws them large. Describing each emblem as polygons means one definition renders sharply at all
 * of them, and a shape can be adjusted without re-pixelling it.
 *
 * Every emblem follows the same rules, which is what makes the set look like a set:
 *   - a dark outline, so it separates from the pale ball it sits on
 *   - the rank's own colour as the body, shaded lighter towards the top-left
 *   - one white accent, placed where the eye lands first
 */

/** Supersampling factor. Shapes are rasterised big and reduced, which gives clean diagonals. */
const SS = 4;

const hex = (c) => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
const lighten = ([r, g, b], t) => [r + (255 - r) * t, g + (255 - g) * t, b + (255 - b) * t];
const darken = ([r, g, b], t) => [r * (1 - t), g * (1 - t), b * (1 - t)];

/** Is (x, y) inside the polygon? Even-odd crossing test. */
function inside(poly, x, y) {
  let hit = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i];
    const [xj, yj] = poly[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) hit = !hit;
  }
  return hit;
}

const inCircle = (cx, cy, r, x, y) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r;

/**
 * Each emblem is a function over unit space (0..1 in both axes) answering, for a point:
 * 'body', 'accent' or null. Working in unit space is what lets one definition serve every size.
 */
const EMBLEMS = {
  /** A shield with a cross cut into it — the classic staff mark. */
  staff(x, y) {
    const shield = [[0.1, 0.06], [0.9, 0.06], [0.9, 0.52], [0.5, 0.96], [0.1, 0.52]];
    if (!inside(shield, x, y)) return null;
    const bar = Math.abs(x - 0.5) < 0.1 && y > 0.18 && y < 0.68;
    const arm = Math.abs(y - 0.35) < 0.1 && x > 0.22 && x < 0.78;
    return bar || arm ? 'accent' : 'body';
  },

  /**
   * A crown: three broad points on a thick band. Deliberately chunky — at badge size a crown with
   * fine spires collapses into a white smudge, so the points are wide and the valleys shallow.
   */
  owner(x, y) {
    const band = y > 0.66 && y < 0.94 && x > 0.06 && x < 0.94;
    if (band) return Math.abs(x - 0.5) < 0.12 && y < 0.86 ? 'accent' : 'body';
    const points = [[0.06, 0.72], [0.06, 0.22], [0.28, 0.5], [0.5, 0.1], [0.72, 0.5], [0.94, 0.22], [0.94, 0.72]];
    if (!inside(points, x, y)) return null;
    return y < 0.34 && Math.abs(x - 0.5) < 0.12 ? 'accent' : 'body';
  },

  /** A five-pointed star. */
  partner(x, y) {
    const pts = [];
    for (let i = 0; i < 10; i++) {
      const a = -Math.PI / 2 + (i * Math.PI) / 5;
      const r = i % 2 === 0 ? 0.46 : 0.19;
      pts.push([0.5 + Math.cos(a) * r, 0.5 + Math.sin(a) * r * 1.02]);
    }
    if (!inside(pts, x, y)) return null;
    return inCircle(0.44, 0.42, 0.11, x, y) ? 'accent' : 'body';
  },

  /** A round-bottomed flask with a liquid line. */
  tester(x, y) {
    const neck = x > 0.38 && x < 0.62 && y > 0.06 && y < 0.34;
    const lip = x > 0.32 && x < 0.68 && y > 0.06 && y < 0.15;
    const body = inCircle(0.5, 0.64, 0.32, x, y);
    const shoulders = inside([[0.38, 0.3], [0.62, 0.3], [0.82, 0.66], [0.18, 0.66]], x, y);
    if (!(neck || lip || body || shoulders)) return null;
    if (lip) return 'accent';
    // The liquid's surface, and a bubble in it.
    if (body && y > 0.58 && y < 0.66) return 'accent';
    if (inCircle(0.4, 0.74, 0.06, x, y)) return 'accent';
    return 'body';
  },

  /** Angle brackets: the developer mark. Two chevrons, mirrored about the centre. */
  developer(x, y) {
    const thickness = 0.1;
    // Distance from a point to a line segment.
    const seg = (px, py, ax, ay, bx, by) => {
      const dx = bx - ax, dy = by - ay;
      const u = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
      return Math.hypot(px - (ax + u * dx), py - (ay + u * dy));
    };
    // '<' : tip at the left, arms opening to the right. The arms stop well short of the centre so
    // the two chevrons keep a clear gap instead of merging into a diamond.
    const left = Math.min(seg(x, y, 0.06, 0.5, 0.33, 0.15), seg(x, y, 0.06, 0.5, 0.33, 0.85));
    // '>' : the mirror of it.
    const right = Math.min(seg(x, y, 0.94, 0.5, 0.67, 0.15), seg(x, y, 0.94, 0.5, 0.67, 0.85));
    if (left < thickness) return x < 0.24 ? 'accent' : 'body';
    if (right < thickness) return 'body';
    return null;
  },

  /** A beetle, which reads better at small sizes than a pair of wings. */
  bug_hunter(x, y) {
    const body = inCircle(0.5, 0.58, 0.33, x, y) || (Math.abs(x - 0.5) < 0.33 && y > 0.36 && y < 0.6);
    const head = inCircle(0.5, 0.26, 0.16, x, y);
    const legs =
      (Math.abs(y - 0.42) < 0.055 && (x > 0.06 && x < 0.24)) ||
      (Math.abs(y - 0.42) < 0.055 && (x > 0.76 && x < 0.94)) ||
      (Math.abs(y - 0.68) < 0.055 && (x > 0.06 && x < 0.26)) ||
      (Math.abs(y - 0.68) < 0.055 && (x > 0.74 && x < 0.94));
    if (!(body || head || legs)) return null;
    // The split down the shell, and two eyes.
    if (body && Math.abs(x - 0.5) < 0.055 && y > 0.36) return 'accent';
    if (head && (inCircle(0.43, 0.24, 0.05, x, y) || inCircle(0.57, 0.24, 0.05, x, y))) return 'accent';
    return 'body';
  },
};

/**
 * Renders an emblem to an RGBA grid of `size` pixels.
 *
 * @param {string} name   key in EMBLEMS
 * @param {string} colour the rank's colour, as #rrggbb
 * @param {number} size   width and height in pixels
 */
export function renderEmblem(name, colour, size) {
  const shape = EMBLEMS[name];
  if (!shape) throw new Error(`No emblem called ${name}`);
  const base = hex(colour);
  const body = lighten(base, 0.08);
  const shade = darken(base, 0.3);
  const line = darken(base, 0.72);

  // Supersample: for each pixel, how much of it is body, accent, or empty.
  const grid = Array.from({ length: size }, () => Array.from({ length: size }, () => [0, 0, 0, 0]));
  const kind = Array.from({ length: size }, () => Array.from({ length: size }, () => null));
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      let hitBody = 0, hitAccent = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const u = (px + (sx + 0.5) / SS) / size;
          const v = (py + (sy + 0.5) / SS) / size;
          const r = shape(u, v);
          if (r === 'accent') hitAccent++;
          else if (r === 'body') hitBody++;
        }
      }
      const total = SS * SS;
      // A pixel belongs to the emblem once it is at least a third covered: pixel art has no
      // partial coverage, and a hard threshold keeps edges crisp instead of fuzzy.
      if (hitAccent + hitBody < total / 3) continue;
      kind[py][px] = hitAccent > hitBody ? 'accent' : 'body';
    }
  }

  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      if (!kind[py][px]) continue;
      // Lit from the top-left, the same direction the snowball is lit from.
      const lit = 1 - (px / size) * 0.35 - (py / size) * 0.35;
      const tone = kind[py][px] === 'accent' ? [255, 255, 255] : lit > 0.72 ? lighten(body, 0.22) : lit > 0.5 ? body : shade;
      grid[py][px] = [Math.round(tone[0]), Math.round(tone[1]), Math.round(tone[2]), 255];
    }
  }

  // Trace the outline outside the shape so the emblem never loses weight to it.
  const out = Array.from({ length: size + 2 }, () => Array.from({ length: size + 2 }, () => [0, 0, 0, 0]));
  for (let py = 0; py < size; py++) for (let px = 0; px < size; px++) out[py + 1][px + 1] = grid[py][px];
  const rim = [];
  for (let py = 0; py < size + 2; py++) {
    for (let px = 0; px < size + 2; px++) {
      if (out[py][px][3] === 255) continue;
      let touches = false;
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, -1], [1, -1], [-1, 1]]) {
        const ny = py + dy, nx = px + dx;
        if (ny < 0 || nx < 0 || ny >= size + 2 || nx >= size + 2) continue;
        if (out[ny][nx][3] === 255) { touches = true; break; }
      }
      if (touches) rim.push([px, py]);
    }
  }
  for (const [px, py] of rim) out[py][px] = [Math.round(line[0]), Math.round(line[1]), Math.round(line[2]), 255];
  return out;
}

export const EMBLEM_NAMES = Object.keys(EMBLEMS);
