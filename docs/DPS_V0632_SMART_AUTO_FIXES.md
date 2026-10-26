# LumaWorks v0.6.32 — Smart Auto fixes (handoff / resume notes)

Branch: `experiment/lumaworks-v0.6.32-smart-auto-fixes` (from v0.6.30 `419271c`).
App: "LumaWorks Auto Fix", `com.dennis.lumaworks.autofix.v0632`, 0.6.32-beta (63201).
It installs alongside the other test builds.

## Status (paused 2026-10-27, 01:30 Brisbane)
- Code written and reviewed by reading only (no local compiler: only GitHub is
  reachable from the session). **CI has not yet confirmed it compiles or passes.**
- Pushing this branch starts `.github/workflows/dps-v0632-lumaworks-smart-auto-fixes.yml`:
  - job 1: `auto_compare` A/B renders → pushed to branch `eval/lumaworks-v0632-auto-compare`
    (original | v0.6.30 | v0.6.32 per photo, plus `results.json`, `summary.txt`),
    then `cargo test -p lightcraft-pipeline --lib` and engine `smart_auto` tests.
  - job 2: ARM64 APK, artifact `LumaWorks-v0.6.32-Smart-Auto-Fixes-ARM64`.
- Note: other branches from 26 Oct also work on Auto: `experiment/lumaworks-v0.6.31-smart-auto-reliability`
  and `experiment/lumaworks-v0.6.32-classic-auto-repairs`. This branch is independent of them.

## Next steps when resuming
1. Check the run: `gh api repos/dennisp5-alt/Dennis-Photo-Studio/actions/runs?branch=experiment/lumaworks-v0.6.32-smart-auto-fixes`.
2. Fix any compile or test failures (most likely: the new tests in `mod lumaworks_v0632_production_auto`).
3. `git fetch origin eval/lumaworks-v0632-auto-compare` and review the A/B images.
   Look especially at the `b_under13_*` (−1.3 EV) cases: for a globally underexposed photo,
   both versions may still only add about +0.6 EV, because the candidate estimate itself
   corrects roughly 40% of the deficit. If that is too dark, that's the next thing to tune.
4. Real photos ≥512 px: the assertive candidate's 128×128 white-subject rule caps its exposure
   at 0 when a bright white area sits on a darker scene, so the blend gives ~0.35 × the basic
   estimate. Check whether clouds/white shirts still hold exposure down too much.
5. Hand over the APK link and a short summary to Dennis.

## Root causes found in v0.6.30
- Any near-white patch (cloud, white shirt, frost; 64×64 grid) capped Exposure at +0.05 EV,
  and a bright sky (>9% bright pixels) at +0.18 EV, before any pixel check.
- Blend clamps limited Exposure to ±0.9 EV and Shadows, Vibrance etc. tightly.
- Render guard was all-or-nothing: >2.8% new clipping reset Exposure, Vibrance, Saturation
  and Curve Lights to zero instead of trimming them.
- The guard rendered with the old white balance while Auto applied a new one.
- Smart Auto replaced a chosen WB preset (Daylight, Tungsten…) but said WB was kept.
- Kotlin: double tap ran Auto twice (two undo steps); WB wording differed between one-tap
  and Preview; the toast hid Contrast, Highlights, Shadows, Whites, Blacks.
- Correction to an earlier claim: the flat base tone with +68 contrast applies to RAW only;
  JPEGs use `ToneMap::display` (identity at neutral).

## What changed
- `crates/pipeline/src/auto.rs`
  - `smart_auto_tone_report_balanced` now = `smart_auto_tone_report_balanced_with_wb(.., None)`.
    v0.6.30 logic kept as `smart_auto_tone_report_balanced_v0630` (A/B and tests only).
  - New version: analyses under the planned WB; wider clamps (exposure ±1.5 …); nano white
    subject keeps +0.05; micro white / bright sky → exposure ≤ +0.5 with Highlights/Whites
    protection applied after `auto_finish::refine`; flat-night rule unchanged.
  - `production_quality_guard`: 192 px renders before/after (planned WB included); order:
    structure loss → dark over-lift (limit 24+30·EV) → new white clipping (Highlights recovery
    first, then bisect brightening moves; limit 1.5% on top of what the WB alone clips) →
    new black clipping (2%). Trims land on slider steps (toward zero) so the checked tone is
    the applied tone. Legacy `preview_quality_guard` untouched.
  - New tests: `mod lumaworks_v0632_production_auto`.
- `crates/engine/src/cmd/develop.rs`: `develop.auto` and `develop.autoPreview` keep any WB
  that is not As Shot/Auto (presets too), pass the planned Auto WB to Smart Auto, and return
  `preservedWbMode`.
- `AppViewModel.kt`: `smartAutoBusy` guard, ignores a result for a photo the user left,
  shared `smartAutoWbStatus` / `smartAutoSummary`, `warm_light_preserved` message in WB Auto.
- `crates/pipeline/examples/auto_compare.rs`: A/B tool used by CI.
- Version, app ID and app name as above.
