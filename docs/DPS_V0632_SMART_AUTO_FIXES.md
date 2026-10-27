# LumaWorks v0.6.32 — Smart Auto fixes (handoff / resume notes)

Branch: `experiment/lumaworks-v0.6.32-smart-auto-fixes` (from v0.6.30 `419271c`).
App: "LumaWorks Auto Fix", `com.dennis.lumaworks.autofix.v0632`, 0.6.32-beta.5 (63205).
It installs alongside the other test builds.

## Status (27 Oct 2026)
- beta.1 (`2cf525f`): CI green: all pipeline tests, engine `smart_auto` tests,
  Kotlin tests, APK. The A/B on 40 photos showed the guard/WB fixes are safe (17 normal
  photos unchanged) but that the real weakness is exposure strength in both versions:
  −1.3 EV photos got only about +0.5 EV, −2 EV photos about +0.5 EV, and overexposed photos
  got no correction at all.
- beta.2 (`20857fe`, run 40402665616): adds a stage-1 global exposure correction (below).
  CI green (all tests, APK). A/B: 17 normal photos unchanged except kodim02 (+0.27 → +0.48);
  −1.3 EV photos now +0.86…+1.53 (mean luma 92–121 vs originals 83–124; v0.6.30 69–97);
  −2 EV +1.68/+1.80; overexposed −0.23/−0.67; Moon, night, fire, white flower unchanged.
  Handed to Dennis for testing on his phone.
- CI (`.github/workflows/dps-v0632-lumaworks-smart-auto-fixes.yml`):
  - job 1: `auto_compare` A/B renders → branch `eval/lumaworks-v0632-auto-compare`
    (original | v0.6.30 | v0.6.32 per photo, plus `results.json`, `summary.txt`),
    then `cargo test -p lightcraft-pipeline --lib` and engine `smart_auto` tests.
  - job 2: ARM64 APK, artifact `LumaWorks-v0.6.32-Smart-Auto-Fixes-ARM64`.
- Other branches from 26 Oct also work on Auto: `experiment/lumaworks-v0.6.31-smart-auto-reliability`
  and `experiment/lumaworks-v0.6.32-classic-auto-repairs`. This branch is independent of them.

- beta.3: tuned against Lightroom Auto on 10 of Dennis's photos (LR vs beta.2: brightness
  within ±10 on 7/10, but our black point 3–7 levels higher, saturation 0.02–0.06 lower,
  daisies white point 229 vs 248). Adds, for ordinary scenes only (not night/fire/white
  subject on dark/intentional dark): vibrance ≥ +5 when the photo has colour, and a rendered
  black/white point match (target luma p1 10, p99 238) via curve Shadows/Highlights +
  Blacks/Whites, within the guard's clipping limits.

- beta.4: beta.3 matched Lightroom's black points (8–15 vs LR 7–17) but its white target
  (238) over-brightened photos LR leaves at 213–233. White target now 228, applied only when
  the rendered p99 is below 218.

- beta.4 vs Lightroom on Dennis's 10 photos: average difference 6.8 (beta.2 7.0); blacks
  within 0–4 levels, colour within 0.03. Remaining gaps by design: fire, daisies.
- beta.5: Subject/Background masking. Problems: Background was a different, square-squashed,
  unrefined 128px path (did not meet the Subject edge); SegFormer-B0 sees people at 128×128,
  so hair/outline were blobs and leaked into similar-coloured walls. Now: Subject and
  Background are one aspect-correct selection (Background = exact byte complement); when a
  person is found, MODNet (Xenova/modnet, Apache-2.0, assets/modnet_portrait.onnx) gives a
  hair-level matte at 1024px input, gated to where SegFormer saw people (dilated ~4%), stored
  as 512×512 logits. Animals/objects keep the refined SegFormer path. CI posts MODNet overlays
  of Kodak portraits to eval branch masks/.

## Next steps (if resuming)
1. Check the run: `gh api repos/dennisp5-alt/Dennis-Photo-Studio/actions/runs?branch=experiment/lumaworks-v0.6.32-smart-auto-fixes`.
2. Fix any test failures, then `git fetch origin eval/lumaworks-v0632-auto-compare` and look
   at `b_under13_*`, `c_under20_*`, `e_over08_*` and the 17 `a_orig_*` (should be unchanged
   except kodim02).
3. Hand over the APK link and a short summary to Dennis.
4. Not done yet, worth raising with him: flat/hazy photos stay flat (the display tone map's
   Blacks/Contrast sliders are gentle, and Auto proposes small values); Auto WB barely corrects
   a strong colour cast (by design it keeps warm light; a daylight scene with an orange cast
   looks the same statistically).

## Stage 1: global exposure correction (`exposure_correction` in auto.rs)
- Targets from the Kodak reference set: median −0.45 EV, brightest 0.5% (p99.5) +2.20 EV,
  darkest 5% (p5) −2.50 EV (all relative to middle grey).
- Too dark: lift = min(target median − median, target p99.5 − p99.5), applied when ≥ 0.35 EV,
  at most +1.5 EV. A photo with a bright highlight keeps its mood; one where nothing is
  bright is treated as underexposed.
- Too bright: p99.5 at white (≥ 2.30), shadows lifted (p5 > −2.15), not high key →
  darken by 0.85 × min(median excess, p5 excess), at most −1.2 EV.
- Protected (no correction): firelight (candidate's emitter flag), 128×128 white subject,
  flat night, intentional darkness and isolated lights (the auto_finish definitions).
- Stage 2 = the existing Smart Auto run on the photo as it looks after stage 1; its exposure
  is added (total capped at ±2.0 EV), and it may not undo a stage-1 darkening.
- Predicted on the A/B set: originals unchanged except kodim02 (+0.48 base); −1.3 EV photos
  +0.6…+1.2 base; −2 EV photos +1.5; overexposed −0.23 and −0.67.

## Root causes found in v0.6.30
- Exposure estimate corrects about a third of an underexposure (and the old blend clamp
  was ±0.9 EV); overexposure is only corrected once the median is above +0.85 EV.
- Any near-white patch (cloud, white shirt, frost; 64×64 grid) capped Exposure at +0.05 EV,
  and a bright sky (>9% bright pixels) at +0.18 EV, before any pixel check.
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
  - New version: stage 1 above; analyses under the planned WB; wider clamps; nano white
    subject keeps +0.05; micro white / bright sky → stage-2 exposure ≤ +0.5 with
    Highlights/Whites protection applied after `auto_finish::refine`; flat-night rule unchanged.
    `AutoToneAuditReport.exposure_correction_ev` reports stage 1.
  - `production_quality_guard`: 192 px renders before/after (planned WB included); order:
    structure loss → dark over-lift (limit 24+30·EV) → new white clipping (Highlights recovery
    first, then bisect brightening moves; limit 1.5% on top of what the WB alone clips) →
    new black clipping (2%). Trims land on slider steps (toward zero) so the checked tone is
    the applied tone. Legacy `preview_quality_guard` untouched.
  - Tests: `mod lumaworks_v0632_production_auto`; one old bound (exposure ≤ 0.90, the old
    clamp) now uses `MAX_AUTO_EXPOSURE_EV`.
- `crates/engine/src/cmd/develop.rs`: `develop.auto` and `develop.autoPreview` keep any WB
  that is not As Shot/Auto (presets too), pass the planned Auto WB to Smart Auto, and return
  `preservedWbMode`.
- `AppViewModel.kt`: `smartAutoBusy` guard, ignores a result for a photo the user left,
  shared `smartAutoWbStatus` / `smartAutoSummary`, `warm_light_preserved` message in WB Auto.
- `crates/pipeline/examples/auto_compare.rs`: A/B tool used by CI.
- Version, app ID and app name as above.
