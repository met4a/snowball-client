# Snowglass progress

Read this first in any new session, then `PLAN.md`. Branch: `ui/snowglass` (not pushed).
The plan was approved on 2026-11-01 with the recommended answers to Q1–Q8 (PLAN.md §15).

## Current state: M1 (design system) done; next is M2 (glass renderer)

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

## Next: M2 (glass renderer)

1. Read the second halves of the 26.2 and 1.21.11 primers (shaders, samplers, blur).
2. Confirm in the jars:
   - the `--graphicsBackend` values (26.2 `Main`)
   - `RenderSystem.getSamplerCache()` on 1.21.11
   - how custom `RenderPipeline`s are registered and precompiled on each version
   - the Iris API class used for detection
3. Build the backdrop pyramid, the `snowball:glass` pipeline, the glass render state, the `GuiRenderer.draw` HEAD inject and the `guiRenderState` accessor (PLAN.md §6).
4. Extend `SnowglassGameTest` with the six backdrops, and add `-Pbackend=vulkan` for 26.2.

## Reproducing the reference captures

The Lethal Client captures were taken with a ~100-line Node script that drives headless Chrome over the DevTools protocol, with no dependencies. It lived in that session's temporary scratchpad, not in the repo. If it's needed again, rewrite it from REFERENCES.md "How the captures were made".
