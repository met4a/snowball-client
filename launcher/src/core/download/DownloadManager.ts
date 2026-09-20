import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdir, rename, rm } from 'node:fs/promises';
import { dirname } from 'node:path';
import { EventEmitter } from 'node:events';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { getLogger } from '../logging/Logger.js';
import { fileSize, isFileValid } from '../util/fsutil.js';
import type { VerifiedFiles } from './VerifiedFiles.js';

const log = getLogger('download');

export interface DownloadTask {
  url: string;
  dest: string;
  sha1?: string;
  size?: number;
  /** Human-readable label for progress/errors. */
  label?: string;
}

export interface DownloadProgress {
  completed: number;
  total: number;
  bytes: number;
  current?: string;
}

export class DownloadError extends Error {
  constructor(message: string, readonly task: DownloadTask, readonly causeError?: unknown) {
    super(message);
    this.name = 'DownloadError';
  }
}

export class DownloadBatchError extends Error {
  constructor(readonly failures: DownloadError[]) {
    super(
      `${failures.length} download(s) failed:\n` +
        failures.slice(0, 5).map((f) => `  - ${f.task.label ?? f.task.url}: ${f.message}`).join('\n') +
        (failures.length > 5 ? `\n  ...and ${failures.length - 5} more` : ''),
    );
    this.name = 'DownloadBatchError';
  }
}

export type FetchLike = (url: string, init?: { signal?: AbortSignal; headers?: Record<string, string>; method?: string; body?: string }) => Promise<Response>;

export interface DownloadManagerOptions {
  concurrency?: number;
  retries?: number;
  retryBaseDelayMs?: number;
  timeoutMs?: number;
  fetchImpl?: FetchLike;
  userAgent?: string;
  /** Skips re-hashing files that were checksum-verified earlier and have not changed since. */
  verified?: VerifiedFiles;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Downloads files with bounded concurrency, per-file retries with exponential backoff,
 * `.part` staging (interrupted downloads never masquerade as complete) and size/SHA-1 checks.
 * Existing files that already match their checksum are skipped.
 */
export class DownloadManager extends EventEmitter {
  private readonly concurrency: number;
  private readonly retries: number;
  private readonly retryBaseDelayMs: number;
  private readonly timeoutMs: number;
  private readonly fetchImpl: FetchLike;
  private readonly userAgent: string;
  private readonly verified?: VerifiedFiles;

  constructor(options: DownloadManagerOptions = {}) {
    super();
    this.concurrency = Math.max(1, options.concurrency ?? 8);
    this.retries = Math.max(0, options.retries ?? 3);
    this.retryBaseDelayMs = options.retryBaseDelayMs ?? 500;
    this.timeoutMs = options.timeoutMs ?? 60_000;
    this.fetchImpl = options.fetchImpl ?? ((url, init) => fetch(url, init));
    this.userAgent = options.userAgent ?? 'SnowballClientLauncher/1.0';
    this.verified = options.verified;
  }

  fetchText(url: string, signal?: AbortSignal): Promise<string> {
    return this.requestText(url, signal);
  }

  /** POSTs a JSON body and parses the JSON response (used for batch lookups such as Modrinth's version_files). */
  async postJson<T>(url: string, body: unknown, signal?: AbortSignal): Promise<T> {
    const text = await this.requestText(url, signal, JSON.stringify(body));
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new Error(`Malformed JSON received from ${url}`);
    }
  }

  private async requestText(url: string, signal?: AbortSignal, jsonBody?: string): Promise<string> {
    assertHttps(url);
    let lastError: unknown;
    for (let attempt = 0; attempt <= this.retries; attempt++) {
      const timeout = AbortSignal.timeout(this.timeoutMs);
      const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
      try {
        const headers: Record<string, string> = { 'User-Agent': this.userAgent };
        if (jsonBody !== undefined) headers['Content-Type'] = 'application/json';
        const res = await this.fetchImpl(url, jsonBody === undefined ? { signal: combined, headers } : { signal: combined, headers, method: 'POST', body: jsonBody });
        if (!res.ok) {
          if (res.status >= 400 && res.status < 500 && res.status !== 429) {
            throw new Error(`HTTP ${res.status} for ${url}`);
          }
          throw new RetryableError(`HTTP ${res.status} for ${url}`);
        }
        return await res.text();
      } catch (err) {
        lastError = err;
        if (signal?.aborted || !isRetryable(err) || attempt === this.retries) break;
        await sleep(this.retryBaseDelayMs * 2 ** attempt);
      }
    }
    throw lastError instanceof Error ? lastError : new Error(String(lastError));
  }

  async fetchJson<T>(url: string, signal?: AbortSignal): Promise<T> {
    const text = await this.fetchText(url, signal);
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new Error(`Malformed JSON received from ${url}`);
    }
  }

  async download(task: DownloadTask, signal?: AbortSignal): Promise<'downloaded' | 'cached'> {
    assertHttps(task.url);
    if (task.sha1 && (await this.verified?.isVerified(task.dest, task.sha1))) return 'cached';
    if (await isFileValid(task.dest, { sha1: task.sha1, size: task.size })) {
      // A file is complete unless it is empty: downloads are written to .part and renamed when finished.
      // Loader libraries from Maven repositories publish no checksum, so presence is all there is to check.
      if ((task.size ?? (await fileSize(task.dest)) ?? 0) > 0) {
        if (task.sha1) await this.verified?.record(task.dest, task.sha1);
        return 'cached';
      }
    }
    await mkdir(dirname(task.dest), { recursive: true });
    const part = `${task.dest}.part`;
    let lastError: unknown;

    for (let attempt = 0; attempt <= this.retries; attempt++) {
      const timeout = AbortSignal.timeout(this.timeoutMs);
      const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
      try {
        const res = await this.fetchImpl(task.url, { signal: combined, headers: { 'User-Agent': this.userAgent } });
        if (!res.ok || !res.body) {
          const retryable = res.status >= 500 || res.status === 429;
          const msg = `HTTP ${res.status}`;
          throw retryable ? new RetryableError(msg) : new Error(msg);
        }
        const hash = createHash('sha1');
        let bytes = 0;
        const source = Readable.fromWeb(res.body as import('node:stream/web').ReadableStream);
        source.on('data', (chunk: Buffer) => {
          hash.update(chunk);
          bytes += chunk.length;
          this.emit('bytes', chunk.length);
        });
        await pipeline(source, createWriteStream(part));

        if (task.size !== undefined && bytes !== task.size) {
          throw new RetryableError(`size mismatch (expected ${task.size}, got ${bytes})`);
        }
        const digest = hash.digest('hex');
        if (task.sha1 && digest.toLowerCase() !== task.sha1.toLowerCase()) {
          throw new RetryableError(`checksum mismatch (expected ${task.sha1}, got ${digest})`);
        }
        await rename(part, task.dest);
        if (task.sha1) await this.verified?.record(task.dest, task.sha1);
        return 'downloaded';
      } catch (err) {
        lastError = err;
        await rm(part, { force: true });
        if (signal?.aborted) break;
        if (!isRetryable(err) || attempt === this.retries) break;
        const delay = this.retryBaseDelayMs * 2 ** attempt;
        log.warn(`Retrying ${task.label ?? task.url} in ${delay}ms`, { attempt: attempt + 1, error: String(err) });
        await sleep(delay);
      }
    }
    const reason = signal?.aborted ? 'cancelled' : lastError instanceof Error ? lastError.message : String(lastError);
    throw new DownloadError(reason, task, lastError);
  }

  /** Runs all tasks; collects every failure and throws one aggregated error at the end. */
  /** Drops what is known about verified files, so the next pass checksums everything again. */
  async forgetVerifiedFiles(): Promise<void> {
    this.verified?.clear();
    await this.verified?.save();
  }

  async downloadAll(tasks: DownloadTask[], signal?: AbortSignal, onProgress?: (p: DownloadProgress) => void): Promise<void> {
    const unique = dedupeTasks(tasks);
    if (!unique.length) return;
    const progress: DownloadProgress = { completed: 0, total: unique.length, bytes: 0 };
    const onBytes = (n: number) => {
      progress.bytes += n;
    };
    this.on('bytes', onBytes);
    const failures: DownloadError[] = [];
    let index = 0;
    const worker = async () => {
      while (index < unique.length) {
        if (signal?.aborted) return;
        const task = unique[index++];
        progress.current = task.label ?? task.url;
        try {
          await this.download(task, signal);
        } catch (err) {
          failures.push(err instanceof DownloadError ? err : new DownloadError(String(err), task, err));
        }
        progress.completed++;
        onProgress?.({ ...progress });
      }
    };
    try {
      await Promise.all(Array.from({ length: Math.min(this.concurrency, unique.length) }, worker));
    } finally {
      this.off('bytes', onBytes);
      await this.verified?.save();
    }
    if (signal?.aborted) throw new Error('Download cancelled');
    if (failures.length) {
      log.error('Download batch failed', { failures: failures.length });
      throw new DownloadBatchError(failures);
    }
  }
}

class RetryableError extends Error {}

function isRetryable(err: unknown): boolean {
  if (err instanceof RetryableError) return true;
  if (err instanceof Error) {
    if (err.name === 'TimeoutError' || err.name === 'AbortError') return err.name === 'TimeoutError';
    // fetch() network failures surface as TypeError("fetch failed").
    return err instanceof TypeError || /ECONNRESET|ETIMEDOUT|EAI_AGAIN|socket/i.test(err.message);
  }
  return false;
}

function assertHttps(url: string): void {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw new Error(`Invalid download URL: ${url}`);
  }
  if (parsed.protocol !== 'https:') throw new Error(`Refusing non-HTTPS download: ${url}`);
}

function dedupeTasks(tasks: DownloadTask[]): DownloadTask[] {
  const seen = new Map<string, DownloadTask>();
  for (const t of tasks) if (!seen.has(t.dest)) seen.set(t.dest, t);
  return [...seen.values()];
}
