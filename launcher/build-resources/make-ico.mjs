/**
 * Packs the PNGs make-icon.ps1 rendered into a single multi-size .ico.
 *
 * An .ico is a small directory followed by the images themselves. Since Vista each image may be a
 * PNG rather than a raw bitmap, which is what this writes: no palettes, no AND mask, and the alpha
 * comes through intact.
 *
 *   node build-resources/make-ico.mjs
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const here = dirname(fileURLToPath(import.meta.url));
const from = join(tmpdir(), 'snowball-icon');
const sizes = [16, 24, 32, 48, 64, 128, 256];

const images = sizes.map((size) => ({ size, data: readFileSync(join(from, `${size}.png`)) }));

const HEADER = 6;
const ENTRY = 16;
const dir = Buffer.alloc(HEADER + ENTRY * images.length);
dir.writeUInt16LE(0, 0); // reserved
dir.writeUInt16LE(1, 2); // 1 = icon
dir.writeUInt16LE(images.length, 4);

let offset = dir.length;
images.forEach((image, i) => {
  const at = HEADER + ENTRY * i;
  // 256 is written as 0: the field is one byte, so 256 does not fit and 0 means "256".
  dir.writeUInt8(image.size >= 256 ? 0 : image.size, at);
  dir.writeUInt8(image.size >= 256 ? 0 : image.size, at + 1);
  dir.writeUInt8(0, at + 2); // colours in palette: 0 for true colour
  dir.writeUInt8(0, at + 3); // reserved
  dir.writeUInt16LE(1, at + 4); // colour planes
  dir.writeUInt16LE(32, at + 6); // bits per pixel
  dir.writeUInt32LE(image.data.length, at + 8);
  dir.writeUInt32LE(offset, at + 12);
  offset += image.data.length;
});

const dest = join(here, 'installer', 'icon.ico');
mkdirSync(dirname(dest), { recursive: true });
writeFileSync(dest, Buffer.concat([dir, ...images.map((i) => i.data)]));
console.log(`wrote ${dest} (${sizes.join(', ')}) - ${(offset / 1024).toFixed(1)} KB`);
