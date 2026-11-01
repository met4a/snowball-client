# Snowball global chat

The chat everyone sees in the launcher. One Cloudflare Worker with one Durable Object: the room keeps
the last 60 messages, the announcement and the mutes. It costs nothing on Cloudflare's free plan.

## What it does

- **Nobody can pretend to be someone else.** A launcher asks this worker for a one-time id, asks
  Mojang to vouch for the account against that id (the same handshake a Minecraft server uses), and
  this worker checks that with Mojang. The Minecraft token never leaves the player's computer.
- **The rules are enforced here, not only in the launcher.** No links, no addresses, no images, no
  pinging everyone, a word list, a length limit, and a rate limit of 12 messages a minute.
- **Badges only while someone is really on Snowball.** A game running Snowball Client proves its
  account the same way (`POST /presence`) and repeats itself every two minutes on a ticket; `POST
  /ranks` only reports players whose game did so in the last five minutes. Closing the game sends
  `DELETE /presence`. Nothing about it is written to storage. Tests: `node --test test/`.
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

## Snowball+ from the store

The website's store page (`website/store.html`) looks up the Minecraft name the player types and
sends them to a Stripe Payment Link with `client_reference_id` set to `<account id>_<name>`. Stripe
then calls `POST /store/stripe`, signed with the endpoint's secret, and the room gives that account
Snowball+ while the monthly subscription is paid for. It takes back only the Snowball+ the store
gave; a rank staff handed out stays. The rank history shows these as given by "Store".

1. In Stripe, under **Developers > Webhooks**, add an endpoint for
   `https://snowball-chat.snowball-mc.workers.dev/store/stripe` with the events
   `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
   `customer.subscription.updated` and `customer.subscription.deleted`.
2. `npx wrangler secret put STRIPE_WEBHOOK_SECRET` and paste that endpoint's signing secret
   (`whsec_...`).

Until the secret is set, `/store/stripe` answers 503 and gives nobody anything. Refunds and disputes
are not followed on their own: cancel the subscription in Stripe when you refund one, and the
cancellation takes Snowball+ back.

## Checking it

`GET /` answers `Snowball chat is up.` and `GET /nonce` returns a fresh id, so you can tell the
worker is live before shipping a launcher that points at it.
