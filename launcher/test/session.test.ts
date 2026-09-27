import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { AuthManager, type SecretCipher } from '../src/core/auth/AuthManager.js';
import { tempDir } from './helpers.js';

const cipher: SecretCipher = { isAvailable: () => true, encrypt: (s) => `enc:${Buffer.from(s).toString('base64')}`, decrypt: (s) => Buffer.from(s.slice(4), 'base64').toString() };
const CLIENT = () => '11111111-2222-3333-4444-555555555555';

/** Microsoft, Xbox and Minecraft, counting how often each is asked. */
function services(expiresIn = 86_400) {
  const calls: string[] = [];
  let issued = 0;
  const fetchImpl = async (url: string) => {
    const key = ['token', 'authenticate', 'authorize', 'login_with_xbox', 'profile'].find((k) => url.includes(k))!;
    calls.push(key);
    const body: Record<string, () => unknown> = {
      token: () => ({ access_token: 'ms-access', refresh_token: 'ms-refresh' }),
      authenticate: () => ({ Token: 'xbl', DisplayClaims: { xui: [{ uhs: 'hash' }] } }),
      authorize: () => ({ Token: 'xsts', DisplayClaims: { xui: [{ xid: '1' }] } }),
      login_with_xbox: () => ({ access_token: `mc-${++issued}`, expires_in: expiresIn }),
      profile: () => ({ id: '0123456789abcdef0123456789abcdef', name: 'Snowy' }),
    };
    return new Response(JSON.stringify(body[key]()), { status: 200 });
  };
  return { fetchImpl, calls };
}

async function signIn(file: string, fetchImpl: (url: string) => Promise<Response>) {
  const auth = new AuthManager(file, cipher, CLIENT, fetchImpl, false);
  await auth.load();
  return auth.signInWithMicrosoft(async (r) => `${r.redirectUri}?code=c&state=${new URL(r.url).searchParams.get('state')}`).then((account) => ({ auth, account }));
}

describe('Minecraft sessions', () => {
  it('are reused after the launcher restarts, without signing in again', async () => {
    const file = join(tempDir(), 'accounts.json');
    const first = services();
    const { account } = await signIn(file, first.fetchImpl);

    // A new launcher run: nothing in memory, only accounts.json.
    const second = services();
    const restarted = new AuthManager(file, cipher, CLIENT, second.fetchImpl, false);
    await restarted.load();
    expect((await restarted.session(account.id)).accessToken).toBe('mc-1');
    expect(second.calls).toEqual([]);
    expect(restarted.list()[0].signedIn).toBe(true);
    // Stored encrypted, like the refresh token, never in plain text.
    expect(readFileSync(file, 'utf8')).not.toContain('mc-1');
  });

  it('are refreshed once they are close to running out', async () => {
    const file = join(tempDir(), 'accounts.json');
    const { account } = await signIn(file, services().fetchImpl);
    // Wind the stored session to ten minutes before it expires: inside the safety margin.
    const saved = JSON.parse(readFileSync(file, 'utf8'));
    const stored = JSON.parse(cipher.decrypt(saved.accounts[0].session));
    stored.expiresAt = Date.now() + 10 * 60_000;
    saved.accounts[0].session = cipher.encrypt(JSON.stringify(stored));
    writeFileSync(file, JSON.stringify(saved));

    const later = services();
    const restarted = new AuthManager(file, cipher, CLIENT, later.fetchImpl, false);
    await restarted.load();
    expect((await restarted.session(account.id)).accessToken).toBe('mc-1');
    expect(later.calls).toContain('login_with_xbox');
  });

  it('are not written anywhere the window can read', async () => {
    const { auth } = await signIn(join(tempDir(), 'accounts.json'), services().fetchImpl);
    expect(Object.keys(auth.list()[0])).not.toContain('session');
    expect(Object.keys(auth.list()[0])).not.toContain('refreshToken');
  });
});
