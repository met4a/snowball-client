import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { getLogger } from '../logging/Logger.js';
import { readJson, writeJsonAtomic } from '../util/fsutil.js';

const log = getLogger('auth');

/** Encrypts secrets at rest. The Electron main process backs this with the OS keychain (safeStorage). */
export interface SecretCipher {
  isAvailable(): boolean;
  encrypt(plain: string): string;
  decrypt(cipherText: string): string;
}

export interface StoredAccount {
  id: string;
  type: 'msa' | 'offline';
  name: string;
  uuid: string;
  /** Encrypted Microsoft refresh token; never stored in plain text. */
  refreshToken?: string;
}

export interface SessionAccount {
  id: string;
  type: 'msa' | 'offline';
  name: string;
  uuid: string;
  accessToken: string;
  xuid?: string;
}

export interface AuthorizationRequest {
  /** Microsoft sign-in page to show to the user. */
  url: string;
  /** Sign-in is finished when the page navigates to this address (with ?code=... or ?error=...). */
  redirectUri: string;
}

/** Shows Microsoft's sign-in page and resolves with the full redirect URL once the user is done. */
export type AuthorizationPrompt = (request: AuthorizationRequest, signal?: AbortSignal) => Promise<string>;

type FetchLike = (url: string, init?: { method?: string; headers?: Record<string, string>; body?: string; signal?: AbortSignal }) => Promise<Response>;

const MS_AUTHORIZE = 'https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize';
const MS_TOKEN = 'https://login.microsoftonline.com/consumers/oauth2/v2.0/token';
/** Standard redirect for desktop apps; register it in the Azure app as "Mobile and desktop applications". */
export const MS_REDIRECT_URI = 'https://login.microsoftonline.com/common/oauth2/nativeclient';
const XBL_AUTH = 'https://user.auth.xboxlive.com/user/authenticate';
const XSTS_AUTH = 'https://xsts.auth.xboxlive.com/xsts/authorize';
const MC_LOGIN = 'https://api.minecraftservices.com/authentication/login_with_xbox';
const MC_PROFILE = 'https://api.minecraftservices.com/minecraft/profile';
const SCOPE = 'XboxLive.signin offline_access';
const NAME_PATTERN = /^[A-Za-z0-9_]{3,16}$/;

export class AuthError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthError';
  }
}

/** Deterministic offline-mode UUID, identical to the vanilla server's "OfflinePlayer:<name>" v3 UUID. */
export function offlineUuid(name: string): string {
  const hash = createHash('md5').update(`OfflinePlayer:${name}`, 'utf8').digest();
  hash[6] = (hash[6] & 0x0f) | 0x30;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = hash.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Microsoft account sign-in through Microsoft's own login page (OAuth authorization code + PKCE) and
 * the Xbox Live / XSTS / Minecraft services chain. Requires an Azure application (client id) approved for the Minecraft
 * API. Access tokens are kept in memory only; refresh tokens are encrypted before being written.
 */
export class AuthManager {
  private accounts: StoredAccount[] = [];
  private sessions = new Map<string, SessionAccount>();

  constructor(
    private readonly accountsFile: string,
    private readonly cipher: SecretCipher,
    private readonly getClientId: () => string,
    private readonly fetchImpl: FetchLike = (url, init) => fetch(url, init),
    private readonly devOfflineAllowed = process.env.SNOWBALLCLIENT_DEV_OFFLINE === '1',
  ) {}

  async load(): Promise<void> {
    const result = await readJson<{ accounts?: unknown }>(this.accountsFile);
    const raw = result.ok && Array.isArray(result.value.accounts) ? result.value.accounts : [];
    this.accounts = raw.filter((a): a is StoredAccount =>
      !!a && typeof a === 'object' && typeof (a as StoredAccount).id === 'string' && ((a as StoredAccount).type === 'msa' || (a as StoredAccount).type === 'offline') &&
      typeof (a as StoredAccount).name === 'string' && typeof (a as StoredAccount).uuid === 'string');
    if (!result.ok && result.reason === 'malformed') log.warn('accounts.json was malformed; starting with no accounts');
  }

  list(): Array<Omit<StoredAccount, 'refreshToken'> & { signedIn: boolean }> {
    return this.accounts.map(({ refreshToken: _omit, ...a }) => ({ ...a, signedIn: this.sessions.has(a.id) || a.type === 'offline' }));
  }

  private async persist(): Promise<void> {
    await writeJsonAtomic(this.accountsFile, { schemaVersion: 1, accounts: this.accounts });
  }

  /** Offline accounts are for LAN/singleplayer use and require a Microsoft account to have been added first. */
  canAddOffline(): boolean {
    return this.devOfflineAllowed || this.accounts.some((a) => a.type === 'msa');
  }

  async addOffline(name: string): Promise<StoredAccount> {
    if (!this.canAddOffline()) throw new AuthError('Add a Microsoft account that owns Minecraft before creating offline profiles.');
    if (!NAME_PATTERN.test(name)) throw new AuthError('Offline names must be 3-16 letters, numbers or underscores.');
    const account: StoredAccount = { id: randomUUID(), type: 'offline', name, uuid: offlineUuid(name) };
    this.accounts.push(account);
    await this.persist();
    return account;
  }

  async remove(id: string): Promise<void> {
    this.accounts = this.accounts.filter((a) => a.id !== id);
    this.sessions.delete(id);
    await this.persist();
  }

  private clientId(): string {
    const id = this.getClientId().trim();
    if (!/^[0-9a-f-]{36}$/i.test(id)) {
      throw new AuthError('Microsoft sign-in needs an Azure application client id approved for Minecraft. Set it in Settings > Accounts.');
    }
    return id;
  }

  private async postForm(url: string, form: Record<string, string>): Promise<any> {
    const res = await this.fetchImpl(url, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(form).toString() });
    const body = await res.json().catch(() => ({}));
    return { status: res.status, body };
  }

  private async postJson(url: string, payload: unknown, headers: Record<string, string> = {}): Promise<{ status: number; body: any }> {
    const res = await this.fetchImpl(url, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...headers }, body: JSON.stringify(payload) });
    const body = await res.json().catch(() => ({}));
    return { status: res.status, body };
  }

  /**
   * Interactive sign-in: the user enters their email and password on Microsoft's own page (shown by
   * `prompt`); the launcher only receives an authorization code, exchanged with PKCE for tokens.
   */
  async signInWithMicrosoft(prompt: AuthorizationPrompt, signal?: AbortSignal): Promise<StoredAccount> {
    const clientId = this.clientId();
    const verifier = randomBytes(32).toString('base64url');
    const challenge = createHash('sha256').update(verifier).digest('base64url');
    const state = randomBytes(16).toString('base64url');
    const params = new URLSearchParams({
      client_id: clientId,
      response_type: 'code',
      redirect_uri: MS_REDIRECT_URI,
      scope: SCOPE,
      code_challenge: challenge,
      code_challenge_method: 'S256',
      state,
      prompt: 'select_account',
    });

    let redirected: URL;
    try {
      redirected = new URL(await prompt({ url: `${MS_AUTHORIZE}?${params}`, redirectUri: MS_REDIRECT_URI }, signal));
    } catch (err) {
      throw new AuthError((err as Error).message || 'Sign-in was cancelled.');
    }
    if (redirected.searchParams.get('state') !== state) throw new AuthError('Microsoft sign-in returned an unexpected response. Please try again.');
    const error = redirected.searchParams.get('error');
    if (error) {
      throw new AuthError(error === 'access_denied' ? 'Sign-in was cancelled.' : `Microsoft sign-in failed: ${redirected.searchParams.get('error_description') ?? error}`);
    }
    const code = redirected.searchParams.get('code');
    if (!code) throw new AuthError('Microsoft sign-in did not return a login code.');

    const token = await this.postForm(MS_TOKEN, { client_id: clientId, grant_type: 'authorization_code', code, redirect_uri: MS_REDIRECT_URI, code_verifier: verifier, scope: SCOPE });
    if (token.status !== 200 || !token.body.access_token) {
      throw new AuthError(`Microsoft sign-in failed: ${token.body.error_description ?? token.body.error ?? token.status}`);
    }
    const session = await this.minecraftSession(token.body.access_token);
    return this.storeMsa(session, token.body.refresh_token);
  }

  private async storeMsa(session: Omit<SessionAccount, 'id' | 'type'>, refreshToken: string): Promise<StoredAccount> {
    const existing = this.accounts.find((a) => a.type === 'msa' && a.uuid === session.uuid);
    const account: StoredAccount = existing ?? { id: randomUUID(), type: 'msa', name: session.name, uuid: session.uuid };
    account.name = session.name;
    if (this.cipher.isAvailable()) account.refreshToken = this.cipher.encrypt(refreshToken);
    else {
      delete account.refreshToken;
      log.warn('Secure storage is unavailable; the Microsoft session will not be remembered after closing the launcher.');
    }
    if (!existing) this.accounts.push(account);
    this.sessions.set(account.id, { id: account.id, type: 'msa', ...session });
    await this.persist();
    log.info(`Signed in Microsoft account ${session.name}`);
    return account;
  }

  /** Xbox Live -> XSTS -> Minecraft token -> profile. Explains the common account problems. */
  private async minecraftSession(msAccessToken: string): Promise<Omit<SessionAccount, 'id' | 'type'>> {
    const xbl = await this.postJson(XBL_AUTH, { Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: `d=${msAccessToken}` }, RelyingParty: 'http://auth.xboxlive.com', TokenType: 'JWT' });
    if (xbl.status !== 200) throw new AuthError('Xbox Live authentication failed.');
    const uhs = xbl.body.DisplayClaims?.xui?.[0]?.uhs;
    const xsts = await this.postJson(XSTS_AUTH, { Properties: { SandboxId: 'RETAIL', UserTokens: [xbl.body.Token] }, RelyingParty: 'rp://api.minecraftservices.com/', TokenType: 'JWT' });
    if (xsts.status !== 200) {
      const code = String(xsts.body.XErr ?? '');
      if (code === '2148916233') throw new AuthError('This Microsoft account has no Xbox profile. Create one at xbox.com first.');
      if (code === '2148916238') throw new AuthError('This is a child account; an adult must add it to a Microsoft family first.');
      throw new AuthError('Xbox security token request failed.');
    }
    const mc = await this.postJson(MC_LOGIN, { identityToken: `XBL3.0 x=${uhs};${xsts.body.Token}` });
    if (mc.status !== 200 || !mc.body.access_token) {
      const detail = String(mc.body.errorMessage ?? mc.body.error ?? '');
      if (mc.status === 403 || /app registration|AppRegInfo/i.test(detail)) {
        throw new AuthError('Your Microsoft account signed in, but Minecraft has not approved this launcher yet. This starts working once Mojang approves the Snowball Client app ID.');
      }
      throw new AuthError(`Minecraft services rejected the Xbox token (HTTP ${mc.status}${detail ? `: ${detail}` : ''}).`);
    }
    const profileRes = await this.fetchImpl(MC_PROFILE, { headers: { Authorization: `Bearer ${mc.body.access_token}` } });
    if (profileRes.status === 404) throw new AuthError('This Microsoft account does not own Minecraft: Java Edition.');
    const profile = await profileRes.json().catch(() => ({}));
    if (!profile.id || !profile.name) throw new AuthError('Could not read the Minecraft profile.');
    const uuid = String(profile.id).replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5');
    return { name: profile.name, uuid, accessToken: mc.body.access_token, xuid: xsts.body.DisplayClaims?.xui?.[0]?.xid };
  }

  /** Returns a launch-ready session, refreshing the Microsoft token if needed. */
  async session(accountId: string): Promise<SessionAccount> {
    const account = this.accounts.find((a) => a.id === accountId);
    if (!account) throw new AuthError('Account not found.');
    if (account.type === 'offline') return { id: account.id, type: 'offline', name: account.name, uuid: account.uuid, accessToken: '0' };
    const cached = this.sessions.get(account.id);
    if (cached) return cached;
    if (!account.refreshToken || !this.cipher.isAvailable()) throw new AuthError('Please sign in to this Microsoft account again.');
    const refresh = await this.postForm(MS_TOKEN, { grant_type: 'refresh_token', client_id: this.clientId(), refresh_token: this.cipher.decrypt(account.refreshToken), scope: SCOPE });
    if (refresh.status !== 200) {
      delete account.refreshToken;
      await this.persist();
      throw new AuthError('Your Microsoft session expired. Please sign in again.');
    }
    const session = await this.minecraftSession(refresh.body.access_token);
    await this.storeMsa(session, refresh.body.refresh_token);
    return this.sessions.get(account.id)!;
  }
}
