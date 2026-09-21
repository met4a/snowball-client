# What's new in Snowball Client

Written for the people who play it. Each release says what changed, what got
fixed, and nothing else. Technical notes live in the commit history.

Snowball updates itself: leave **Settings → Updates → Check for updates on
startup** on, and new versions download in the background and apply when you
restart. You only download an installer once.

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

### Fixed
- The launcher looked pixelated. It was the interface font and the way the logo
  was scaled; both are fixed.
- On a 1080p screen at 200% scaling the window was larger than the screen and
  could not be placed properly.
- Three parts of the interface had no styling at all and rendered as bare text:
  the output panel header, rank badges, and rows in the mod checker.
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
