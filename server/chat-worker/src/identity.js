/*
 * Proving who a player is, without asking Mojang.
 *
 * Mojang blocks requests coming from Cloudflare Workers, so the usual "did this player just join?"
 * call is not available here. Minecraft's own chat signing gives a better answer anyway: Mojang
 * hands every player a key pair together with a signature over that key and the player's account id.
 * A launcher signs the one-time id this room issued, and this file checks both signatures offline:
 *
 *   1. Mojang really issued this public key to this account id (Mojang's signature),
 *   2. whoever is connecting holds the matching private key (the signature over our id).
 *
 * Nothing but public keys is ever sent to this server, and neither check needs the network.
 */

/** Mojang's player certificate keys, from https://api.minecraftservices.com/publickeys. */
const MOJANG_KEYS = [
  'MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAylB4B6m5lz7jwrcFz6Fd/fnfUhcvlxsTSn5kIK/2aGG1C3kMy4VjhwlxF6BFUSnfxhNswPjh3ZitkBxEAFY25uzkJFRwHwVA9mdwjashXILtR6OqdLXXFVyUPIURLOSWqGNBtb08EN5fMnG8iFLgEJIBMxs9BvF3s3/FhuHyPKiVTZmXY0WY4ZyYqvoKR+XjaTRPPvBsDa4WI2u1zxXMeHlodT3lnCzVvyOYBLXL6CJgByuOxccJ8hnXfF9yY4F0aeL080Jz/3+EBNG8RO4ByhtBf4Ny8NQ6stWsjfeUIvH7bU/4zCYcYOq4WrInXHqS8qruDmIl7P5XXGcabuzQstPf/h2CRAUpP/PlHXcMlvewjmGU6MfDK+lifScNYwjPxRo4nKTGFZf/0aqHCh/EAsQyLKrOIYRE0lDG3bzBh8ogIMLAugsAfBb6M3mqCqKaTMAf/VAjh5FFJnjS+7bE+bZEV0qwax1CEoPPJL1fIQjOS8zj086gjpGRCtSy9+bTPTfTR/SJ+VUB5G2IeCItkNHpJX2ygojFZ9n5Fnj7R9ZnOM+L8nyIjPu3aePvtcrXlyLhH/hvOfIOjPxOlqW+O5QwSFP4OEcyLAUgDdUgyW36Z5mB285uKW/ighzZsOTevVUG2QwDItObIV6i8RCxFbN2oDHyPaO5j1tTaBNyVt8CAwEAAQ==',
  'MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAt4t9NPuu7cktclnaH7eZj0omkLcJHeLz5MKsyJEntHZ0INtuBjSSul3Pp3pBeJN8k3ADdcdBLUN90bcAi7WsQqTx3Ft363q3W7TbM8j2iTEdp/0uVspoRt/DP1tkaWFs/w2WwUv9jbVoBUzfUc4pSTIxRwdjmqjZQfvjwKNDbOx3IhP2H0WXodbISejPi1wBZqNW4m1rnZAXp/EpUguxA8mobCa4vUCBkyFDyXdl69/wUSJHyCPmgcMJ364OlAhIqtwVPShBZObvrK/f0BYk6ShJD3N7TFDatSYsIIdcTKRknaIm91s+EsMrdB9U4Yw+ZJ/pyCB4S3vk8zfDCnb0DWIxYH3/EMzaxl77djmTmMzi/JDITup5z3jfWtRZmrAhU2/+W5IO5hEpo3/bCS9PXIY5xb41Lmp2ZO8dXKtyD66Chchy0W129n8vPl2GIruOdrxsjZAHnneyAb9jm0uaGaphwnEnuecX/qgHY6ZMtayvLLsPst8PO6R1vufMy8WqjK+j7LnC1krL7CPDg0NEhyQTmw5l+NCNjSlvB1juM9V4PARg0bYCOkGXm7ydRCjSSH8CJXZpwnd5cBB5WKAX3KPzutRgMi/LFwNSMZzFuUyXaYOZPpD259yqph1LmGqegEdDriACVU+dVEONFMm8eIuBofe7ljmsAFKW9BINwK0CAwEAAQ==',
];

/** Extra Mojang keys are added at runtime from the same list the launcher fetched. */
export function trustedKeys(env) {
  const extra = String(env?.MOJANG_KEYS ?? '').split(',').map((k) => k.trim()).filter(Boolean);
  return [...MOJANG_KEYS, ...extra];
}

function base64ToBytes(base64) {
  const binary = atob(String(base64).replace(/\s+/g, ''));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** Turns a PEM block into the DER bytes inside it. */
function pemToBytes(pem) {
  return base64ToBytes(String(pem).replace(/-----[A-Z ]+-----/g, ''));
}

function bigEndian64(value) {
  const bytes = new Uint8Array(8);
  new DataView(bytes.buffer).setBigInt64(0, BigInt(value), false);
  return bytes;
}

/** The two halves of a UUID as Java sees them, which is what Mojang signed. */
function uuidHalves(uuid) {
  const hex = String(uuid).replace(/-/g, '');
  if (!/^[0-9a-fA-F]{32}$/.test(hex)) return null;
  return [BigInt.asIntN(64, BigInt('0x' + hex.slice(0, 16))), BigInt.asIntN(64, BigInt('0x' + hex.slice(16)))];
}

function concat(...parts) {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(total);
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

async function verifyWith(keyBase64, hash, signature, payload) {
  try {
    const key = await crypto.subtle.importKey('spki', base64ToBytes(keyBase64), { name: 'RSASSA-PKCS1-v1_5', hash }, false, ['verify']);
    return await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, signature, payload);
  } catch {
    return false;
  }
}

/**
 * Checks that Mojang issued this public key to this account, and that the connection holds the
 * matching private key.
 *
 * @returns null when everything checks out, or a short reason why it did not
 */
export async function verifyPlayerProof(env, { uuid, publicKey, keySignature, expiresAt, nonce, nonceSignature }) {
  const halves = uuidHalves(uuid);
  if (!halves) return 'that is not an account id';
  const expiry = Number(expiresAt);
  if (!Number.isFinite(expiry) || expiry < Date.now()) return 'the key Mojang issued has expired';

  let keyBytes;
  let mojangSignature;
  let ourSignature;
  try {
    keyBytes = pemToBytes(publicKey);
    mojangSignature = base64ToBytes(keySignature);
    ourSignature = base64ToBytes(nonceSignature);
  } catch {
    return 'the proof was not readable';
  }

  // What Mojang signed: both halves of the account id, when the key expires, then the key itself.
  const signed = concat(bigEndian64(halves[0]), bigEndian64(halves[1]), bigEndian64(expiry), keyBytes);
  let issued = false;
  for (const key of trustedKeys(env)) {
    if (await verifyWith(key, 'SHA-1', mojangSignature, signed)) {
      issued = true;
      break;
    }
  }
  if (!issued) return 'Mojang did not issue that key to that account';

  // And the connection itself has to sign the one-time id this room just handed out.
  const playerKey = await crypto.subtle.importKey('spki', keyBytes, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify'])
    .catch(() => null);
  if (!playerKey) return 'the public key was not usable';
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', playerKey, ourSignature, new TextEncoder().encode(nonce));
  return ok ? null : 'the connection could not prove it holds that key';
}
