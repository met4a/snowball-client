# Snowglass progress

Read this first in any new session, then `PLAN.md`. Branch: `ui/snowglass` (not pushed).
The plan was approved on 2026-11-01 with the recommended answers to Q1–Q8 (PLAN.md §15).

## Current state: M5 (HUD and HUD editor) done

**Asked 2026-11-01 (user):** do M4, M5 and M6, then either pick the best-looking look for people or send screenshots of all of them so they can choose the default.

### M5: what changed

- **Text HUDs** are glass pills on their background (`Glass.hudRadius`: one line is a pill, taller elements are cards with 5-unit corners), with 6 units each side.
  - In a text that has numbers, the words around them ("FPS", "XYZ", "CPS", "blocks") are drawn quieter, so the numbers stand out.
  - The quieter colour sits 40% of the way from the menus' secondary text to the main text: plain secondary grey all but vanished on HUD glass over snow.
  - Only with the background on. Custom Text, Target Info and Pack Display opt out, since their words are the content.
  - The text is still worked out 20 times a second, not every frame (as before).
- **Keystrokes:** each key is its own pane of HUD glass (`HudModule.glassPane`). Pressing a key floods it with the menu colour at once; letting go fades it on a spring that settles in about 120 ms. The fills are kept where glass cannot be drawn.
- **Armour HUD:** a bar under each durability number. It is the menu colour above 50%, warning (`SnowglassTokens.WARNING`) from 20% to 50%, and danger below 20%. The number stays, so colour is never the only signal.
- **Potion HUD:** the effect's name in the main colour and its time right-aligned and quieter, in the warning colour for its last 10 seconds.
- **HUD editor:**
  - **Snapping:** a dragged element snaps to the screen's edges and middle, and to the other elements' edges and middles, with an accent guide line where it snapped.
  - **Scaling:** scrolling over an element scales it in 5% steps; one turn of the wheel is one undo step.
  - **Nudging:** Shift (or Ctrl) + arrows nudges by 8.
  - **Toolbar:** a glass pill at the bottom with Grid, Snap (S), Reset all and Done. Reset all is a single undo step, since undo now holds groups.
  - The world is dimmed 35% (was 45%).
- **Not done, on purpose:** 9-way anchors and their migration (PLAN.md §8.4). HUD positions are already stored as a fraction of the free space, so an element against an edge stays against it at every resolution and GUI scale. Anchors would change everyone's saved layout for little visible gain.
- `SnowglassGameTest`:
  - Photographs the HUD over all six backdrops, with the armour (worn to about 40% and 20%) and potion HUDs on.
  - Adds a key held down, the HUD editor with the armour HUD picked up, and F1. F1 is `Hud.toggle()` on 26.2 and `options.hideGui` on 1.21.11.

### M5: verified

- `./gradlew build` passes: 233 unit tests per version, 0 failures. `-p legacy build` passes, and `check-generated.mjs` is clean.
- `-Ponly=menu,snowglass` passes on 26.2 and 1.21.11. These screenshots were looked at:
  - the HUD over snow, desert, village and night
  - the held A key flooding with the accent
  - the armour bars (39% and 20% in warning, 100% in the accent) and the potion card
  - the editor with the armour HUD picked up and the toolbar
  - F1 hiding every Snowball element

### M5: not verified

- **HUD glass GPU cost:** not measured. There is still no GPU timer readout (PLAN.md §10).
- **GUI scales:** only GUI 2 was photographed for the HUD.

## Earlier: M4 (the wheel menu restyled in place)

### M4: what changed

All in `RadialMenuView`; the wheel, its layout, the palettes and 3D Menus are as they were.
- **Module rows:** the M3 switch (`Controls.toggle`) on a liquid spring, so the thumb stretches as it moves. The keyboard focus is the M3 focus ring.
- **Status pills:** a status (RESTART, MISSING, the FPS preset, "18 HIDDEN", SHOW/HIDE) is a small pill in its colour instead of loose text. RESTART is in the menu colour, as before.
- **Search:** under the glass looks it is a capsule like the settings pages' text boxes, lit in the menu colour while searching. The classic look keeps its box.
- **FPS BOOST presets:** the four cards stay; the selection now glides to the card picked on a liquid spring, stretching on the way. A segmented control was tried on paper and dropped: at fractional menu scales "Balanced" would not fit a quarter of the panel.
- **Scrolling:** the up/down arrows are replaced by the M3 slim scrollbar on the panel's right edge.
- **Open and close:** the menu and the panel move on standard springs instead of exponential easing. Reduce Motion still snaps.
- `SnowglassGameTest` also photographs FPS BOOST, a search, the keyboard focus, and the menu at 1280×720 and 2560×1440 (GUI Auto). It now switches Glass GUI off first: the menu test leaves it on, and it swaps the blur behind menus for clear glass.

### M4: verified

- `./gradlew build` passes: 233 unit tests per version, 0 failures. `-p legacy build` (1.8.9) passes, and `check-generated.mjs` is clean.
- The existing `SnowballMenuGameTest` passes unchanged on 26.2 and 1.21.11: open, every category, toggle by mouse and keyboard, the change saved, Right Shift toggles, Esc closes.
- `-Ponly=snowglass` passes on both. These screenshots were looked at:
  - `menu_village_fpsboost_gui2`, `menu_village_search_gui2`, `menu_village_focus_gui2`
  - the six backdrops, and `menu_village_720p_guiauto` and `menu_village_1440p_guiauto`
  
  The pills, capsule, focus ring, glide and scrollbar all show.

### M4: not done or not verified

- **Text at 720p.** At 1280×720 (GUI Auto) the row text is drawn about 20% larger than its layout (UiText snaps the font to whole pixels), so some descriptions are cut short with "..". This is how UiText already worked, not new in M4.
- The colour picker as a popover and the `PanelView` pages are left to M6, as PLAN.md §13 has them.

**Font, decided 2026-11-01 (user):** Minecraft's font is back as the default, the main menu included.
- Inter stays as the option under Menu & Accessibility → Font. It replaced Nunito as the smooth font.
- The main menu no longer forces the smooth font (`UiText.setAlwaysSmooth` is gone).
- 1.10.0's `smooth_font` (modules.json) carries over: anyone who had it on gets Inter (`InterfaceSettings.migrateSettings`).
- The "Inter replaces Nunito" notes under M1 still describe the smooth font; it is just not the default any more.

**Glass on the modules, not the list, decided 2026-11-01 (user):** "make the modules glass not the list, like revert it back".
- Their three references (TikTok, @gingyninja) are crystal-PvP gameplay with no menu on screen. The look taken from them is the now-playing card in their HUD: a dark, see-through rounded card in Minecraft's font.
- `Glass.panel` no longer draws real glass. Every panel (the wheel's side list, settings pages, `PanelView` pages, colour picker, waypoints, notifications) is back to the look it had before M2.
- New `Glass.card`: each module row in the wheel's side list is its own pane of real glass, in the panel colour, at the Transparency level and menu glass quality. With glass off, or on a canvas that cannot draw it, the row is the old flat card (`Glass.chip`).
- Unchanged: the HUD glass backgrounds (M2) and the glass dropdown popover and tooltip (M3).
- **Verified:**
  - `./gradlew build` passes: 233 unit tests per version, 0 failures. `-p legacy build` passes, and `check-generated.mjs` is clean.
  - `-Ponly=snowglass` passes on 26.2 and 1.21.11 (OpenGL). The menu screenshots were looked at: glass cards on the old list panel over snow, village and night, and with the 3D lean.
- **Not verified:** the cards at GUI scales other than 2, and with the Lite glass quality.

### M3: what changed

- **`gui/snowglass/Controls`** (shared; draws on any canvas):
  - switch: the thumb stretches with its spring's speed and a little while pressed; its side states on/off
  - slider: white thumb; while dragged, a value capsule appears above it
  - segmented choice: a sliding accent pill that stretches while it moves
  - dropdown, and its glass popover that grows out of the control; the chosen option has a check mark as well as the colour
  - capsules for keys and text; a keybind conflict gets a warning outline plus words in the row
  - colour well
  - focus ring (concentric, accent; yellow in high contrast), slim scrollbar, glass tooltip with word wrap
  - every hit area is at least 12 units
- **`FocusList`**: Tab or Down moves forward, Shift+Tab or Up back, both wrapping. Nothing is focused until the keyboard is used.
- **`ModuleSettingsView`**: every setting uses these controls. A choice of 4 or fewer short options is segmented; longer ones become a dropdown.
  - Mouse: Shift-drag moves a slider a tenth as far.
  - Keyboard: Left and Right step a slider (one step, the finest a setting allows), step a choice or set a switch; Enter or Space activates; Esc closes the open dropdown, then the text box, then the page.
  - A row whose description is cut short shows it whole in a tooltip after 0.4 s.
  - The keybind row says which module or Minecraft control already uses the key (`Host.vanillaKeyUser`, through Fabric's `getBoundKeyOf`), plus a scrollbar.
- **`TextField`**: a capsule with a focus ring under the glass looks; the classic look is unchanged.
- New Stonecutter rename rule: `KeyBindingHelper.getBoundKeyOf(` ↔ `KeyMappingHelper.getBoundKeyOf(`.

### M3: verified

- Builds on all three targets; 232 unit tests per version, 0 failures (new `ControlsTest`, `FocusListTest`). `check-generated.mjs` is clean.
- The full game-test suite passes on 26.2 (5m 55s) and 1.21.11 (6m 6s).
- In-game screenshots, 26.2, over the village:
  - `settings_interface_village_gui2.png`: every control type
  - `settings_focus_village_gui2.png`: Tab focus ring
  - `settings_dropdown_village_gui2.png`: Enter opens the glass dropdown
  - `settings_tooltip_village_gui2.png`: the full description

  All four were looked at and work as intended.

### M3: not done or not verified

- **Lifted glass thumbs.** No glass "droplet" thumbs: a glass thumb would sample the world under the panel rather than the panel, and read as a hole. Thumbs are solid white, with stretch and a press response.
- **Colour picker.** Still opens as its own screen, not a popover.
- **Shift fine-drag.** Not exercised by a game test, only by reading the code.
- **Reduce Motion with the new controls.** Springs snap in code; no screenshot of that path.
- **GUI scales.** Only GUI 2 was photographed.

## Earlier: M2 (real glass) done, and it is in the real Snowball menu

**Direction from the user (2026-11-01, after seeing the Glass Lab):** they want *the normal Snowball
menu*, one menu that works with colour switching, 3D Menus and everything else, not a separate
design. So real glass now goes into the existing menus through `Glass.panel` (the radial menu's side
panel, module settings, Menu & Accessibility, every `PanelView` page, the colour picker, waypoints,
notifications) and the HUD backgrounds. **Decided:** the radial wheel stays. The sidebar-window
redesign is dropped, and M4 restyles the wheel menu and its pages in place (PLAN.md §8.1, §13).

### M2: what changed

- **Shaders** (`assets/snowballclient/shaders/core/`):
  - `snowglass.vsh/.fsh`: one quad per panel. It does:
    - a rounded-rect SDF with derivative anti-aliasing
    - lensing in a rim band, and dispersion at the rim (Full only)
    - frost: small controls stay clear, big panels frost, and the rim looks through clearer glass so the bending shows
    - the adaptive legibility tint, sampled from the 1/8 level (same sums as `Legibility.java`)
    - a rim lit from the top left, a bottom shade, grain, and a soft shadow
  - `snowglass_down.fsh` / `snowglass_up.fsh`: dual-Kawase pyramid passes, drawn with vanilla's `core/screenquad`.
  - Written in vanilla's own GLSL subset: no `gl_FragCoord.xy` for sampling (the backdrop UV comes from clip space, as vanilla's full-screen passes do), and no names that clash with GLSL built-ins.
- **`platform/SnowglassRenderer`** (modern only):
  - pyramid textures at 1/2, 1/4, 1/8, plus a 1/4 up-sample for Full
  - glass and blur pipelines, written with `//? if` for 1.21.11 vs 26.2
  - pooled `GlassElement`s (`GuiElementRenderState`) using vanilla's entity vertex layout to carry per-panel data
  - fallback to Off for the session if the pipelines don't validate (`precompilePipeline(...).isValid()`), logged once with one notification
  - Full drops to Lite while an Iris shader pack is in use (by reflection; untested)
  - textures recreated on resize and closed on shutdown
- **Mixins:**
  - `GuiRendererMixin`: builds the pyramid at the start of `GuiRenderer.draw`, or right after vanilla's `processBlurEffect` when a menu asked for the background blur, so the glass matches the blurred world around it. It also resets the pool when `draw` returns.
  - `GuiStateAccessor`: an `@Accessor` for `guiRenderState`.
- **Canvas.** `Canvas.drawsGlass(quality)` and `Canvas.glass(GlassPanel)`: `GuiCanvas` draws real glass. The defaults draw nothing new, so 1.8.9 and 3D Menus' in-world windows look exactly as before.
- **`Glass.panel`.** When real glass is available it draws it, keeping the 3D Menus thickness, the menu-colour glow and the lean's light. Otherwise it draws exactly the old look.
- **`Glass.hud`.** HUD backgrounds are glass. The element's own opacity slider becomes its transparency level, and quality follows HUD glass (Auto is Lite while FPS Boost is on).
- **Glass Lab** (dev-only, F7): moved onto real glass (Full and Lite rows). Compiled-in sliders rebuild the shader when let go.
- **`SnowglassGameTest`** now photographs the **real menu**:
  - over six backdrops built with commands (snow, desert, village, night, cave, Nether), at GUI 2, and over snow at GUI Auto
  - the HUD alone over snow and the village
  - over the village, also in the Aurora palette, with 3D Menus leaning, and with Menu & Accessibility open
  - Switches: `-Pbackend=vulkan`, and `-PglassFail` (forces the fallback).

### M2: verified

- Builds on all three targets; unit tests pass.
- Screenshots looked at, in `screenshots/26.2/opengl/` and `screenshots/1.21.11/opengl/` (both versions render the same):
  - glass shows the world behind, frosted and tinted
  - over snow the panel darkens on its own and text stays readable
  - the palette switch recolours the glass; the 3D lean bends the glass with the panel
  - the HUD pills read over the bright village
- The forced shader failure falls back to the old look with no crash: `screenshots/26.2/opengl-fallback/`.
- The full game-test suite (menu, flicker, render, skins, spatial, snowglass) passes on 26.2 (5m 46s) and on 1.21.11 (5m 48s), with the glass renderer in.

### M2: not verified

- **Vulkan.** The 26.2 Vulkan backend loses the device (`VK_ERROR_DEVICE_LOST` in vanilla's "GUI after blur" pass) while Fabric's test is still on Minecraft's Create World screen, before any world or glass exists. The M1 commit, which has no glass renderer, crashes the same way, so it isn't Snowglass. It may be the experimental backend, the game-test harness, or the OBS graphics hook loaded into the process. Glass on Vulkan is untested.
- **GPU cost.** Not measured. The game-test harness renders one frame per tick, so frame times there mean nothing, and scoped GPU timer queries aren't wired up yet. Planned for M7.
- **Iris, Sodium, ImmediatelyFast.** Not tested.
- **1.8.9.** Not run in game.

### M1: what changed

- **Inter replaces Nunito.**
  - Inter 4.1 Regular, Medium and SemiBold, the static TTFs from the official `rsms/inter` v4.1 release. The zip's SHA-256 was `9883fdd4…b11e`.
  - The files are in `assets/snowballclient/fonts/`, with Inter's OFL licence as `OFL.txt`. The Nunito files and their licence are gone.
  - `SmoothText.Weight` is now REGULAR, MEDIUM and SEMIBOLD. UiText maps titles to SemiBold, UI text to Medium, and small text to Regular.
- **Tabular digits.** `SmoothText.width/draw(..., tabular)` gives every digit the widest digit's advance, centred in its cell. `java.awt` can't switch on OpenType `tnum`, which is why it works this way.
- **Snowglass package** (`gui/snowglass`, shared with 1.8.9):
  - `SnowglassTokens`: the brand colours from `design/tokens.json`, the brief's colours where the brand has none, spacing, concentric radii, type sizes, the minimum hit size, and category colours.
  - `MaterialLevel`: five named stops, blended in between.
  - `Legibility`: the contrast rule, with gamma-space blending like the game's colour target. Primary text gets ≥ 4.5:1 and secondary ≥ 3:1, with the backdrop always darker than the text.
  - `OffMaterial`: the Off material, drawn from fills.
  - `GlassTuning`: the live material parameters.
  - `GlassLabView`: the dev bench.
- **Motion.** `gui/anim/Spring` is an exact damped-spring solution, so it's frame-rate independent. Standard is 0.28 s response at 0.85 damping; liquid is 0.32 s at 0.7, plus `stretch()`.
- **Theme schema 2** (`gui.json`): new `glassQuality`, `glassLevel`, `hudGlass`, `reduceMotion` and `font` keys. Menu Scale is clamped to 0.75–1.25.
  - `migrateFromV1` converts the old keys:
    - style liquid / frosted / classic becomes full / lite / off
    - `panelOpacity` becomes `glassLevel` (0.82 → 50)
    - animation speed 0 turns on Reduce Motion
    - the old default panel colour `#07090C` becomes the brand surface `#0B0F14`; a custom panel colour is kept
    - Inter is on for everyone
  - `panelOpacity` is now derived from `glassLevel`, for the surfaces still drawn flat.
- **Menu & Accessibility** settings:
  - Look, Panel opacity and Smooth text are replaced by Glass quality, Transparency (0–100), HUD glass (auto/full/lite/off), Font (inter/minecraft) and Reduce motion.
  - The Snowball palette's panel colour is now the brand surface.
  - `Theme.motion()` returns 0 while Reduce Motion is on. Every view that read `animationSpeed` now reads `motion()`, including both `Keystrokes` versions and `Depth`.
- **Glass Lab.**
  - Opened with **F7, in development runs only** (`isDevelopmentEnvironment()`); players never get the key.
  - It shows:
    - the five stops over the world, and over a snow stand-in where the legibility rule darkens them, each with its contrast ratio and alpha
    - the type scale, plus tabular vs proportional digits
    - category icons in their colours
    - both springs
    - sliders for every material parameter (R resets)
  - It scales itself down to fit at GUI scale Auto.
- **Game test** `SnowglassGameTest` (`-Ponly=snowglass`) photographs the lab at GUI scale 2 and Auto at 1920×1080. It copies the PNGs to `docs/ui-revamp/screenshots/<version>/opengl/` (the path comes from the `snowball.screenshots` system property set in `client/build.gradle.kts`).

### M1: verified

- `./gradlew build`: both modern versions compile; 222 unit tests per version, 0 failures. The one skipped test is the existing `SkinLibraryTest`.
- New tests:
  - `SpringTest`: the same path at 30, 144 and 240 fps within 1%; overshoot; stretch cap; stall clamp
  - `SnowglassTest`: a contrast sweep at every level over backdrop luminance 0–1; level blending; concentric radii; distinct category colours
  - `ConfigTest`: migration from a 1.10.0 `gui.json`, a kept custom colour, and the opacity↔level mapping both ways
  - `GlassLookTest`: the new settings
  - `SmoothTextTest`: tabular digits
- `./gradlew -p legacy build` (1.8.9) compiles the shared Snowglass code.
- `node tools/check-generated.mjs`: clean.
- **Glass Lab in the real game.** `runClientGameTest -Ponly=snowglass` passed on 26.2 and 1.21.11 (OpenGL). The screenshots are in `screenshots/26.2/opengl/` and `screenshots/1.21.11/opengl/` (`glass-lab_gui2.png`, `glass-lab_guiauto.png`). I looked at all four:
  - Inter is crisp at GUI 2 and Auto, and tabular digits line up.
  - Category icons show their colours.
  - Over the snow stand-in the legibility rule raises the tint: Ultra Clear 0.18 → 0.63, Regular 0.48 → 0.75. Text stays readable on every swatch.
  - Both versions render the same.
  - Vanilla's menu blur is behind the lab, because "Blur behind menus" is on.
- **Full game-test suite** (menu, flicker, render, skins, spatial, snowglass):
  - 26.2: passed (5m 18s).
  - 1.21.11: the first run failed in `SnowballRenderGameTest` with Fabric's "Network synchronizer in invalid state" ("Detected interfacing with packets at a lower level"). Every UI test before it had passed. The render test alone then passed, and a second full run passed (5m 14s).
  - So it looks like an intermittent problem in the game-test harness, not caused by M1, but it is not proven. If it comes back, try `-Dfabric.client.gametest.disableNetworkSynchronizer`.
  - I checked the existing menus' screenshots from the 26.2 run (the Right Shift menu at 1920×1080, Menu & Accessibility). Inter reads well and the layouts hold.
  - The new settings' descriptions were cut off, so they were shortened afterwards. That was a text-only change, rebuilt and unit-tested, but the game tests were not re-run after it.
- The launcher's design test pins `accentColor = 0xFF7FCBFF;` in `Theme.java`. That line is unchanged.

### M1: not verified, or deliberately left

- **Reduce Motion in older views.** Views that predate Snowglass jump rather than crossfade under Reduce Motion (their animation speed becomes 0). Crossfades come with the M3 components.
- **New icons.** No new line icons yet; the plan said "extended icons", but nothing needs a new glyph until M3/M4. Category colours exist and are shown in the lab.
- **Text size and layout.** The visual weight and size of Inter in the existing menus (`UiText.SMOOTH_SIZE` was tuned for Nunito) has not been checked beyond the lab screenshots. Revisit it when M4 rebuilds the menu.
- **Untested in game.** 1.8.9 has not been run in game, only built.

### Earlier: M0 (2026-11-01)

- Recon, the plan, the reference board (44 images in the git-ignored `.design-refs/`) and REFERENCES.md.
- The Apple HIG "Materials" and Liquid Glass overview pages could not be read (they render with JavaScript); the WWDC25 transcript was used instead.
- Only the first halves of the 26.2, 26.1 and 1.21.11 primers were read.

## Next

1. M4: restyle the wheel menu in place with the M3 controls. That covers:
   - the side panel's rows (their switches), search capsule and FPS Boost cards
   - the other `PanelView` pages
   - the colour picker as a popover

   The wheel layout, the palettes and 3D Menus stay as they are.
3. In M7: measure GPU cost, try Vulkan outside the game-test harness, and run Iris, Sodium and ImmediatelyFast.

## Reproducing the reference captures

The Lethal Client captures were taken with a ~100-line Node script that drives headless Chrome over the DevTools protocol, with no dependencies. It lived in that session's temporary scratchpad, not in the repo. If it's needed again, rewrite it from REFERENCES.md "How the captures were made".
