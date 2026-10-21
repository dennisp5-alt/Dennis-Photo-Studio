# Dennis Photo Studio — Auto Tone & Auto WB: Development Objective and Audit

**Checkpoint:** 21 October 2026  
**Reference implementation:** `studio-v0.6.8-lightcraft-refinement-auto`  
**Status:** Source-level audit and acceptance requirements; **not** validated photographic performance or a release sign-off.  
**Privacy:** Do not upload the user's private photo collections, EXIF identifying information or image-derived private datasets to GitHub.

## Mission — one-click natural photographic development

Pressing **Auto** must analyse the complete photograph and choose combined, editable Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Curves, Vibrance, Saturation and white-balance settings that produce the best *natural* rendition possible.

The system must make meaningful corrections to clearly imperfect photographs and may make tasteful subtle refinements to already acceptable photos. Zero adjustment is correct **only when the unchanged rendering is demonstrably as good as or better than feasible alternatives**. Passing preservation-only tests does not prove Auto works.

The app remains fully offline. Keep manual sliders, editable curves, local masking, rendering, detail and export quality intact. Never substitute a baked-in AI-style filter for the existing photographic development engine. Do not automatically start an Android build or change a release baseline when this document is read.

## Reference audit versus original Lightcraft

A user-supplied independent audit of upstream `storytold/lightcraft` at commit `5fe74557eca64638b1f9015c9769f7e52a889fa2` reports a fixed tone recipe of Contrast +68, Highlights −66, Shadows +52, Vibrance +30 (only Exposure varies), using brightness regression calibrated on 24 Sony RAW files; grey-world-style weighted WB can respond to coloured subjects rather than illuminants. The synthetic 90%-foliage/10%-grey tint +66 counterexample is a formula reproduction, **not** an Android build test. These upstream claims are context provided by the user, not independently established by this current-branch source audit.

The current `studio-v0.6.8-lightcraft-refinement-auto` has a substantially different implementation; do **not** revert to upstream's fixed tone settings or its unrestricted weighted-average WB.

## Verified v0.6.8 implementation findings

**Code:** `crates/pipeline/src/auto.rs`, `crates/engine/src/cmd/develop.rs`, Android `AppViewModel.kt` and preview dialog.

1. `smart_auto_tone` already computes luminance percentile spread (p05/p50/p95/p995), shadow and bright fractions, recoverable midtones, approximate subject-edge EV, chroma statistics, warm emitter evidence, and some top/bottom scene statistics. It does **not** use the upstream fixed +68/−66/+52/+30 recipe.
2. Core exposure correction is capped around +0.46 EV or −0.35 EV, with a +0.18 EV high-highlight cap, a +0.12 EV backlit-sky cap, conditional no-exposure scene gates and a very small `v0.6.8` ordinary-photo nudge. These are **potentially inadequate** for some real exposure errors. Assess actual rendered clipping rather than removing caps unconditionally.
3. Some tone formulas multiply several independent eligibility gates, and others set Shadows or Curves to zero for low-key or strongly coloured scenes. Protection is valuable, but the gates can suppress justified foreground or subject corrections.
4. The user-facing Smart Auto always emits `whites:0.0`, even though Classic `auto_tone` can compute Whites. The complete global tonal control set is therefore not currently used.
5. `preview_quality_guard` renders a 160 px comparison and can zero Exposure, Curve Lights, Vibrance, Saturation, Blacks and portions of the Curve proposal after clipping/edge changes. It does **not** attenuate a rejected candidate toward a feasible lesser strength or retry. More importantly its `baseline` starts from defaults plus WB and point curves—not the complete `DevelopSettings` currently shown in the editor. It therefore cannot reliably compare current versus proposed *whole edits*.
6. `auto_wb_report` uses low-chroma candidate pixels (chroma up to 0.24) and spatial 4×4 agreement, with a warm-light suppression heuristic, confidence thresholds, Kelvin/tint caps and final small-change deadbands. This improves on one global weighted mean but can miss true casts when neutral references look strongly coloured. A neutral-looking coloured object can still bias results. The warm-light gate currently precedes raw-versus-rendered-specific reasoning.
7. `develop.autoPreview` returns only final tone controls, WB report and final candidate settings, not the pre-gate candidate, measured evidence or rejection reasons. The Android preview summary hides WB temperature/tint deltas and several Light controls. `applyAutoPreview()` invokes `develop.auto` to recompute rather than apply exactly the accepted preview settings; evaluate parity.
8. Pipeline architecture already supports raw-specific camera-space WB matrices, rendered-image Bradford WB, scene-linear local tone, independent tonal mapping, parametric/point curves, masks and exports. Preserve this work and test RAW and JPEG through their real render paths.
9. Recent v0.6.8 screenshots showed only Contrast +4 on one photo and only Vibrance +5 on another, with WB correction indicated. These are user observations; they do not independently measure final output quality.

## Target architecture

### Tone analysis and correction
- Compute scene-referred and *rendered* histogram/clipping statistics; distinguish high-key/low-key intent from exposure defects using multiple regions and subject evidence, not global median alone.
- Produce a **joint** tone plan: Exposure, Contrast, Highlights, Shadows, Whites, Blacks and Curves interact. Rank candidate plans based on actual output while penalizing lost highlight/black detail, crushed local contrast, excessive texture amplification, skin/subject colour distortion and unintended changes to atmosphere.
- Apply stronger corrections when needed and supported by data; if a plan breaches a constraint, search for a **smaller feasible correction** instead of vetoing unrelated controls.
- Keep global Auto useful for normal photographs. Add regional Auto *only where* it demonstrably improves results over global edits; use existing masking infrastructure with reliable segmentation, feathering, editable masks and clear quality gates.
- Preserve neutral and colour intent; no arbitrary mandatory saturation or tone recipe.

### White balance
- Estimate an illuminant from independent evidence: neutral candidate reliability and spatial agreement, dominant material/subject colour exclusion, illumination consistency and source metadata/camera model.
- Real casts can produce chromatic candidate neutrals. Do not assume only near-grey RGB values can carry WB evidence.
- Separate warm ambient illumination from unwanted cast; allow strong corrections when supported, especially on RAW, and modest calibrated residual corrections on JPEG.
- Report confidence *and why*: presence and distribution of plausible neutral references, agreement/disagreement across regions and source-specific uncertainty. Retain as-shot if uncertainty is meaningful, not merely for aesthetic conservatism.

### Observability
- Create a structured, opt-in local diagnostic report (JSON, not photo contents): source type and rendering mode; exposure distribution per region; highlight/shadow and clipping fractions; initial tone candidates; every reduction/rejection **with reason**; candidate-search results; final applied sliders and WB; original/suggested output stats; renderer/performance timings.
- Record `proposal_pre_guard`, `proposal_post_guard`, `wb_candidate`, `wb_applied`, and `rendered_before_after`. Never conflate a missing estimate with a vetoed estimate.
- Ensure the non-destructive preview uses the **same actual candidate** later applied; show explicit WB temp/tint delta and the complete summary. Avoid save/undo on Cancel.
- Keep photo assets private and diagnostics offline unless explicitly exported by the user.

## Validation and pass/fail criteria

**The unit of success is final rendered image quality, not slider movement or synthetic unit-test success.**

Use the user's photo archives **locally**, not in a GitHub commit, with train/tune and held-out splits tracked separately. Include:
- Controlled −/＋ exposure errors, including >0.5 EV where recoverable; compare recovered subject brightness and new clipping to known originals.
- Bright skies with dark foreground, night/fire/sunset with intentional atmosphere, high-key and low-key scenes, portraits/skin tones, dominant foliage or sky, saturated flowers, neutral surfaces under colour casts, mixed lighting.
- Camera JPEG, edited JPEG, and RAW where available; preserve original pixel/render colour semantics.
- True source/edited image pairs where they exist. Never assume unrelated edited photos constitute ground-truth before/after pairs.
- Compare (A) original/current photo, (B) `studio-source-v0.1` Classic Auto, (C) v0.6.5/0.6.8, and (D) revised Auto through **the actual pipeline** at matching output settings.
- Assess visual preference by direct A/B review blind to version when feasible, and objective clipping, luminance recovery, colour drift, texture/noise damage and consistency.
- Evaluate failures explicitly, not just averages. No regression to correct fire/warmth handling or finished photographs merely to increase the fraction of nonzero sliders.
- Before release, verify preview/application equivalence, manual controls, masking persistence, undo, full-res exported JPEG/RAW behavior, performance and offline operation on Samsung Galaxy S23 Ultra.

### Development sequence (do not automatically build at every stage)
1. Instrument `smart_auto_tone` and `auto_wb_report` and fix the baseline guard's use of current full `DevelopSettings`; verify pre/post decisions and actual preview/apply correspondence.
2. Evaluate real photographs and classify misses as **estimation**, **suppression**, **rendering mismatch**, or **WB misidentification**; establish held-out benchmarks.
3. Redesign joint tone candidates and scale-down-on-veto; address structurally absent Whites decisions and exposure-strength restrictions only after failure analysis.
4. Improve source-aware multi-region WB with measured confidence and corrected cast cases.
5. Validate exported images visually and numerically; then make one consolidated experimental APK, preserving v0.6.5/v0.6.6/v0.6.7/v0.6.8 baselines.
6. Pursue automatic regional masks as a subsequent validated enhancement once the global Auto foundation has proven itself.

**Non-goal:** Manufacturing small nonzero changes merely to make Auto appear active. The criterion is balanced, natural, dependable one-click development.
