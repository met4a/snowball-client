# Snowball Client

A Minecraft client and launcher: an instance-based launcher (in the spirit of Prism Launcher) and a
Fabric client mod with a radial control menu, HUD and quality-of-life modules (in the spirit of
Lunar Client).

Snowball Client is a legitimate client-side customisation, accessibility and performance project.
It contains no ESP, X-ray, combat automation, reach, aim assistance, player tracking or anti-cheat
bypasses.

```
snowball-client/
├── client/        Snowball Client (Fabric; one source tree built for Minecraft 26.2 and 1.21.11
│                  with Stonecutter, and client/legacy for Minecraft 1.8.9 on Legacy Fabric,
│                  sharing the same menus and settings). client/tools publishes the rank badges.
├── launcher/      Electron + TypeScript launcher (builds the Windows .exe)
├── server/        Cloudflare Worker behind global chat, ranks and bug reports
├── website/       The public site, published with GitHub Pages
├── store/         Microsoft Store listing copy
├── CHANGELOG.md   What changed in each release, written for players
├── SECURITY.md    Reporting a security problem, and how updates are trusted
├── Snowball.png   Source artwork for the logo
└── README.md
```

## Quick start (what you run)

The thing you run is the launcher `.exe`. The client mod is a `.jar` because Fabric can only load
Java mods; the launcher bundles that jar and installs it into instances for you.

```bash
cd launcher
npm install
npm run dist
```

Output (Windows):

- `launcher/release/SnowballClient-<version>-setup.exe` - installer
- `launcher/release/SnowballClient-<version>-portable.exe` - single-file portable build

Then `npm run verify:signature`, which prints the Authenticode status of everything in
`release/` and refuses anything signed badly or by an unexpected publisher. Snowball has no
certificate yet, so local builds report NotSigned - set `SNOWBALL_ALLOW_UNSIGNED=1` for those.

## Requirements

| Part | Needs |
| --- | --- |
| Client mod | JDK 21+ to run Gradle. Java 25 for compiling is downloaded automatically by the Gradle toolchain. |
| Launcher | Node.js 22+ and npm. |
| Playing | A Microsoft account that owns Minecraft: Java Edition (see *Accounts*). The launcher downloads the Java runtime Minecraft requests if none is installed. |

## Client mod (`client/`)

```bash
cd client
./gradlew build buildAndCollect      # every Minecraft version: compile + unit tests + jars in build/libs/launcher/
./gradlew :26.2:test                 # unit tests for one version
./gradlew :1.21.11:runClient         # start Minecraft 1.21.11 with the client (development)
./gradlew :26.2:runClientGameTest    # automated in-game UI test (opens a game window)
```

- Jars: `client/build/libs/launcher/snowball-client-<version>+<minecraft>.jar`, one per Minecraft version
- Versions are listed in `settings.gradle.kts` with per-version dependencies in `stonecutter.properties.toml`.
  `src/` is 26.2 code (keep 26.2 as the active version). Differences for older versions are either plain
  renames in `stonecutter.gradle.kts` or `//? if >=26.1 { ... //?} else { /*...*/ //?}` blocks in the code.
- In-game test screenshots: `client/build/run/clientGameTest/screenshots/`

The in-game test creates a world, opens the menu at 1280x720, 1920x1080, 2560x1440 and 3840x2160,
selects every category with the mouse, toggles a module with mouse and keyboard, checks that the
mouse is re-captured on close and that `modules.json` persisted the change.

### Using the client

- **Right Shift** opens the radial menu (rebind it in Controls under *Snowball Client*).
- Click a segment (or use Left/Right/Tab) to pick a category; the panel on the right lists its modules.
- Every row shows the module name and a short description. Start typing to search all modules;
  Esc clears the search.
- Click a row or press Enter/Space to toggle. The ⋮ button or right-click opens module settings
  (keybind, sliders, choices, colours, reset). Rows with › open a screen.
- Esc or the menu key closes the menu.

| Category | Modules |
| --- | --- |
| CLIENT | Client HUD, Custom Crosshair, FPS Counter, Ping Counter, Coordinates, Direction HUD, Speed, Combo Counter, Reach Display, Memory Usage, Pack Display, Armor HUD, Menu & Accessibility |
| FPS BOOST | Boost (preset cards: Max FPS, Balanced, Quality, Off). Under Advanced: Faster Rendering (Sodium), Faster Game Logic (Lithium), Lower Memory Use (FerriteCore), Skip Hidden Mobs (Entity Culling), Faster HUD (ImmediatelyFast), Animation Optimization, Background FPS Limit, Particle Limiter, Performance Advisor |
| RENDER | Boost, Fullbright, Zoom, Freelook, Third-Person Camera, Time Changer, Hit Colour, Block Outline, No Hurt Shake, Low Fire, FOV Settings, Render Distance, Entity Distance, Weather Effects, Potion HUD |
| MISC | Chat Upgrades (stack repeats, mention highlight, right-click copy, timestamps), Nick Hider, Screenshot Utility, Notifications, Time Display, Server Information, Session Timer |
| QOL | Keystrokes, Toggle Sprint, Zoom (with zoom level readout), Freelook, Snaplook, Third-Person Camera, Waypoints, CPS Counter, Coordinates |
| STORAGE | Screenshots, Config Profiles, Mod Profiles, Resource Packs, Shader Packs, Container Preview, Container Search, Item Counter |

Modules are discovered from `ModuleManager`: register a new module in `ModuleRegistry` and it appears
in its category (use `alsoIn(...)` to list it in more than one, `markAdvanced()` to put it under the
category's Advanced row).

Boost remembers your video settings, applies the chosen preset and asks the launcher for the matching
performance profile; switching it off restores your settings. The optimisation mod rows reflect
separately installed mods. Mods cannot be loaded or unloaded while the game runs, so their toggles are
written to `config/snowballclient/mod-requests.json` and applied by the launcher on the next launch
(the row shows RESTART until then, or MISSING when the mod is not installed).

All features are client-side and visual or informational only: no ESP, X-ray, reach changes, aim
assist or auto clicking. Reach Display and Combo Counter only report the player's own hits.

### Client configuration

`<instance>/minecraft/config/snowballclient/`:

| File | Contents |
| --- | --- |
| `client.json` | last category, menu options |
| `modules.json` | enabled state and settings per module |
| `keybinds.json` | module keybinds (the menu key is stored in `options.txt`) |
| `gui.json` | theme: opacity, accent colours, scale, animation speed, high contrast |
| `waypoints.json` | waypoints per world/server |
| `profiles/*.json` | saved module setups |
| `mod-requests.json` | mod toggles and performance profile requested from inside the game |

Every file has a `schemaVersion` and migrations. An unreadable file is renamed to
`<name>.corrupt-<time>.json` and defaults are used, so a broken config never crashes the game.

## Launcher (`launcher/`)

```bash
cd launcher
npm install
npm test             # unit tests (Vitest)
npm run typecheck    # main + renderer
npm start            # build and run the launcher in development
npm run build:client # build the client mod jar that gets bundled
npm run dist         # client mod + launcher -> launcher/release/*.exe
```

Development reads the client jars from `client/build/libs/launcher/`; packaged builds include them in
`resources/client/`. The launcher picks the build whose Minecraft range matches each instance.

Installer artwork (sidebar, header, icon) lives in `launcher/build-resources/installer/` and is
regenerated with `npx electron scripts/installer-art.cjs`; page text is in `installer.nsh`.

### Data folder

`%APPDATA%\SnowballClientLauncher` (Windows), `~/Library/Application Support/SnowballClientLauncher`
(macOS), `~/.local/share/snowball-client-launcher` (Linux). Override with `SNOWBALLCLIENT_LAUNCHER_HOME`.

```
instances/<name>/instance.json      version, loader, Java, memory, arguments, window, profiles
instances/<name>/minecraft/          isolated game directory: mods, config, saves, resourcepacks,
                                     shaderpacks, screenshots, logs
versions/ libraries/ assets/         shared, checksum-verified game files
runtimes/                            Java runtimes downloaded from Mojang
logs/launcher-YYYY-MM-DD.log         structured JSON logs (tokens redacted)
settings.json accounts.json
```

### Features

- Instances with their own Minecraft version, loader, Java, memory, JVM/game arguments, window size,
  mods and folders. Create, edit, duplicate, delete.
- Minecraft versions from Mojang's manifest; inheritance merge for loader profiles; libraries,
  natives and assets with SHA-1 verification, retries, `.part` staging and clear errors.
- Mod loaders behind `IModLoader`: Fabric and Quilt (official meta profiles), Forge and NeoForge
  (official installers, verified against the Maven `.sha1`, run headless).
- Java detection (JAVA_HOME, PATH, common vendor folders), per-instance selection with version
  validation, automatic Mojang runtime download, safe memory recommendations.
- Mod manager: install, remove, enable/disable (renames the jar, never modifies it), duplicate,
  loader, Minecraft-version and rendering-conflict detection (e.g. Sodium + OptiFine).
- Missing dependencies: required mods from `fabric.mod.json`, `quilt.mod.json` and `mods.toml` are
  checked against installed mods, their `provides` and bundled jar-in-jar mods, and shown as
  "Iris Shaders needs Sodium to run." with a one-click install for well-known mods.
- Mod browser (BROWSE page) on Modrinth's public API: search with sorting and version/loader
  filters, one-click installs that pick the file for the instance's exact Minecraft version and
  loader, required dependencies resolved before anything downloads, and updates from the MODS page.
  Installed mods are recognised by SHA-1, so hand-added and profile-installed mods count too.
- New Fabric instances get the newest Fabric API for their exact Minecraft version once; if it cannot
  be installed yet it is retried on launch, and removing it afterwards is respected.
- Snowball Client is part of the launcher, not a mod in the instance (`src/core/snowball/`), the way
  Lunar and Feather work. The launcher keeps a SHA-1 verified copy of each client build in
  `<data folder>/client/` and loads it into the game with Fabric Loader's `-Dfabric.addMods=...`, so it
  never appears in the mods folder or mod list and can't be deleted or turned off there. Every instance
  with a matching build gets it automatically, with Fabric API, when it is created; before each launch
  the copy is verified and restored if it is missing or damaged, and old copies found in a mods folder
  are renamed to `.duplicate` (or `.unsupported` on versions without a build) so the client never loads
  twice. In a Snowball instance (`.snowball/core.json`), Fabric API can be updated but `ModManager` and
  performance profiles refuse to remove or turn it off. Client builds are found by reading each jar's
  `fabric.mod.json` in `resources/client/` (development: `client/build/libs/`), so a build for another
  Minecraft version is supported by adding its jar.
- Readable output: the Home page shows an Activity timeline (`[Snowball] Starting Minecraft...`,
  `Loading 56 mods`, `Joined the world`, crash and out-of-memory explanations) next to the technical
  log, which reassembles Minecraft's Log4j XML console output into normal log lines.
- Performance profiles (Balanced, FPS Boost, Maximum FPS, Visual Quality) that install compatible
  Sodium-based stacks from Modrinth and only ever remove files they installed.
- Launching in the background with live log capture, crash detection (exit code, crash reports,
  crash markers), duplicate-launch prevention and process-tree stop.

### Accounts

"Sign in with Microsoft" opens Microsoft's own login window, where the player enters their email and
password. The launcher never sees the password: it only receives a login code (OAuth authorization
code with PKCE), then signs in to Xbox Live, XSTS and Minecraft services.

Microsoft only allows this for launchers registered with Microsoft and approved by Mojang, so the
build needs an **Azure application (client) ID**:

1. portal.azure.com > App registrations > New registration > "Personal Microsoft accounts only".
2. Add a redirect URI of type "Mobile and desktop applications":
   `https://login.microsoftonline.com/common/oauth2/nativeclient`
3. Request Minecraft API access for that app through Mojang's app review form (aka.ms/mce-reviewappid).
4. Put the ID in `launcher/app-config.json` (`"microsoftClientId"`) before `npm run dist` so every user
   can sign in, or paste it in Settings > Accounts.

Refresh tokens are encrypted with the operating system keychain (Electron `safeStorage`) and never
written in plain text; access tokens stay in memory. Offline profiles (for singleplayer/LAN) can only
be added after a Microsoft account.

## Security notes

- Downloaded metadata is validated; all paths from metadata and user input go through traversal checks.
- Only HTTPS downloads; mod downloads are restricted to Modrinth's CDN; installers must match their published checksum.
- The renderer is sandboxed with context isolation, a strict CSP and a whitelisted IPC bridge.
- Logs redact access tokens, refresh tokens and authorisation headers.

## Licenses

Snowball Client is MIT licensed. The in-game menu uses Minecraft's own font. The launcher bundles
Monocraft, a Minecraft-style font under the SIL Open Font License
(`launcher/src/renderer/assets/fonts/OFL-Monocraft.txt`).
