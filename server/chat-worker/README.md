# Snowball global chat

The chat everyone sees in the launcher. One Cloudflare Worker with one Durable Object: the room keeps
the last 60 messages, the announcement and the mutes. It costs nothing on Cloudflare's free plan.

## What it does

- **Nobody can pretend to be someone else.** A launcher asks this worker for a one-time id, asks
  Mojang to vouch for the account against that id (the same handshake a Minecraft server uses), and
  this worker checks that with Mojang. The Minecraft token never leaves the player's computer.
- **The rules are enforced here, not only in the launcher.** No links, no addresses, no images, no
  pinging everyone, a word list, a length limit, and a rate limit of 12 messages a minute.
- **Announcements and moderation are yours alone.** Only the UUID in `ADMIN_UUID` may post an
  announcement, mute someone or clear the chat.

## Deploy it (about two minutes)

1. Put your own Minecraft UUID (no dashes) in `wrangler.toml` under `ADMIN_UUID`.
2. From this folder:

   ```bash
   npx wrangler login
   npx wrangler deploy
   ```

3. Wrangler prints a URL such as `https://snowball-chat.<your-account>.workers.dev`.
4. Put that URL in the launcher's `app-config.json` as `chatUrl`, and your UUID as `adminUuid`, then
   build the launcher. Until a URL is set, the launcher simply hides the chat.

## Checking it

`GET /` answers `Snowball chat is up.` and `GET /nonce` returns a fresh id, so you can tell the
worker is live before shipping a launcher that points at it.
