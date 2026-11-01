# Snowglass reference board

The images live in `.design-refs/` at the repo root. That folder is git-ignored: every image there is a
third-party copyrighted screenshot kept **for direction only**. Never commit, ship, trace or copy any of it.
Nothing below describes a logo, icon, font, wallpaper, cosmetic or colour scheme to reuse.

Collected 2026-11-01. Each entry gives what the image teaches and what Snowglass takes from it.

## Apple Liquid Glass (material)

| File | Takeaway |
|---|---|
| `apple-02-clear-look.jpg` | The clear end of the range: widgets and dock are almost pure refraction, separated from the wallpaper only by a bright rim and a slight lift. It reads only because the wallpaper behind is calm and desaturated. Over Minecraft snow this would fail, so the clear end has to be guarded by adaptive tint. |
| `apple-03-dark-tint.jpg` | The tinted end: smoky dark panels, with icons and text in a single accent hue. Contrast stays high over a busy, high-contrast photo. This is close to Snowglass's "Thick / Fully Tinted" stops and the right model for HUD pills over bright scenes. |
| `apple-video-details-1..8.png` | Frames from Apple's "details" clip. **3–4:** round media buttons, where the rim lensing bends the scene and the pressed button gets more saturated light. **5:** the switch thumb turns into a clear, wider lens while pressed. **6:** the slider thumb is a small glass pill over a filled track. **7:** the segmented control's selection pill magnifies the label under it as it travels. These are our switch, slider and segmented-control behaviours. |
| `apple-video-tabbars-1..8.png` | Frames from the Music tab-bar clip. Scrolling down shrinks the tab bar into a compact capsule, and the mini player merges into the same plane. The selection is a soft tinted capsule, and coloured content behind spills colour into the glass. This is the model for the mod-menu header compacting on scroll, and for the sliding category pill. |

## Golden Gate legibility fixes (iOS 27 / macOS 27)

| File | Takeaway |
|---|---|
| `goldengate-01-slider-ios.jpg` | The new Liquid Glass slider runs from "Clear" to "Tinted", and its caption says tinted "increases opacity, adding contrast to content and controls". A single global slider is exactly our Transparency setting. Even at the clear end, the floating search capsule frosts the text under it so its own label reads. |
| `goldengate-02-max-transparency-ios.jpg` | At maximum transparency, the content directly under a control is blurred hard, which keeps the control's label readable even when the content behind is text. Lesson: **under glyphs, blur beats clarity**. Small controls still need a frosted core, not just a clear rim. |
| `goldengate-03-slider-macos.png` | Most transparent → Default (midpoint) → Most opaque, all with a live preview. The default sits in the middle, which maps to our **Regular** stop at `glassLevel` 50. The Glass Lab and the settings page should both preview the slider live. |
| `goldengate-04-toolbar-before-after.png` | Tahoe's toolbar dissolves into the photos under it. Golden Gate puts a uniform, slightly frosted bar behind the controls once content scrolls under them. Lesson: when the module grid scrolls under the top bar, give the bar a uniform frosted backing and a hairline, rather than letting cards show through at full clarity. |

Reading notes (fetched 2026-11-01):
- **9to5Mac (macOS 27):** "ultraclear to fully tinted" slider; "more uniform refraction and improved contrast"; uniform toolbars; edge-to-edge sidebars; the active window is more clearly distinguished.
- **9to5Mac (iOS 27):** beyond the slider, the blur under text was changed so overlaid text stays legible at maximum transparency.
- **MacStories (macOS 27, p.2):** edges are darker and highlights brighter, which adds depth and readability. The slider runs from transparent to nearly solid, with the default at the midpoint. A continuous bar appears automatically under controls when content scrolls under them.
- **WWDC25 "Meet Liquid Glass" (transcript):**
  - Lensing, not scattering, is the primary cue, and light bends at the edges.
  - Shadow opacity rises over text and falls over light backgrounds.
  - Small elements flip between light and dark with the backdrop; large ones adapt but do not flip. Larger elements simulate thicker glass: deeper shadow, stronger lensing, softer scattering.
  - Never put glass on glass. Content on glass uses fills and vibrancy.
  - Menus pop out of their trigger.
  - Unfocused windows recede.
  - Reduce Transparency means frostier glass; Increase Contrast means black or white with a border; Reduce Motion turns off the elastic effects.
- **Apple HIG "Materials" and the Liquid Glass overview** render with JavaScript, and WebFetch returned only their titles. Their guidance was **not read** this session; the WWDC transcript covers the same material model.

## Minecraft clients (information architecture and behaviour)

| File | Takeaway |
|---|---|
| `lunar-01-mod-menu.png` | A centred dark window over a blurred world: profiles on the left, filter chips and search across the top, and a three-column grid of mod cards (icon, name, Options, gear, Enabled/Disabled bar). The card grid with an on/off state on every card is what players expect. The full-width green and red bars use colour plus text; we use a switch plus state text instead. |
| `lunar-02-profile-editor.png` | The profile list sits in the sidebar, and the profile editor is a separate panel with Reset, Delete and Save. Profiles are first-class. Snowball already has Config Profiles and Mod Profiles, so they belong at the bottom of the sidebar. |
| `lunar-03-settings.png` | A dense settings list with ON/OFF pills, sliders with value labels, and left/right choice arrows, in two columns, under section headers in small caps. It shows how dense a settings page can be and still read. Our rows stay single-column at window width, with section headers. |
| `dawn-01-module-settings.png` | Module settings on the left with collapsible sections (segmented choices, switches, colour wells, sliders with a value box), next to a **live preview of the HUD element** on the right. Reset Settings sits at top right. Snowglass's module-settings pane should show a live preview of HUD modules. |
| `lethal-01-default.png` | The Fabric 26.2 menu: a rounded window holding a sidebar card (logo, MODULES list, user card at the bottom) and a content card with a greeting header. Theme cards show swatch bars and a radio button. Effects rows are a title, a one-line description, a switch and a reset glyph. Calm, near-black navy surfaces with low-contrast hairline borders. |
| `lethal-02..09-theme-*.png` | Eight accent themes. Only the accent moves: the selected card border, the active sidebar item, the switches and the section sparkle. Surfaces stay the same navy. That confirms Snowball's existing approach of named palettes that only change accent and tint. |
| `lethal-10-effects-on.png`, `lethal-11-effects-off.png` | See-Through GUI and Frosted Blur on versus off. In the web demo the only visible difference is the switch state, because the page has no world behind it. The settings model is useful: separate user toggles for transparency, blur and an ambient background. The look is not. |
| `lethal-12-transactions.png` … `lethal-16-settings.png` | Module pages: a header with a count badge, KPI tiles, a profit-display card with an "Edit position" button, chips and segmented filters, list rows with inline actions, and switch rows. Settings has a keybind row ("Open menu key — Right Shift") with a reset glyph. The Settings pane has no sidebar entry in the demo, so the capture script showed that pane directly. |
| `aurora-01-menu.jpg` | The Right Shift menu over a live arena. Category tiles are glass cards with a thin bright rim and an almost clear interior. The selected tile is larger with a brighter rim. A smaller row of utility tiles sits below. This is the closest in-game example of rim-defined glass. Its weakness: labels in a thin pixel font over obsidian are barely legible, which is the problem our adaptive tint solves. |
| `aurora-02-hud.jpg` | A HUD mid-fight: hotbar numbers and LMB/RMB counters as bare pixel text over a bright sky and particles, with no backing. This is the failure case for HUD legibility over bright scenes: HUD values need a backing pill (glass or solid) and text shadow off. |

## Dropped

| File | Why |
|---|---|
| `apple-01-liquid-glass-hero.jpg` | A product line-up. The glass is too small at this scale to show anything about the material. |
| `aurora-03-profiles.jpg` | Despite its name, it shows the same HUD as `aurora-02` with no profiles UI. |
| `aurora-04-preview.jpg` | The site's logo over a shader-pack landscape. No UI. |

## How the captures were made

- The direct downloads came from the URLs in the brief. Lunar's CDN served JPEG under `.png` names and Dawn's file was WebP, so all four were converted to real PNG with `ffmpeg`. `aurora-02-hud.jpg` arrived truncated the first time and was downloaded again until it matched the server's `Content-Length`.
- Lethal: headless Chrome at 1920×1080, driven over the DevTools protocol by a small Node script (no Playwright install). Each capture is the `#lcwin` replica plus 24 px of margin.
- Apple clips: frames taken at 1/16, 3/16 … 15/16 of each clip's length and scaled to 1280 px wide.
