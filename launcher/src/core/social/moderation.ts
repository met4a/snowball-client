/**
 * The chat rules, in one place. The launcher checks a message before it sends it so the writer gets
 * an instant answer, and the server checks it again on the way in, because a client can be changed
 * and a server cannot.
 */

export interface ModerationResult {
  ok: boolean;
  /** The message as it should be sent: trimmed, with shouting and spam calmed down. */
  text: string;
  /** Why it was refused, in words the writer can act on. */
  reason?: string;
}

export const MAX_MESSAGE_LENGTH = 240;

/** Words that are never allowed. Kept short and blunt on purpose; leetspeak is normalised first. */
const BANNED = [
  'nigger', 'nigga', 'faggot', 'fag', 'retard', 'retarded', 'kys', 'kill yourself', 'rape', 'rapist',
  'cunt', 'whore', 'slut', 'bitch', 'bastard', 'dick', 'cock', 'pussy', 'penis', 'vagina', 'porn',
  'pornhub', 'nsfw', 'sex', 'anal', 'blowjob', 'cum', 'fuck', 'fucking', 'fucker', 'motherfucker',
  'shit', 'bullshit', 'asshole', 'arsehole', 'wanker', 'twat', 'nazi', 'hitler', 'suicide',
];

/** Letters people swap in to get past a filter. */
const LEET: Record<string, string> = { '0': 'o', '1': 'i', '!': 'i', '3': 'e', '4': 'a', '@': 'a', '5': 's', '$': 's', '7': 't', '8': 'b', '+': 't', '|': 'i' };

const LINK = /(https?:\/\/|www\.|discord\.gg|discord\.com\/invite|[a-z0-9-]+\.(com|net|org|gg|io|xyz|me|tv|co|ru|link|shop|store|app|dev|site|online|club)(\/|\b))/i;
const IP = /\b\d{1,3}(?:\.\d{1,3}){3}\b(?::\d{1,5})?/;
const IMAGE = /(data:image\/|base64,|\.(png|jpe?g|gif|webp|bmp|svg)\b)/i;
const MENTION_EVERYONE = /@(everyone|here)\b/i;
/** Control codes, zero-width marks and bidi overrides: invisible, but they hide words from a filter. */
const INVISIBLE = new RegExp('[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u2028\\u2029\\u202a-\\u202e\\ufeff]', 'g');

/** Strips accents, invisible characters and leetspeak so the word list sees the real word. */
function normalize(text: string): string {
  const folded = text
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .split('')
    .map((ch) => LEET[ch] ?? ch)
    .join('');
  // "f u c k" and "f-u-c-k" collapse to the same word, while ordinary spacing survives below.
  return folded.replace(/[^a-z]/g, '');
}

function containsBannedWord(text: string): string | null {
  const squashed = normalize(text);
  const spaced = ` ${text.toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^a-z ]/g, ' ').replace(/\s+/g, ' ')} `;
  for (const word of BANNED) {
    if (squashed.includes(word.replace(/[^a-z]/g, ''))) return word;
    if (spaced.includes(` ${word} `)) return word;
  }
  return null;
}

/** Checks one message and returns the version that may be sent. */
export function moderate(raw: string): ModerationResult {
  let text = (raw ?? '').replace(INVISIBLE, '').replace(/\s+/g, ' ').trim();
  if (!text) return { ok: false, text: '', reason: 'Type something first.' };
  if (text.length > MAX_MESSAGE_LENGTH) return { ok: false, text, reason: `Keep it under ${MAX_MESSAGE_LENGTH} characters.` };
  if (LINK.test(text) || IP.test(text)) return { ok: false, text, reason: 'Links and addresses are not allowed in global chat.' };
  if (IMAGE.test(text)) return { ok: false, text, reason: 'Images cannot be posted in global chat.' };
  if (MENTION_EVERYONE.test(text)) return { ok: false, text, reason: 'You cannot ping everyone.' };
  const banned = containsBannedWord(text);
  if (banned) return { ok: false, text, reason: 'That word is not allowed here.' };

  // Shouting and key-mashing are calmed down rather than refused.
  const letters = text.replace(/[^a-zA-Z]/g, '');
  if (letters.length > 6 && letters.replace(/[^A-Z]/g, '').length / letters.length > 0.7) {
    text = text.charAt(0) + text.slice(1).toLowerCase();
  }
  text = text.replace(/(.)\1{4,}/g, '$1$1$1');
  return { ok: true, text };
}

/** How fast one person may talk, checked on both sides. */
export class RateLimit {
  private readonly times: number[] = [];

  constructor(private readonly perMinute = 12, private readonly gapMs = 1500) {}

  /** @returns null when the message may go, or how long to wait in milliseconds */
  check(now = Date.now()): number | null {
    while (this.times.length && now - this.times[0] > 60_000) this.times.shift();
    const last = this.times[this.times.length - 1];
    if (last !== undefined && now - last < this.gapMs) return this.gapMs - (now - last);
    if (this.times.length >= this.perMinute) return 60_000 - (now - this.times[0]);
    this.times.push(now);
    return null;
  }
}
