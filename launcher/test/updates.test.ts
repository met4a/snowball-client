import { describe, expect, it } from 'vitest';
import { compareVersions, describeUpdateFailure, isValidVersion } from '../src/main/updateRules.js';

describe('compareVersions', () => {
  it('orders releases', () => {
    expect(compareVersions('1.5.2', '1.5.1')).toBe(1);
    expect(compareVersions('1.5.1', '1.5.2')).toBe(-1);
    expect(compareVersions('1.5.1', '1.5.1')).toBe(0);
  });

  it('compares each part numerically, not as text', () => {
    // The bug this guards: "1.10.0" sorting below "1.9.0" under a string compare.
    expect(compareVersions('1.10.0', '1.9.0')).toBe(1);
    expect(compareVersions('2.0.0', '1.99.99')).toBe(1);
  });

  it('tolerates a v prefix and pre-release suffixes', () => {
    expect(compareVersions('v1.6.0', '1.5.9')).toBe(1);
    expect(compareVersions('1.6.0-beta.2', '1.6.0')).toBe(0);
  });

  it('treats missing parts as zero', () => {
    expect(compareVersions('1.6', '1.6.0')).toBe(0);
    expect(compareVersions('1.6.1', '1.6')).toBe(1);
  });
});

describe('isValidVersion', () => {
  it('accepts release strings', () => {
    for (const v of ['1.5.1', 'v1.5.1', '10.0.3', '1.6.0-beta.1']) expect(isValidVersion(v)).toBe(true);
  });

  it('rejects anything an update must not be built on', () => {
    // A feed that has been truncated, defaced or served as an error page must never
    // be read as "there is a version here".
    for (const v of ['', 'latest', '1.5', 'not a version', '<!DOCTYPE html>', null, undefined, 42, {}]) {
      expect(isValidVersion(v)).toBe(false);
    }
  });
});

describe('describeUpdateFailure', () => {
  const cases: Array<[string, string, string]> = [
    ['no internet', 'net::ERR_INTERNET_DISCONNECTED', 'No internet connection'],
    ['dns failure', 'getaddrinfo ENOTFOUND github.com', 'No internet connection'],
    ['timeout', 'connect ETIMEDOUT 140.82.121.4:443', 'The connection dropped'],
    ['reset', 'read ECONNRESET', 'The connection dropped'],
    ['corrupt download', 'sha512 checksum mismatch, expected abc got def', 'The download was damaged'],
    ['permissions', 'EPERM: operation not permitted, rename', "Snowball couldn't write the update"],
    ['disk full', 'ENOSPC: no space left on device', 'Not enough disk space'],
    ['no release', 'Cannot find latest.yml in the latest release', 'No update was published'],
    ['rate limited', 'HTTP 429 rate limit exceeded', 'The update server is busy'],
    ['unknown', 'something nobody predicted', "The update couldn't be checked"],
  ];

  for (const [name, raw, title] of cases) {
    it(`explains ${name} without jargon`, () => {
      const described = describeUpdateFailure(new Error(raw));
      expect(described.title).toBe(title);
      // The headline must be readable: no error codes leaking into what the player sees.
      expect(described.title).not.toMatch(/E[A-Z]{3,}|net::|sha512|HTTP \d/);
      expect(described.message).not.toMatch(/E[A-Z]{3,}|net::|::/);
      expect(described.hints.length).toBeGreaterThan(0);
      // ...but the raw text is still there for a bug report.
      expect(described.detail).toContain(raw);
    });
  }

  it('keeps the stack in detail while leaving it out of the message', () => {
    const err = new Error('net::ERR_INTERNET_DISCONNECTED');
    const described = describeUpdateFailure(err);
    expect(described.detail).toContain('Error: net::ERR_INTERNET_DISCONNECTED');
    expect(described.message).not.toContain('Error:');
  });

  it('handles a non-Error being thrown', () => {
    const described = describeUpdateFailure('plain string failure');
    expect(described.detail).toBe('plain string failure');
    expect(described.title).toBeTruthy();
  });

  it('marks every described failure as retryable', () => {
    // Nothing here is permanent: the launcher always leaves a way forward.
    for (const [, raw] of cases) expect(describeUpdateFailure(new Error(raw)).canRetry).toBe(true);
  });
});
