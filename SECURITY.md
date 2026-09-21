# Security

## Reporting something

If you find a security problem in Snowball Client, please report it privately
first: open a [security advisory](https://github.com/met4a/snowball-client/security/advisories/new)
rather than a public issue. You can also report it in the launcher under
**Chat → Report a bug** and say that it is a security issue.

Please include what you did, what happened, and the version from the launcher
sidebar. You will get a reply.

Do not test against other people's accounts or against the Snowball chat server
in a way that affects other players.

## What Snowball does with your account

- Microsoft sign-in uses the standard authorization-code flow with PKCE. Snowball
  is a public client and has no client secret, because a desktop application
  cannot keep one.
- Your Minecraft access token is sent to Mojang and to nobody else. It is never
  sent to the Snowball chat server.
- Refresh tokens are encrypted with the operating system keychain — DPAPI on
  Windows — before they are written to disk.
- Logs pass through redaction before they are written, so tokens and
  authorization headers never reach a file. Anything you copy from
  **Settings → Logs** has already been redacted.

## How the chat server knows who you are

The chat server never sees your Minecraft token. Instead the launcher asks
Minecraft for the signing key it issues for chat, and proves the account with
two signatures: Mojang's own signature over your public key, and your signature
over a one-time value the server issues. The server verifies both offline.

Ranks and permissions are decided by the server and sent to the client. The
client never tells the server what it is allowed to do, so editing local files
cannot grant Owner, Staff or Snowball+.

## How updates are trusted

- The update feed is pinned at build time to one GitHub release source. The
  launcher does not read an update location from a file, a setting, or the
  network.
- Downloads are checksum-verified against the signed release metadata before
  anything is staged.
- A package whose version is not newer than what is installed is refused.
- The handover is recorded first, so the next start can confirm the new build
  actually came up.
- Nothing downloaded is executed before it has been verified. The Forge and
  NeoForge installers are checked against the `.sha1` their Maven repository
  publishes, and Snowball refuses to run one it could not verify.

Once a code-signing certificate is in place, `publisherName` in
`launcher/package.json` makes electron-updater reject any update package signed
by a different publisher. Until then, builds are unsigned — see below.

## Code signing

Snowball does not yet have a code-signing certificate. Windows SmartScreen will
therefore warn about the installer, and that warning is legitimate: it is telling
you the publisher has not been verified.

Do not follow any instructions — from anyone, including us — that tell you to
disable SmartScreen, exclude Snowball from your antivirus, or bypass a security
warning. Check the SHA-256 of what you downloaded against the checksum on the
release page instead.

Signing is pending [SignPath Foundation](https://signpath.org/) approval. When it
lands, releases are signed in CI and `npm run verify:signature` refuses to
publish anything that is unsigned or signed by an unexpected publisher. Signing
will not make the SmartScreen warning disappear immediately — reputation builds
over time and over download volume.

## Scope

Snowball runs Minecraft and mods that you choose to install. The mod checker in
**Home → Check mods for malware** reads jars locally and flags patterns used by
token stealers; it is a helpful signal, not a guarantee, and it is not a
substitute for only installing mods you trust.
