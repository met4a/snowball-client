# What's new in Snowball Client

Written for the people who play it. Each release says what changed, what got
fixed, and nothing else. Technical notes live in the commit history.

Snowball updates itself: when it starts it checks for a new version, installs
it and restarts into it, waiting if a game is running. You only download an
installer once.

---

## 1.8.0

### What's new
- **Nether Travel.** A calculator in the QOL category: type a position and see
  where a portal comes out on the other side. X and Z are divided or multiplied
  by 8, Y stays the same, and negative and decimal numbers work. It starts from
  where you stand, Tab moves between the boxes, Enter copies the block
  position for /tp. The Coordinates HUD can also show the other dimension's
  position under "Show the other dimension".
- **Low Fire, reworked.** The burning overlay is now squashed towards the
  bottom of the screen instead of slid off it, so the flames keep their whole
  shape and no longer blink as the fire animates. Choose the flame height and
  how see-through they are. It measures the same at every resolution and GUI
  scale, and it now works on Minecraft 1.8.9 too. Your old setting carries over.
- **Bring your setup from other launchers.** Import now finds Lunar Client
  (your Fabric mods for each version, options, keybinds and servers, with worlds
  and packs from its game folder), Feather Client, and the Modrinth App again:
  newer Modrinth versions keep their profiles in a database, and Snowball could
  not see any of them. Your .minecraft folder is offered for Badlion and
  LabyMod players, and you pick the version. For anything else, choose a folder.
  With a long list you can search it, and what to bring across is picked first.
- **A Snowball badge now means someone is playing with Snowball Client.**
  Badges used to follow anyone who had ever used Snowball, even while they
  played on another client. Now your game tells Snowball's server every couple
  of minutes that it is running Snowball Client, proved with the key Mojang
  issued to your account (your access token only ever goes to Mojang), and a
  badge only shows while that is true. It goes a few minutes after the game
  closes. Hide yours under Menu & Accessibility, "Show others I use Snowball".
  Players still on 1.7.0 show no badge until their launcher updates them.
- **Snowball's staff get a pixel-art snowball comet** instead of an [Owner],
  [Admin] or [Staff] tag, drawn to fit Minecraft's own pixels in the player
  list and above heads at every GUI scale.
- **Play tells you what it is doing.** The button shows Preparing, Launching
  and Running, with Cancel and Stop, and one instance can only be started once.
  The progress card lists each step: game files, mods, account, starting
  Minecraft, the game window.
- **The launcher gets out of the way.** It minimises when Minecraft opens (or
  closes, if you prefer, and comes back if the game crashes). Everything keeps
  going while it is minimised or in the background.
- **When a launch fails, it says why in plain words** and offers a fix where
  there is one - download Java, use a safe amount of memory, sign in again,
  verify files - with the technical details one click away.
- **Faster to start playing.** Your Minecraft session is kept (encrypted)
  between launches until it runs out, Java installations are remembered,
  natives are only unpacked when they change, and the preparation steps run
  side by side. Preparing an instance went from about 0.75 s to about 0.26 s
  on a warm start on our test machine.
- Discord Rich Presence was rebuilt in the launcher and in game: it
  reconnects when Discord restarts, sends one presence at a time within
  Discord's limits, and never shows IP addresses, home or dynamic-DNS server
  names, your account or your files. Showing a server's name is off unless you
  turn it on, and then only public domains are shown. It needs Snowball's own
  Discord application, which this build does not have yet, so for now it says
  "not set up" instead of pretending to work.

### Fixed
- The game cut to black for a frame when a resource pack or the language
  reloaded while playing. It now fades exactly like Minecraft does.
- The Mojang logo and progress bar showed through Snowball's loading screen as
  it faded, and the loading screen asked for text before Minecraft could draw
  any, which logged shader errors on every start.
- Module names in the wheel could run into the next segment.
- At GUI scale 1, panel titles ran into the line below them.
- Pressing Play twice could start the same instance twice.
- The progress card disappeared right after pressing Play.
- A launcher left open for more than a day could start Minecraft with a
  session that had run out.
- Settings said "Launcher 1.5.1" whatever the version was.
- The "Install optimisation mods" switch did not save.
- The rank badge in the player list was blurry.
- Scrollbars were the system's white ones on a dark window.

## 1.7.0

### What's new
- **Pick your own menu colours in game.** Open the Snowball menu, then Client,
  then Menu & Accessibility: Menu colour and Panel colour open a colour picker
  with swatches, hue, saturation and brightness, and a box for a hex code. The
  menus change as you drag. The same picker now opens for every colour setting,
  like the crosshair and hit colour.
- **The mod check knows the mods everyone uses.** Mods downloaded from Modrinth
  are recognised by their fingerprint and set aside as known, so Fabric API,
  Sodium, Flashback and the rest no longer show up as something to worry about.
  Only files it cannot vouch for are read, and a file pretending to be a popular
  mod is still caught because its fingerprint does not match. Only the
  fingerprint is sent to Modrinth, never the file.
- The mod check is friendlier to read: no more red and yellow warnings, plain
  words for what it found, and details that open when you ask for them.
- Your Snowball badge shows above your head in game, not only in the player
  list. Press F5 to see yours, and you will see other Snowball players' badges
  above theirs.
- The admin panel is rebuilt. Proper tabs, each field sits next to its buttons,
  and on a wide screen the sections sit side by side instead of stretching
  across the whole window.
- The launcher front page is rebuilt: one large panel for your instance and the
  Play button, and a column for your version, who is online and quick actions.
  No news feed and no adverts.
- Make the launcher yours: any accent colour, a picture of your own behind it,
  and a compact layout for small screens.
- Everything fits every screen, from a 1280x720 laptop at 150% scaling to a
  1440p monitor.
- Smooth, short animations throughout, all switched off by Reduce motion.
- Descriptions in the in-game menu are easier to read on smaller screens.

### Fixed
- Importing from the official Minecraft Launcher could give an instance
  Fabric's version number instead of Minecraft's (0.19.5 instead of 1.21.11),
  and that instance could never start. New imports read it correctly, and
  instances already imported this way are repaired when the launcher starts.
- Long instance names ran across the card next to them.
- Download and update progress bars never moved. Neither did several other
  small layout details, which were being blocked by the launcher's own
  security settings; they now show as intended, and the security settings
  are unchanged.
- The mod check's details could not be closed, and were open from the start.
- The admin panel's People list, bug reports and player lookups loaded but never
  appeared.
- Buttons at the top of the Mods and Browse pages ran off the side of a small
  window.
- The message about which Minecraft versions Snowball Client supports named
  Fabric where it meant Minecraft.

---

## 1.6.3

### Fixed
- Snowball could not check for updates at all. Every installed copy failed the
  moment it looked, with an error about `autoDownload`, which means automatic
  updating has never actually run for anyone - it only ever worked while the
  launcher was being developed. This is the last version you have to install by
  hand.

---

## 1.6.2

### What's new
- Snowball updates itself when it starts. There is nothing to click and no
  installer to run: if a new version is out, it is installed and Snowball
  restarts into it before you get going. It waits if a game is running.
- A new application icon, so Snowball is easier to pick out on the taskbar.

---

## 1.6.1

### Fixed
- Minecraft crashed on startup with 1.6.0 on Minecraft 1.21.11: the Snowball
  client mod failed to load, so the game stopped about twelve seconds in. The
  glass-menu code was looking for a method under its 26.2 name on a version that
  calls it something else.
- The Mods page showed the client as 1.5.1 while the launcher said 1.6.0. They
  are the same number again.
- Every chat message appeared twice. Joining chat while a reconnect was already
  on its way opened a second connection, and both delivered everything.
- The announcement banner only appeared once you had joined chat. It now shows
  wherever you are in the launcher, without joining.
- The admin panel showed tabs to Staff and Developer but refused every button on
  them. It now allows exactly what your rank allows.
- Rank history never loaded in the admin panel.

---

## 1.6.0

### What's new
- The launcher has been redesigned. Text is sharp at every Windows scaling
  level, the layout holds together from 720p up to 1440p, and the interface is
  quieter — no glow, no oversized cards, the same look on every page.
- Home now tells you what you need at a glance: your instance and Play on the
  left, and on the right the version you are on, whether an update is waiting,
  how many people are on Snowball, and the four things you usually click.
- Updates show their progress properly: downloading, checking, then ready, with
  a real percentage and the size.
- When something goes wrong, Snowball says what happened in plain words and what
  to try, with **Copy details** and **Open logs** for when you need the rest.
- Settings has log controls: open the folder, copy the recent log, and choose
  how long logs are kept.
- Rank badges are redrawn. Every Snowball player now wears the snowball in the
  player list, with their rank's emblem set into it — a flask for Tester, a
  beetle for Bug Hunter, a star for Partner, a shield for Staff, brackets for
  Developer, a crown for Owner. The same badge appears in chat and in the
  launcher, so a rank looks the same everywhere.

### Fixed
- The launcher looked pixelated. It was the interface font and the way the logo
  was scaled; both are fixed.
- On a 1080p screen at 200% scaling the window was larger than the screen and
  could not be placed properly.
- Three parts of the interface had no styling at all and rendered as bare text:
  the output panel header, rank badges, and rows in the mod checker.
- Rank badges looked blurry in the player list. They were drawn at a size that
  did not divide evenly into the size they were stored at.
- An update that failed to apply used to leave no trace. Snowball now notices on
  the next start and offers to try again.
- A download that arrives damaged or is not actually a newer version is refused
  instead of installed.

---

## 1.5.1

### What's new
- The launcher shows its own version, under the logo and in Settings.

### Fixed
- Global chat would not connect. Snowball now proves which account you are
  without needing to reach Mojang's servers from the chat backend, which is what
  was failing.

---

## 1.5.0

### What's new
- Eight ranks, from Snowball through to Owner, shown in chat and in the TAB list.
- A HUD editor: drag, resize, rotate and align every module, with snapping,
  undo and saved layouts.
- Ten module categories in the in-game menu, with a radial menu that fits them.
- Performance presets, from Potato to Competitive.
- Glass menus: see the world through the in-game interface.
- A player counter, so you can see how many people are on Snowball.
- Owner tools: ranks, people, bug reports and feature switches.

---

## 1.4.0

### What's new
- The launcher updates itself. After this version you no longer download an
  installer for each release.
- Import your instances from other launchers.
- Mods are checked for known malware patterns before they run, on your machine.

---

## 1.3.0 and earlier

Instance management, Fabric / Quilt / Forge / NeoForge support, automatic Java
downloads, the Modrinth mod browser, Microsoft sign-in, and the first releases
of the Snowball client itself.
