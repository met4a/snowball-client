# Skins: the shared library and how equipping works

The skin changer exists twice: a **Skins** page in the launcher and a **Skins** panel in game.
Both read and write one library on disk, so a skin added in one place shows up in the other. Both
change the account's real skin through Mojang's API, so everyone sees it (other players after
they next load your profile, which on a server means after you rejoin).

## Where the library lives

- The launcher keeps it in `<launcher data root>/skins/` (next to `settings.json`), and passes
  `-Dsnowball.skinsDir=<that folder>` to the game when it launches.
- The game uses `System.getProperty("snowball.skinsDir")` when it is set and is an absolute path,
  and otherwise `<config dir>/snowballclient/skins/` (dev runs, other launchers).

## Files

```
skins/
  skins.json          the list (below)
  <id>.png            one PNG per skin, 64x64 or 64x32, exactly as Mojang takes it
```

`skins.json`:

```json
{
  "schemaVersion": 1,
  "skins": [
    {
      "id": "3f9c2a7b1e04",
      "name": "Patrol's skin",
      "variant": "classic",
      "favourite": false,
      "added": 1791692767978,
      "source": "username",
      "texture": "6b2a1c...e9"
    }
  ]
}
```

| Field | Rule |
|---|---|
| `id` | 12 lowercase hex characters, random. Also the file name: `<id>.png`. Anything else is ignored. |
| `name` | 1 to 32 characters, no control characters. Shown on the card and the row. |
| `variant` | `"classic"` (4-pixel arms, Minecraft's "wide") or `"slim"` (3-pixel arms). |
| `favourite` | Starred skins sort first. |
| `added` | Milliseconds since 1970. Newest first after favourites. |
| `source` | `"username"`, `"url"`, `"file"`, `"current"` (your skin at the time) or `"folder"` (a PNG dropped into the folder). Informational only. |
| `texture` | The hash Mojang gave this skin (the last path segment of its `textures.minecraft.net/texture/<hash>` URL), or absent/null when unknown. Used only to show which skin is **Equipped**. |

Readers keep going when the file is missing or damaged (an empty list), skip entries that break
the rules or whose PNG is missing, and ignore fields they do not know. Writers always re-read the
file just before changing it, change only what they meant to, and write it atomically (a temp file
in the same folder, then a rename), so the launcher and the game never lose each other's changes.

Order everywhere: favourites first, then newest `added` first.

**Folder drops.** A `*.png` in the folder whose name is not `<id>.png` of a listed skin is adopted
when the library is next read by either side: if it is a valid 64x64 or 64x32 PNG it is renamed to
a new `<id>.png` and listed with the old file name (without `.png`, cut to 32 characters) as its
name, `source: "folder"`, and an auto-detected variant. Invalid files are left alone.

**Variant detection** (64x64 only; 64x32 skins are always classic): slim when every pixel in the
rectangles x 50..51, y 16..19 and x 54..55, y 20..31 is fully transparent (alpha 0); otherwise
classic.

**PNG check** without decoding: the first 8 bytes are the PNG signature, bytes 12..15 are `IHDR`,
width (bytes 16..19, big-endian) is 64 and height (bytes 20..23) is 64 or 32. Files over 64 KiB are
refused.

## Adding a skin

- **By username** (3 to 16 of `A-Z a-z 0-9 _`): `GET https://api.mojang.com/users/profiles/minecraft/<name>`
  gives the id; `GET https://sessionserver.mojang.com/session/minecraft/profile/<id>` gives a
  `textures` property, base64 JSON whose `textures.SKIN.url` is the PNG (rewrite `http://` to
  `https://`; only accept host `textures.minecraft.net`) and whose `textures.SKIN.metadata.model`
  is `"slim"` for slim skins. Name it `<Name>'s skin`. `texture` = the URL's last segment.
- **By URL**: `https://` only, at most 64 KiB, must pass the PNG check. Name it from the URL's last
  path segment, or `Skin` when that is empty.
- **From a file** (launcher only: the main process shows the file dialog): same check.
- **Your current skin**: when the page or panel opens and the account's active skin (from the
  profile below) has a `texture` that no listed skin has, it is downloaded and listed as
  `<Name>'s skin` with `source: "current"`, so changing skin never loses the old one.

## Equipping (Mojang's API)

All calls go to `https://api.minecraftservices.com` with `Authorization: Bearer <Minecraft access token>`.
The token never goes anywhere else and is never written to disk by this feature.

- `GET /minecraft/profile` → `{ id, name, skins: [{ id, state: "ACTIVE"|..., url, variant: "CLASSIC"|"SLIM" }], capes: [...] }`.
  The skin with `state` `ACTIVE` is the one worn.
- **Equip** = `POST /minecraft/profile/skins` as `multipart/form-data` with a `variant` field
  (`classic` or `slim`) and a `file` field (the PNG bytes, filename `skin.png`, type `image/png`).
  On 200 the response is the updated profile; store its ACTIVE skin's hash as the entry's
  `texture` (re-reading `skins.json` first).
- Errors, in words a player understands:
  - no token / offline account (token missing, `"0"`, or shorter than 20 characters): "Sign in
    with a Microsoft account to change your skin." Equip is not attempted.
  - 401: "Your Minecraft login has expired. Restart the launcher (or the game) and try again."
  - 429: "Mojang is limiting skin changes right now. Try again in a minute."
  - 400: Mojang's `errorMessage` when present, else "Mojang refused this skin."
  - anything else / network: "Could not reach Mojang. Check your connection and try again."
- POST/PUT/DELETE are never retried automatically.

After a successful equip:

- The launcher marks the card **Equipped** and says "Equipped. Other players see it after you
  rejoin a server."
- The game shows the new skin on you at once (modern versions: tab list, third person,
  inventory, emote wheel, title-screen face), and says the same about other players. On 1.8.9 it
  says "Equipped. Rejoin to see it in game."
