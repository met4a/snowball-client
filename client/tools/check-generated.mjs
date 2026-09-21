/**
 * Checks that Stonecutter actually rewrote the per-version sources.
 *
 * src/ is written for 26.2 and older versions are generated from it by string replacement. When a
 * rule does not match, the generated file silently keeps a 26.x name. Ordinary code fails loudly
 * at that point — it will not compile against 1.21.x. Mixin selectors do not: `method = "..."` is
 * just a string, so the build succeeds, the jar ships, and Minecraft crashes on startup with
 * "could not find any targets matching ...".
 *
 * That is exactly how 1.6.0 shipped a client that would not load: a rename rule matches on
 * "name(", and one @Inject selector was written as a bare name with no parenthesis, so it was
 * never rewritten.
 *
 * This runs after generation and before packaging, and fails the build instead.
 *
 *   node client/tools/check-generated.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const versionsDir = join(here, '..', 'versions');

/**
 * Names that exist only on 26.x. Finding one in a generated source for an older version means a
 * replacement rule did not match.
 */
const MODERN_ONLY = [
  'GuiGraphicsExtractor',
  'PlayerFaceExtractor',
  'extractBlurredBackground',
  'extractMenuBackground',
  'extractPanorama',
  'extractBackground',
  'extractImage',
  'extractRenderState',
  'nonEmptyItemCopyStream',
  'addClientSystemMessage',
  'KeyMappingHelper',
  'ClientTooltipComponentCallback',
  'afterExtract',
];

/** Versions that are generated from the 26.2 tree, and so must not contain the names above. */
const isGeneratedOlder = (name) => /^1\./.test(name);

function* javaFiles(dir) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) yield* javaFiles(full);
    else if (entry.endsWith('.java')) yield full;
  }
}

if (!existsSync(versionsDir)) {
  console.log('No generated versions yet; nothing to check.');
  process.exit(0);
}

const problems = [];
let checked = 0;

let looked = 0;
for (const version of readdirSync(versionsDir)) {
  if (!isGeneratedOlder(version)) continue;
  // Stonecutter writes the rewritten tree under the version's build directory.
  const src = join(versionsDir, version, 'build', 'generated', 'stonecutter', 'main', 'java');
  if (!existsSync(src)) continue;
  looked++;

  for (const file of javaFiles(src)) {
    checked++;
    const text = readFileSync(file, 'utf8');
    for (const name of MODERN_ONLY) {
      if (!text.includes(name)) continue;
      const line = text.split(/\r?\n/).findIndex((l) => l.includes(name)) + 1;
      problems.push(`${relative(versionsDir, file)}:${line}  still says "${name}"`);
    }
  }
}

if (problems.length) {
  console.error(`\nStonecutter left ${problems.length} 26.x name(s) in generated sources:\n`);
  for (const p of problems) console.error(`  ${p}`);
  console.error(
    '\nAdd or fix a rule in client/stonecutter.gradle.kts. Note the rename rules match on "name(",' +
      '\nso a mixin selector written as a bare method name is never rewritten - give it the full' +
      '\ndescriptor, e.g. method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V".\n',
  );
  process.exit(1);
}

// A check that silently inspects nothing is worse than no check, so say so loudly.
if (looked === 0 || checked === 0) {
  console.error(
    'Found no generated sources to check. Run the Stonecutter build first\n' +
      '(./gradlew :1.21.11:stonecutterGenerate), or fix the path in this script.',
  );
  process.exit(1);
}

console.log(`Generated sources look right (${checked} files across ${looked} version(s), no 26.x names left).`);
