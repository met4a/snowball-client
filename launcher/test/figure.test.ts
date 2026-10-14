import { describe, expect, it } from 'vitest';
import '../src/renderer/figure.js';

interface FigureSkin {
  width: number;
  height: number;
  data: ArrayLike<number>;
  slim: boolean;
}

/** The Skins page's figure, which the script leaves on the global object for app.js. */
const { SkinFigure } = globalThis as unknown as {
  SkinFigure: { draw(skin: FigureSkin | null, width: number, height: number, yaw?: number, into?: Uint8ClampedArray): Uint8ClampedArray };
};

/** A skin of one colour, with the columns a slim skin leaves clear when `slim`. */
function skin(rgb: [number, number, number], slim = false, height = 64): FigureSkin {
  const data = new Uint8ClampedArray(64 * height * 4);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < 64; x++) {
      const clear = slim && ((x >= 50 && x <= 51 && y >= 16 && y <= 19) || (x >= 54 && x <= 55 && y >= 20 && y <= 31));
      data.set(clear ? [0, 0, 0, 0] : [...rgb, 255], (y * 64 + x) * 4);
    }
  }
  return { width: 64, height, data, slim };
}

const W = 72;
const H = 120;
const pixel = (picture: Uint8ClampedArray, x: number, y: number) => Array.from(picture.subarray((y * W + x) * 4, (y * W + x) * 4 + 4));
/** Pixels the player covers outright (the shadow is never this solid). */
const solid = (picture: Uint8ClampedArray) => {
  let n = 0;
  for (let i = 3; i < picture.length; i += 4) if (picture[i] === 255) n++;
  return n;
};

describe('the skin figure', () => {
  it('draws a player in the middle of a clear picture', () => {
    const picture = SkinFigure.draw(null, W, H);
    expect(picture).toHaveLength(W * H * 4);
    expect(pixel(picture, 0, 0)[3]).toBe(0);
    expect(pixel(picture, W - 1, 0)[3]).toBe(0);
    // The body, a little above the middle, is solid.
    expect(pixel(picture, W / 2, Math.round(H * 0.45))[3]).toBe(255);
  });

  it('wears the skin it is given, lit from one side', () => {
    const picture = SkinFigure.draw(skin([200, 30, 30]), W, H);
    const [r, g, b, a] = pixel(picture, W / 2, Math.round(H * 0.45));
    expect(a).toBe(255);
    expect(r).toBeGreaterThan(70);
    expect(g).toBeLessThan(r / 2);
    expect(b).toBeLessThan(r / 2);
    // Nothing is ever brighter than the skin itself.
    for (let i = 0; i < picture.length; i += 4) expect(picture[i]).toBeLessThanOrEqual(200);
  });

  it('gives slim skins thinner arms than classic ones', () => {
    const classic = solid(SkinFigure.draw(skin([90, 140, 200]), W, H));
    const slim = solid(SkinFigure.draw(skin([90, 140, 200], true), W, H));
    expect(slim).toBeLessThan(classic);
    // Old 64x32 skins draw too, as classic.
    expect(solid(SkinFigure.draw(skin([90, 140, 200], false, 32), W, H))).toBeGreaterThan(0);
  });

  it('turns, and fills the buffer it is handed', () => {
    const front = SkinFigure.draw(skin([90, 140, 200]), W, H, 0);
    const into = new Uint8ClampedArray(W * H * 4);
    const turned = SkinFigure.draw(skin([90, 140, 200]), W, H, Math.PI / 2, into);
    expect(turned).toBe(into);
    expect(Buffer.from(turned).equals(Buffer.from(front))).toBe(false);
  });
});
