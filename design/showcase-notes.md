# Notes from the client showcase video

Notes on a 77-second showcase of another client's menus (recorded on Minecraft 1.21.11 with
Snowball loaded alongside), screen by screen, and what each means for Snowball. Nothing is copied:
these are notes on layout, motion and feel, so Snowball's own screens can be built to the same
standard.

## The look everywhere

- **Frosted glass cards.** Every surface is a rounded card (about 12 px corner radius at 1080p)
  that blurs the world behind it, tinted dark, with a thin pale border (white, roughly 35-45%
  opacity) and a faint lighter band along the top edge. Outside the cards the world stays sharp.
- **One accent, used sparingly.** Almost everything is white on dark glass. Colour only marks
  state: a teal dot on "Mods" (updates), a green "Others on" toggle, gold outlines on locked
  cosmetics.
- **Text.** A clean pixel font, small (one size for labels, one for titles). Labels sit under
  icons. Each card title has a short underline dash beneath it.
- **Icons.** Thin line icons (shield, layout, wand, double chevron, sliders, person, magnifier,
  people, layers) instead of words wherever space is tight.
- **Motion.** Hover makes a card grow about 8-10% and brighten. Opening a category slides the
  cards round a carousel with a little perspective (they lean as they move), and the chosen card
  grows into the panel. Nothing snaps; everything eases.
- **Dock.** A row of four small square cards at the bottom of the mod menu: Wardrobe, Search,
  Friends, Profiles. Always there, whichever category is open.

## 1. Title screen (0:00)

- Animated aurora sky: dark blue-green night, moving curtains of green and violet light, stars,
  and dark rolling hills along the bottom. A "Sky: Polar" chip in the top right switches skies.
- Logo and wordmark centred near the top.
- Middle column of wide outline buttons: Singleplayer, Multiplayer, (client menu), Mods (with a
  dot when something is new), then Options and Quit Game side by side.
- Left: "What's new" with the version and a short changelog.
- Right: your own player standing, a name pill above, a Wardrobe button below.
- Game version in the bottom left corner.

**Snowball:** `TitleMenuView` has the buttons and a client card. Missing: the animated sky, the
changelog column, your player with a Wardrobe button, and the sky switcher.

## 2. Wardrobe (0:01-0:02)

- One large glass window. Top bar: Back, Wardrobe and Friends tabs; on the right your name, a
  Profiles button and a layout toggle.
- Sub-tabs: Wardrobe, Store, Rewards.
- Left column: your profile, then categories (Capes, Wings, Trails, Kill effects, Mace, Emotes),
  then Badges with a count (0/25), and an "Others on" toggle at the bottom.
- Middle: your player, large, turning slowly; arrows either side to turn it; "Drag to rotate,
  scroll to zoom"; a caption with the category and what is equipped.
- Right column: search box and a sort menu (Newest), then a two-wide grid of cards. Each card
  shows the item's art, its name, a "Beta" tag and a padlock when locked. "None" is the first card.

**Snowball:** `CosmeticsView` is a grid of capes and a studio button. Needed: the three-column
window, a turning player preview wearing the selection, search and sort, tags and padlocks.

## 3. Client menu: category cards (0:03, 0:22-0:25)

- The client's name large at the top.
- Five tall glass cards in a row: PvP, HUD, Visuals, Player, Misc. Each has an icon, a label and
  an underline dash.
- Hovered card: larger, brighter glass.
- Dock underneath: Wardrobe, Search, Friends, Profiles.

## 4. Client menu: an open category (0:26-1:11)

- Clicking a card makes it the centre of a carousel: it grows into a tall panel (about 280 GUI
  units wide at 1080p) and the other four cards move to the sides, two each side, in order around
  the ring, so the next category is always one card away.
- The panel: the category icon and name at the top with an underline; then a scrolling list of
  rows. Each row is a glass strip with a small icon, the module's name, and a switch on the right.
  The row under the mouse is lighter. A thin scrollbar sits on the right.
- HUD's list starts with an "Edit HUD" bar across the top.
- Clicking a module's name opens its settings in the same panel: a "Back to HUD" bar, the module's
  name and one-line description, then rows: Enabled (switch), Toggle Key (shows "None"), text
  fields with a label above, collapsible groups ("Text options", "Background options").
- Search opens as its own panel in the carousel, with a text field at the top.

**Snowball:** `RadialMenuView` (wheel and side panel) and `ModuleSettingsView`. Needed: a cards
layout with this carousel, module rows with icons and switches, settings in place, and the dock.
Keep the wheel as a choice for players who like it.

## 5. Multiplayer, create world, loading (0:05-0:16)

- Multiplayer: the server list keeps vanilla's layout but sits on the aurora sky, with flat dark
  rows and outline buttons at the bottom.
- Create world: vanilla's screen and tabs, restyled with the same outline buttons on the sky.
- Loading terrain: a small square tile with a progress bar above it, over the blurred world.

**Snowball:** Minecraft's own screens are left alone today. The sky behind them and the button
style are the reachable parts.

## 6. Pause menu (0:19)

- The same outline buttons, each with a small icon: Back to Game; Advancements and Statistics;
  (client menu) and Options; Mods (with its dot); Save and Quit to Title.
- Under the buttons, a player card: your face, your name large, and the world's name and mode
  ("New World · Singleplayer").

**Snowball:** not restyled yet. The player card and icons are worth doing.

## 7. Friends and profiles (1:12-1:14)

- Friends: the wardrobe window with the Friends tab: find a player, Friends and Requests tabs,
  "Public stories" with a "New story" button, and a hint to pick a friend to see their items.
- Profiles: a panel in the carousel with Saved and Public tabs.

**Snowball:** chat, friends and config profiles exist in the launcher and client; they can open
from the dock.

## 8. HUD seen in passing

- FPS counter top left, a "Nothing playing" music line top centre, toggle-sprint text and a
  rank line ("Snowball Owner") bottom left. Snowball already has all of these.

## What to build, in order

1. Cards menu: category cards, carousel, module rows with switches, settings in place, dock
   (Wardrobe, Search, Friends, Profiles). Offered next to the wheel under Menu & Accessibility.
2. Title screen: animated aurora sky, changelog column, your player with a Wardrobe button.
3. Pause menu: icons on the buttons and the player card.
4. Wardrobe: three-column window with a turning preview, search, sort, tags and padlocks.
5. The sky behind Minecraft's own menus (multiplayer, create world, options).
