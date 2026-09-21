/**
 * The rules the updater applies, kept free of Electron so they can be tested directly:
 * which version counts as newer, which version strings are acceptable at all, and how a raw
 * failure becomes something a player can act on.
 */
/** Semantic-version compare, tolerant of a `v` prefix and of pre-release suffixes. */
export function compareVersions(a: string, b: string): number {
  const parts = (v: string) => v.replace(/^v/i, '').split('-')[0].split('.').map((n) => Number.parseInt(n, 10) || 0);
  const [x, y] = [parts(a), parts(b)];
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const diff = (x[i] ?? 0) - (y[i] ?? 0);
    if (diff !== 0) return diff < 0 ? -1 : 1;
  }
  return 0;
}

export function isValidVersion(v: unknown): v is string {
  return typeof v === 'string' && /^v?\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(v.trim());
}

/**
 * Turns whatever the updater threw into something a player can act on. The raw text is kept in
 * `detail` so a bug report still carries it, but it never becomes the headline.
 */
export function describeUpdateFailure(raw: unknown): { title: string; message: string; hints: string[]; detail: string; canRetry: boolean } {
  const detail = raw instanceof Error ? `${raw.name}: ${raw.message}${raw.stack ? `\n${raw.stack}` : ''}` : String(raw);
  const text = detail.toLowerCase();
  const of = (title: string, message: string, hints: string[], canRetry = true) => ({ title, message, hints, detail, canRetry });

  if (/err_internet_disconnected|enotfound|eai_again|getaddrinfo|err_name_not_resolved/.test(text)) {
    return of(
      'No internet connection',
      'Snowball could not reach the update server, so it carried on with the version you already have.',
      ['Check your internet connection.', 'If you are on a school or work network, it may be blocking GitHub.', 'Press Retry once you are back online.'],
    );
  }
  if (/etimedout|econnreset|econnaborted|err_timed_out|err_connection|socket hang up|network error/.test(text)) {
    return of(
      'The connection dropped',
      'The download to the update server was interrupted before it finished. Nothing was changed, and the launcher you have still works.',
      ['Check your connection is stable, then press Retry.', 'On a slow connection the download can take a few minutes.'],
    );
  }
  if (/sha512|checksum|integrity|hash mismatch|file size|size mismatch/.test(text)) {
    return of(
      'The download was damaged',
      'The update arrived incomplete or altered, so Snowball refused to install it. Your current version is untouched.',
      ['Press Retry to download it again.', 'If this keeps happening, download the installer from the Snowball website instead.'],
    );
  }
  if (/eacces|eperm|access is denied|permission denied|operation not permitted/.test(text)) {
    return of(
      "Snowball couldn't write the update",
      'Windows refused the file change, usually because the launcher is installed somewhere that needs administrator rights, or another copy is still running.',
      ['Close any other Snowball windows and press Retry.', 'If it still fails, download the installer from the Snowball website and run it once.'],
    );
  }
  if (/enospc|no space|disk full/.test(text)) {
    return of(
      'Not enough disk space',
      'There was not enough free space to download the update.',
      ['Free up a few hundred megabytes on your system drive.', 'Then press Retry.'],
    );
  }
  if (/404|not found|cannot find|no published versions|latest\.yml/.test(text)) {
    return of(
      'No update was published',
      'The update server answered, but it has no release Snowball can read. This is usually temporary while a release is being published.',
      ['Try again in a few minutes.', 'The version you have keeps working in the meantime.'],
    );
  }
  if (/403|429|rate limit/.test(text)) {
    return of(
      'The update server is busy',
      'GitHub is temporarily refusing requests from this network. This clears by itself.',
      ['Wait a few minutes and press Retry.'],
    );
  }
  return of(
    "The update couldn't be checked",
    'Something unexpected went wrong while looking for a new version. Your installed version has not been touched.',
    ['Press Retry.', 'If it keeps happening, use Copy details and open a bug report.'],
  );
}
