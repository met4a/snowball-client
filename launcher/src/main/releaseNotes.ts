import { readFileSync } from 'node:fs';

export interface ReleaseNote {
  version: string;
  sections: Array<{ heading: string; items: string[] }>;
}

/**
 * Reads the changelog that ships with the build. It is deliberately a plain Markdown file that a
 * person edits, rather than generated from commits: the release notes are for players, and commit
 * messages are not.
 *
 * Only the shapes the file actually uses are understood — `## version`, `### heading`, `- item` —
 * and anything else is skipped rather than guessed at.
 */
export function parseChangelog(markdown: string): ReleaseNote[] {
  const notes: ReleaseNote[] = [];
  let note: ReleaseNote | null = null;
  let section: { heading: string; items: string[] } | null = null;

  for (const raw of markdown.split(/\r?\n/)) {
    const line = raw.trimEnd();

    const version = /^##\s+(?!#)(.+)$/.exec(line);
    if (version) {
      const name = version[1].trim();
      note = { version: name, sections: [] };
      section = null;
      notes.push(note);
      continue;
    }
    if (!note) continue;

    const heading = /^###\s+(.+)$/.exec(line);
    if (heading) {
      section = { heading: heading[1].trim(), items: [] };
      note.sections.push(section);
      continue;
    }

    const item = /^[-*]\s+(.+)$/.exec(line);
    if (item && section) {
      section.items.push(item[1].trim());
      continue;
    }
    // A wrapped bullet: Markdown continues the previous item on an indented line.
    if (section?.items.length && /^\s{2,}\S/.test(raw)) {
      section.items[section.items.length - 1] += ` ${raw.trim()}`;
    }
  }
  return notes.filter((n) => n.sections.some((s) => s.items.length));
}

/** The notes for one version, matched leniently so "v1.6.0" and "1.6.0" both find it. */
export function notesFor(notes: ReleaseNote[], version: string): ReleaseNote | null {
  const wanted = version.replace(/^v/i, '').trim();
  return notes.find((n) => n.version.replace(/^v/i, '').trim() === wanted) ?? null;
}

export function loadChangelog(path: string): ReleaseNote[] {
  try {
    return parseChangelog(readFileSync(path, 'utf8'));
  } catch {
    // A build without its changelog still runs; the What's new screen simply says so.
    return [];
  }
}
