# Snowglass progress

Read this first in any new session, then `PLAN.md`. Branch: `ui/snowglass` (not pushed).

## Current state: M0 done, waiting for approval

**Done (2026-11-01):**
- Repo recon: build, versions, UI architecture, surfaces, config, keybinds, FPS Boost, render mixins, and the launcher's jar check. See PLAN.md §1–4.
- API checks against the Gradle-cache jars with `javap` (there are no decompiled sources in the cache):
  - `GuiElementRenderState`, `GuiRenderState`, `TextureSetup`, `RenderPipeline$Builder`, `BindGroupLayout`
  - the `GuiRenderer.draw` blur order
  - `VertexFormat` builders, `GpuDevice`, `CommandEncoder`
  - the TTF provider record
  - `TimerQuery` and the `graphicsBackend` launch option

  Vanilla 26.2 and 1.21.11 shaders were extracted and read: `gui.vsh/fsh`, `box_blur.fsh`, `post_effect/blur.json`, `screenquad.vsh`.
- Version primers read: 26.2 (first half of the page), 1.21.11 (first half), 1.21.6, and the Fabric 26.2 post. The 26.1 primer's first half has no GUI content; its GUI renames are already applied by this repo's Stonecutter rules.
- Reference board: 44 images in `.design-refs/` (git-ignored); takeaways in `REFERENCES.md`.

**Not verified / not done:**
- Apple HIG "Materials" and the Liquid Glass technology overview pages render with JavaScript and could not be read. The WWDC25 "Meet Liquid Glass" transcript was read instead.
- The second halves of the 26.2, 26.1 and 1.21.11 primer pages were not read. Read them at the start of M2 for the shader, sampler and blur sections.
- Accepted values for 26.2's `--graphicsBackend` option.
- The Iris API class name used for detection.
- Whether `RenderSystem.getSamplerCache()` exists in the 1.21.11 jar (the primer says it does).
- No code was written. No build or game test was run this session.

**Open questions:** PLAN.md §15. Each has a recommended default.

## Next

M1 (design system), once the plan is approved. Start by:
- confirming the Q1–Q8 answers in PLAN.md
- running `./gradlew build`, `./gradlew -p legacy build` and `./gradlew test` on an untouched branch, to record a baseline before any change

## Reproducing the reference captures

The Lethal Client captures were taken with a ~100-line Node script that drives headless Chrome over the DevTools protocol, with no dependencies. It was kept in that session's temporary scratchpad, not in the repo. If it's needed again, rewrite it from REFERENCES.md "How the captures were made".
