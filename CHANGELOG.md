# What's new in Snowball Client

Written for the people who play it. Each release says what changed, what got
fixed, and nothing else. Technical notes live in the commit history.

Snowball updates itself: leave **Settings → Updates → Check for updates on
startup** on, and new versions download in the background and apply when you
restart. You only download an installer once.

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
