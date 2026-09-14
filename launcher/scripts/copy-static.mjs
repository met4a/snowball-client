// Copies non-TypeScript renderer files (HTML, CSS, images, fonts) into dist after tsc runs.
import { cpSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const pairs = [
  ['src/renderer/index.html', 'dist/renderer/index.html'],
  ['src/renderer/styles.css', 'dist/renderer/styles.css'],
  ['src/renderer/assets', 'dist/renderer/assets'],
];
for (const [from, to] of pairs) {
  mkdirSync(dirname(join(root, to)), { recursive: true });
  cpSync(join(root, from), join(root, to), { recursive: true });
}
console.log(`copied ${pairs.length} static entries`);
