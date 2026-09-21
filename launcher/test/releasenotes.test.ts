import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { notesFor, parseChangelog } from '../src/main/releaseNotes.js';

const SAMPLE = `# What's new

Intro prose that is not part of any release.

## 1.6.0

### What's new
- First thing.
- A thing that wraps onto
  a second line.

### Fixed
- A bug.

## 1.5.1

### Fixed
- Chat would not connect.

## Nothing here
`;

describe('parseChangelog', () => {
  const notes = parseChangelog(SAMPLE);

  it('finds each released version in order', () => {
    expect(notes.map((n) => n.version)).toEqual(['1.6.0', '1.5.1']);
  });

  it('keeps sections and their items', () => {
    expect(notes[0].sections.map((s) => s.heading)).toEqual(["What's new", 'Fixed']);
    expect(notes[0].sections[1].items).toEqual(['A bug.']);
  });

  it('joins a bullet that wraps onto the next line', () => {
    expect(notes[0].sections[0].items[1]).toBe('A thing that wraps onto a second line.');
  });

  it('drops a version heading with no items under it', () => {
    expect(notes.find((n) => n.version === 'Nothing here')).toBeUndefined();
  });

  it('ignores prose before the first version', () => {
    expect(JSON.stringify(notes)).not.toContain('Intro prose');
  });

  it('returns nothing rather than throwing on junk', () => {
    expect(parseChangelog('')).toEqual([]);
    expect(parseChangelog('just some text\n\nwith no headings')).toEqual([]);
  });
});

describe('notesFor', () => {
  const notes = parseChangelog(SAMPLE);

  it('matches with or without a v prefix', () => {
    expect(notesFor(notes, '1.6.0')?.version).toBe('1.6.0');
    expect(notesFor(notes, 'v1.6.0')?.version).toBe('1.6.0');
  });

  it('returns null for a version with no notes', () => {
    expect(notesFor(notes, '9.9.9')).toBeNull();
  });
});

describe('the changelog that actually ships', () => {
  // Guards the real file: a release whose notes fail to parse would ship a blank
  // "What's new" screen, which is worse than no button at all.
  const notes = parseChangelog(readFileSync(join(__dirname, '..', '..', 'CHANGELOG.md'), 'utf8'));

  it('parses into releases', () => {
    expect(notes.length).toBeGreaterThan(0);
  });

  it('gives every release at least one item', () => {
    for (const note of notes) {
      expect(note.sections.flatMap((s) => s.items).length).toBeGreaterThan(0);
    }
  });

  it('has notes for the version in package.json', () => {
    const pkg = JSON.parse(readFileSync(join(__dirname, '..', 'package.json'), 'utf8')) as { version: string };
    expect(notesFor(notes, pkg.version), `CHANGELOG.md has no "## ${pkg.version}" section`).not.toBeNull();
  });
});
