import { mkdir, open, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { inflateRawSync } from 'node:zlib';
import { safeJoin } from './paths.js';

export interface ZipEntry {
  name: string;
  compressedSize: number;
  uncompressedSize: number;
  method: number;
  localHeaderOffset: number;
  isDirectory: boolean;
}

const EOCD_SIG = 0x06054b50;
const CEN_SIG = 0x02014b50;
const LOC_SIG = 0x04034b50;
const MAX_ENTRY_BYTES = 512 * 1024 * 1024;

/**
 * Minimal read-only ZIP/JAR reader (stored + deflate). Enough for reading mod metadata,
 * installer profiles and extracting natives without an extra dependency.
 */
export class ZipReader {
  private constructor(private readonly buffer: Buffer, readonly entries: ZipEntry[]) {}

  static async open(path: string): Promise<ZipReader> {
    const handle = await open(path, 'r');
    try {
      const { size } = await handle.stat();
      const buffer = Buffer.alloc(size);
      await handle.read(buffer, 0, size, 0);
      return ZipReader.fromBuffer(buffer);
    } finally {
      await handle.close();
    }
  }

  static fromBuffer(buffer: Buffer): ZipReader {
    const eocd = findEocd(buffer);
    const count = buffer.readUInt16LE(eocd + 10);
    const cdOffset = buffer.readUInt32LE(eocd + 16);
    const entries: ZipEntry[] = [];
    let p = cdOffset;
    for (let i = 0; i < count; i++) {
      if (p + 46 > buffer.length || buffer.readUInt32LE(p) !== CEN_SIG) throw new Error('Corrupt ZIP central directory');
      const method = buffer.readUInt16LE(p + 10);
      const compressedSize = buffer.readUInt32LE(p + 20);
      const uncompressedSize = buffer.readUInt32LE(p + 24);
      const nameLen = buffer.readUInt16LE(p + 28);
      const extraLen = buffer.readUInt16LE(p + 30);
      const commentLen = buffer.readUInt16LE(p + 32);
      const localHeaderOffset = buffer.readUInt32LE(p + 42);
      const name = buffer.toString('utf8', p + 46, p + 46 + nameLen);
      entries.push({ name, compressedSize, uncompressedSize, method, localHeaderOffset, isDirectory: name.endsWith('/') });
      p += 46 + nameLen + extraLen + commentLen;
    }
    return new ZipReader(buffer, entries);
  }

  getEntry(name: string): ZipEntry | undefined {
    return this.entries.find((e) => e.name === name);
  }

  read(entry: ZipEntry | string): Buffer {
    const e = typeof entry === 'string' ? this.getEntry(entry) : entry;
    if (!e) throw new Error(`ZIP entry not found: ${String(entry)}`);
    if (e.uncompressedSize > MAX_ENTRY_BYTES) throw new Error(`ZIP entry too large: ${e.name}`);
    const p = e.localHeaderOffset;
    if (this.buffer.readUInt32LE(p) !== LOC_SIG) throw new Error(`Corrupt local header for ${e.name}`);
    const nameLen = this.buffer.readUInt16LE(p + 26);
    const extraLen = this.buffer.readUInt16LE(p + 28);
    const start = p + 30 + nameLen + extraLen;
    const data = this.buffer.subarray(start, start + e.compressedSize);
    if (e.method === 0) return Buffer.from(data);
    if (e.method === 8) return inflateRawSync(data, { maxOutputLength: MAX_ENTRY_BYTES });
    throw new Error(`Unsupported ZIP compression method ${e.method} for ${e.name}`);
  }

  readText(name: string): string | null {
    const e = this.getEntry(name);
    return e ? this.read(e).toString('utf8') : null;
  }

  /** Extracts entries into `dest`; every entry name is validated against path traversal. */
  async extractAll(dest: string, filter: (entry: ZipEntry) => boolean = () => true): Promise<number> {
    let n = 0;
    for (const entry of this.entries) {
      if (!filter(entry)) continue;
      const target = safeJoin(dest, entry.name);
      if (entry.isDirectory) {
        await mkdir(target, { recursive: true });
        continue;
      }
      await mkdir(dirname(target), { recursive: true });
      await writeFile(target, this.read(entry));
      n++;
    }
    return n;
  }
}

function findEocd(buffer: Buffer): number {
  const min = Math.max(0, buffer.length - 0xffff - 22);
  for (let i = buffer.length - 22; i >= min; i--) {
    if (buffer.readUInt32LE(i) === EOCD_SIG) return i;
  }
  throw new Error('Not a ZIP file (end of central directory not found)');
}
