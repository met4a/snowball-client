import { utimesSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { JavaManager } from '../src/core/java/JavaManager.js';
import { tempDir } from './helpers.js';

const PROPERTIES = '    java.version = 21.0.4\n    java.vendor = Eclipse Adoptium\n    os.arch = amd64\n    sun.arch.data.model = 64\n';

describe('Java probes', () => {
  it('are remembered between launcher runs while the executable is unchanged', async () => {
    const dir = tempDir();
    const java = join(dir, 'java.exe');
    writeFileSync(java, 'not really java');
    const cache = join(dir, 'java-probes.json');
    let probes = 0;
    const probe = async () => {
      probes++;
      return PROPERTIES;
    };

    const first = new JavaManager(join(dir, 'runtimes'), undefined, probe, cache);
    expect((await first.inspect(java))?.majorVersion).toBe(21);
    expect(probes).toBe(1);
    await new Promise((r) => setTimeout(r, 400)); // the cache is written shortly after, not on every probe

    // The next run of the launcher: nothing in memory, the cache file on disk.
    const second = new JavaManager(join(dir, 'runtimes'), undefined, probe, cache);
    expect((await second.inspect(java))?.majorVersion).toBe(21);
    expect(probes).toBe(1);

    // Java updated in place: the old answer no longer applies.
    utimesSync(java, new Date(), new Date(Date.now() + 60_000));
    const third = new JavaManager(join(dir, 'runtimes'), undefined, probe, cache);
    expect((await third.inspect(java))?.majorVersion).toBe(21);
    expect(probes).toBe(2);
  });
});
