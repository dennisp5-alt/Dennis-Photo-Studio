# Dennis Craft Mobile v0.3 (Android)

A separate experimental phone-first offline photo editor for the Samsung Galaxy S23 Ultra, inspired by the mobile workflow demonstrated by the user. This is an Android Java proof-of-concept, NOT a port of PhotoCraft's Rust engine yet. It is independently named and has a distinct Android package com.dennis.craftmobile.

Implemented:
- Android Storage Access Framework image open (JPEG, HEIC and other Android-supported formats), including EXIF orientation through ImageDecoder
- Live preview, original comparison by pressing Hold
- Looks (Original, Natural, Warm, Cool, Dramatic, B&W)
- Exposure, contrast, highlights, shadows, saturation, warmth, tint
- Visible Auto button in the editing strip and more responsive scene-adaptive tone corrections; undo/redo
- Centre crop ratios, 90-degree rotation
- Selectable JPEG export qualities 85/90/95/100 and share sheet; original remains untouched
- No all-files permission, no network permission, no access to existing Dennis Photo Studio data
- Android 15/16 safe insets for visible toolbar/navigation; single-threaded background processing, preview resolution 2000px, output capped to 8192px / 50MP for memory safety

Not implemented: PhotoCraft engine integration, PSD editing, layers, masks, heal/clone, raw DNG pipeline, freeform crop handles, 16-bit colour, ICC profiles, metadata preservation. Do not label this as a PhotoCraft port or release until engine integration is real.

## Build

Requires JDK 17 and Android SDK 35 and Gradle 8.10.2.

    gradle -p photocraft-mobile assembleDebug

GitHub Actions automatically produces an APK as a workflow artifact when this feature branch receives a push.

## Why separate?

The production Dennis Photo Studio code is not modified. This app lives in its own photocraft-mobile directory on an isolated feature branch and uses a different Android package. Migration to a dedicated repository can occur before future releases.

## Planned PhotoCraft integration

Evaluate the existing arm64 Android shell from kanaaka/photocraft-android pinned to PhotoCraft 0.7 and replace the desktop egui shell with a mobile-first Kotlin/Java to Rust JNI command bridge. Audit source and licence, then implement native colour processing with real masks, layers and composite/export parity. PhotoCraft is MIT OR Apache-2.0, however ArtCraft trademarks and brand assets must not be reused in a modified product.


## v0.3 visual + system picker
- New adaptive launcher icon: cobalt/indigo aperture symbol on midnight-blue background, unique package branding.
- Dark navy editor workspace, compact glyph toolbar, title, icon navigation (Looks / Adjust / Auto / Crop / More), improved label hierarchy.
- Looks use genuine tiny processed previews instead of text-only tiles.
- Native Android system Photo Picker on Android 13+ (including S23 Ultra / Android 16). Fallback to system content picker on earlier Android versions. Requires no photos/media or all-files permission.
- Export opens a quality-selection dialog with JPEG 85/90/95/100 and Save / Share actions.
- Existing Auto processing math, manual controls and full-resolution export path unchanged from v0.2.
- Still intentionally NOT a port of the PhotoCraft Rust engine. No layers/heal/masks yet.

### Runtime caveat
Updated UI needs an on-device visual acceptance pass; GitHub Actions build success is not device validation.

## v0.4: 64×64 highlight-protected Auto

- 4,096 spatial zones sampled from the original preview using adaptive strides.
- Per-zone luminance and bright RGB-channel occupancy; 3x3 smooth spatial weight map.
- Global exposure limited by bright-region occupancy and scene high percentile.
- Dark foliage lifted *locally*, with RGB-channel headroom gating bright skin, clouds and petals.
- Same immutable scene zone data applied to preview and JPEG output (the analysis grid is mapped to output dimensions).
- Auto Strength 0–100% lives in Adjustments after pressing Auto; 0% removes the automatic tonal effect.
- Auto runs on a background worker; Look presets, normal manual adjustments, crop, picker and export remain on their v0.3 processing path.
- No claims of subject segmentation, semantic masks or integrated PhotoCraft Rust engine.

### Visual regression checks
Test against the uploaded flower and Halloween woodland pairs: no near-white petal blowout or washed-out skin. Also test a genuinely underexposed photo so the new highlight limits do not prevent useful correction. Real-device runtime testing remains necessary.


## v0.5 — grouped detail controls with scene-based Auto

Two groups added within Adjustments:
- Noise + Sharpen: Luminance NR, Color NR, Sharpen
- Clarity + Texture: Clarity, Texture, Dehaze

Each group has Auto Group and three individual numeric 0–100 controls. Main Auto updates both groups using 64×64 spatial scene probes. The established v0.4 tonal Auto and its highlight safeguards are deliberately unchanged.

Processing (DetailEngine.java): bounded-ring-buffer pixel filters combine guided 3×3 luminance and chroma smoothing, mild unsharp detail enhancement, restrained high-frequency texture, coarse local contrast clarity, and a low-amplitude contrast-based Dehaze approximation. The effects operate on the finished editable bitmap in place, keeping extra memory proportional to photo width rather than photo area. Effects apply to both previews and JPEG exports. Zero strength yields the original v0.4 processing path. Sharp detail and near-white highlights are gated to limit halos and clipping.

Auto: 64×64 local scene probes estimate flat-patch luminance/chroma variation, structural detail and tonal spread. Conservative recommendations can legitimately be zero if noise or haze is not supported by evidence. Auto Strength on the Tone group still affects the v0.4 tonal Auto; detail group strengths are independently adjustable.

Limitations: this is an image-processing prototype, not trained or validated scene intelligence. Real-device testing with ISO/noise, portraits, clouds and foliage is required before considering the algorithms production-quality. No PhotoCraft Rust port is claimed. Dehaze is contrast-based, not physical scattering inversion or depth estimation.


## v0.6 — cautious 64×64 Auto White Balance

- Main Auto now estimates RGB channel gains from a 64×64 spatial grid, alongside existing tone and detail Auto. Separate grouped Auto actions remain as v0.5.
- A five-pixel cross average samples candidate neutral surfaces. The estimator rejects dark, clipped and high-chroma samples, requires neutral regions distributed across the photograph, trims colour-ratio outliers and caps gains to approximately ±6%.
- If plausible neutral evidence is insufficient, Auto WB deliberately leaves colours unchanged. It does not claim to measure Kelvin, identify faces or classify a lighting source.
- Auto WB gains are applied to the image inside the existing spatial 64×64 render path, with per-channel headroom protection and luminance neutralisation; identical settings are used for preview and export.
- Manual Warmth and Tint operate after the Auto pass (v0.5 previously ignored both when Auto had activated).
- Auto Strength affects calculated Auto WB as well as Auto tone. Group Auto for Noise + Sharpen or Clarity + Texture does not adjust WB.
- Auto status shows either WB corrected or WB preserved so users can distinguish a justified correction from a no-op.
- Existing Looks, JPEG export quality, modern picker, v0.5 Detail Engine and v0.4 Auto tone design are otherwise unchanged.

### Suggested visual checks
- Lavender flowers: colour and petal highlights must remain natural; neutral deficiency should produce conservative results.
- Halloween woodland: natural skin/pumpkin hue, no magenta/blue bias.
- Neutral indoor image under mild tungsten/fluorescent cast: some WB improvement without flattening the intended ambience.
- Sunset or blue sky without neutrals: intentional colours must remain.
- Manual Warmth/Tint after pressing Auto: sliders must visibly change preview and exported JPEG.


## v0.6.1 performance checkpoint (experimental)

This update changes scheduling/loading only, not the actual v0.6 tone, Auto WB, colour or detail formulas.

- Replaces the shared background queue with dedicated photo-opening, preview-rendering and JPEG-export workers. A queued full-size export cannot directly hold up image-selection preview decode.
- Clears the old photograph and shows an explicit opening placeholder as soon as the new photo is selected. Displays the decoded new photo immediately while its edited preview renders.
- Removes superseded preview jobs from the queue; checks cancellation every eight scanlines both in the tone processor and DetailEngine filter.
- Debounces continuous slider changes at 90ms and uses an 1100px temporary editing preview during a drag. The full 2000px-cap preview is regenerated when the finger lifts. Photo export still renders up to the original 8192px/50MP cap with all effects.
- JPEG export now reports read/process/encode stages, serialises concurrent save taps, and bypasses the edit pipeline for truly unedited, uncropped photos.
- Job completion callbacks are checked for stale selection/render requests and discarded when the user has since opened another photograph.

Boundaries: The cost of applying full-resolution advanced filtering and JPEG compression remains proportional to the original image size and available phone memory. This is not a replacement of the rendering algorithms. Test real-device performance, compare preview/final appearance and test multiple rapid photo selections before considering stable.


## v0.6.2 — reduced processing overhead and swipe-selected gallery photos

- Modern system picker supports up to 24 user-selected images per session, on Android 13+ via MediaStore.ACTION_PICK_IMAGES + EXTRA_PICK_IMAGES_MAX; Android 10–12 uses ACTION_GET_CONTENT with multiple selection. The app does not request general gallery access.
- Swipe left or right across the image to switch between selected files, or tap the top-right photo counter to advance. Swiping has boundaries and cannot navigate photos the user has not selected. Each selected photo retains its Edit parameters while the picker session is active; original files are never overwritten. Undo/redo history is reset on switching.
- Decoder target for the editor preview is 1600px / 2.56MP instead of 2000px / 4MP, and the temporary live slider preview is 960px (previously 1100px). JPEG exports remain up to the same 8192px and 50MP cap: no downsampling change to export.
- When an original has no edits, the app displays the decoded source directly instead of running the full processing pipeline.
- DetailEngine now caches original-row luminance in a bounded rolling buffer. Repeated RGB-to-luma conversions in the 3x3 and large-radius filter taps are avoided without changing the filter settings or neighborhood sampling kernels.
- Previous bitmap allocations are detached and recycled after outstanding preview work, limiting memory retention during multi-image navigation.
- Visual validation on a real S23 Ultra is still needed for performance gains and for finger-swipe detection on the gallery.

The existing v0.6.1 rendering architecture, v0.6 Auto WB, the v0.5 grouped filters, v0.4 Auto tonal parameters and all full-resolution export settings remain untouched.


## v0.6.3 — experimental multi-scale scene-aware Auto

- Built strictly on preserved v0.6.2 checkpoint commit 9d4838b2757f1fc196d6906a06dc591d02a3a323. Original fallback branch checkpoint/dennis-craft-v0.6.2-approved-20261024 remains untouched.
- Retains all 4,096 fine analysis zones, local highlight protection and scene-aware shadow processing.
- Pools the existing 64x64 sampled statistics into an independent count-weighted 16x16 scene map (256 coarse cells; no second bitmap scan).
- Coarse map tests *spatially clustered* adequately exposed regions, surrounding darkness, scene brightness percentiles and bright-pixel coverage. A dark background alongside adequately exposed bright regions is treated as intentional low-key lighting rather than an order to brighten everything. No face, ancestry, skin, or subject recognition is claimed.
- The exposure ceiling becomes +0.07 EV in clearly detected low-key scenes instead of the previous possible +0.45 EV; shadow lift is also reduced to 55% of the previous recommendation. Borderline dark-background scenes can receive more modest caps. Truly dark scenes with no adequately exposed regions keep the old up-to-+0.45 EV correction ability.
- The bottom Auto status identifies when low-key protection was selected. Undo/redo restores the scene-decision flag with the remaining edits.
- Crucially, existing tone-rendering math, white-balance decision algorithm, contrast/sharpen/noise/texture/dehaze algorithms, photo-picker/swipe controls, JPEG export resolution and code signing are unchanged.
- Expected result is not a clinically calibrated or semantically correct exposure detector; it is a heuristic. Validate on device before promoting.

Approximate offline regression checks from supplied image/screenshot pixels:
- Low-key portrait screenshot crop: old +0.45 EV -> new +0.07 EV.
- Woodland original: old +0.085 EV -> unchanged +0.085 EV.
- Flower original: old +0.025 EV -> unchanged +0.025 EV.
- Artificially darkened woodland / portrait / flowers: preserve ability to reach +0.45 EV.

Caution: these are algorithm-level calculations from test inputs, not Android full-resolution render benchmarks or exact original camera source measurements.


## v0.6.4 — experimental three-level Auto with coarse atmosphere protection

Source: unmodified approved checkpoint/dennis-craft-v0.6.3-approved-20261024, commit 66f2013fd012f07c01f07b2ac148c7c39abe6bf2. Work occurs only on experiment/dennis-craft-mobile-v0.6.4-atmosphere-auto.

- 64×64: keep existing luminance/colour analysis, regional shadow weights, WB and image-rendering formula.
- 16×16: keep existing clustered-region / low-key exposure decision.
- 8×8: NEW 64 coarse cells, pooled from the existing 4,096 64×64 measurements (weighted by sample count, no bitmap rescan). A conservative atmosphere gate recognises dark overall scenes with strong orange/yellow highlights or sparse nighttime highlights by combining coarse dark-cell coverage, scene luminance percentiles and bright/warm pixel coverage.
- When atmospheric evidence is strong, reduce *only* the maximum global exposure and shadow strength. Warm-glow scenes limit positive exposure to +0.025 EV and shadow strength to 22% of the existing base recommendation; isolated bright nighttime lights gate at +0.045 EV and 32%. This can only restrain, not increase or undo existing 16×16 protection.
- All other scenes retain exactly the v0.6.3 analysis decisions, including genuinely underexposed photos without confident warm-light/atmosphere evidence.
- UI status and Auto toast show “Warm atmosphere protected” or “Night lights protected” when applicable.
- No semantic identification of fire, portraits or skin. The warm-light heuristic cannot accurately classify every photographic scene.
- White-balance estimator, all pixel-processing routines, detail DSP, saving, selected-photo swiping, preview memory/performance settings and JPEG output format/quality are unchanged.

Offline algorithm checks based on user-provided examples:
1. Dark fire with small flame: v0.6.3 +0.45 EV → v0.6.4 +0.025 EV, warmer flame retained, reduced shadow lift.
2. Brighter fire with logs and intense flame: positive global EV already capped at +0.025 EV; shadow recommendation reduced.
3. Indoor portraits: no warm-light false positive; v0.6.3 decision preserved.
4. Artificially darkened portrait and ordinary daylight shots: do not trigger warm-light gate; v0.6.3 decision preserved.

Real-device photography comparisons (including exported JPEG vs preview) are required before replacing approved build. When in doubt, the 8×8 layer does nothing.


## v0.6.5 — experimental four-level scene safeguard

- Based on unmodified v0.6.4 checkpoint/dennis-craft-v0.6.4-approved-20261025, commit 564a7b5d6c3cadcdb98858c5afe6a5b176039fa2.
- 64x64 local measurements, 16x16 exposure judgement and 8x8 atmosphere decisions remain in place.
- NEW 4x4 (16-cell) intent assessment pools the same 4096 local samples with no additional bitmap scan.
- Only where the previous 8x8 judgement already identified warm/night atmosphere AND 4x4 finds both broad darkness and illuminated regions, cap positive global exposure at +0.015 EV and shadow scale at 0.14. Otherwise v0.6.4 recommendations remain unchanged.
- The 4x4 layer only limits exposure and shadow lift; it never increases exposure or changes white balance.
- The status line reports 4x4 protection when activated.
- Existing colour, WB, detail DSP, swiping, preview and JPEG export remain untouched.
- Test on the phone before considering promotion; scene classification is heuristic. The 128x128 proposal is intentionally deferred.


## v0.6.8 — four-grid scene intelligence experiment (normal-development baseline)

Derived *directly* from frozen v0.6.5 commit 21725afbe27cadf9b91fe5fc5fe910f23861937a.
The 128×128 and 256×256 experiments are intentionally excluded.

- Keep 4×4 scene atmosphere, 8×8 illumination, 16×16 regional exposure, and 64×64 local shadow/highlight maps.
- Add SceneContentGuard: a connected midtone-region analysis on pooled 16×16 cells, using data already available from the 64×64 sampling pass. It identifies structurally prominent *lit regions*, not subjects/faces/skin as semantic objects. It requires 72+ dark cells, strong region evidence, and non-underexposure before imposing a maximum +0.09 EV and 0.66 shadow-strength cap.
- Preserve genuinely underexposed images by not triggering the guard where the existing regional judge finds inadequate exposure.
- When existing low-key/night atmosphere protection or a well-supported lit region indicates the scene is intentionally dark, reduce local shadow lift by up to 25% only in near-black 64×64 zones; feather through the existing 3×3 zone smoothing.
- All corrections only restrain v0.6.5 decisions. In ordinary scenes both original exposure/shadow decisions and original 64×64 weights are kept unchanged.
- Manual adjustments, Auto WB (unchanged), colour rendering, noise reduction, sharpening, dehaze, texture, clarity, zoom, picker and swipe, JPEG quality, export and persistence stay as before.
- Status line identifies structural / near-black protection when detected. Since no semantic model is used, it does NOT claim to recognise people's identity or skin tone.
- Prototype for A/B checking and benchmarking against v0.6.5, not automatic promotion to production. New protections can be tuned or removed independently.


## v0.6.9 — Back to v0.6.8 with Auto/Manual Straighten

Source: checkpoint/dennis-craft-v0.6.8-before-restore-lab, commit 1af8d3dd5f22c8177c6224b686bff3d75260164a.

This project intentionally excludes ALL Restore Lab/AI models, TensorFlow Lite and face detection features, plus the 128x128/256x256 analysis experiments. Four-grid Auto and manual colour/detail work exactly as v0.6.8.

Crop tab features: Auto Straighten, Reset Angle, Manual Straighten slider from -15 degrees to +15 degrees at .1-degree increments, Original Ratio, 1:1, 4:5, 16:9 and Rotate 90. Automatic correction uses Sobel edges and a weighted orientation histogram for axes with evidence spread across multiple photo regions; auto result capped at ±10°. If no reliable straight reference is detected, the angle is untouched and manual adjustment remains available.

The requested angle is stored non-destructively in each photo's Edit snapshot, restored in Undo/Redo and retained on swipe among selected photos. Looks and tone Auto preserve geometrical adjustments. Both preview and full-res JPEG export rotate and zoom into the existing same-sized output frame to avoid black corners, at the cost of some edge cropping. Aspect crop and unchanged detail engine run after geometric straightening.

Build branch: experiment/dennis-craft-mobile-v0.6.9-auto-straighten. A separate working version of Dennis Craft Mobile, not a change to the unrelated Dennis Photo Studio production app.


## v0.6.10 — Auto Straighten detection fix

Regression in v0.6.9: angular peak fraction threshold .205 and the mandatory 17% advantage over zero-degree meant realistic architecture and already-level scenes could be rejected universally. The new detector uses a lower, conservative fraction threshold .115 together with a separate 8% competitor-peak and multi-region support check. Already-level (~0°) is recognized as success rather than 'No confident level'; low-confidence clutter still refuses automatic correction. The algorithm and UI explicitly distinguish already level, detected correction and lacking consistent structural reference.

The user-provided bathroom screenshot includes a strong structural histogram peak around -3.5 degrees; the old .205 fraction fails it (~.17), while the new logic allows detection when line support is sufficiently distributed. Note: screenshot includes UI and was analysed on its cropped image region; real source photo may differ due to perspective.

Same v0.6.8 four-grid photographic Auto, manual angle slider, no AI models, full-resolution export, photo picker and all detail sliders preserved.

## v0.6.11 — Auto Straighten line-aware detector experiment

Fixes the v0.6.10 failure mode, where gradient-pixel orientation histograms rejected most photographs even with visible rooflines, walls or poles. The previous "peak share" and "stronger than 0°" tests could reject genuinely usable lines or naturally level scenes.

NEW: downsample once to longest edge at most 800 pixels, run Sobel edges, then vote for line intercepts within near-horizontal and near-vertical angular candidates. Different line segments get unique intercept bins; each candidate is scored by strength, spatial extent, angular peak prominence and supporting long straight segments. Horizontal and vertical evidence are scored separately, then combined only when they agree or one axis is decisively better supported. Strong perspective conflicts still abstain rather than invent a tilt. A visually negligible <0.8-degree rotation reports "already level" and leaves image uncropped.

A user-provided street screenshot with rooftops and power lines previously returned "no consistent line reference"; an offline implementation-equivalent Hough line diagnostic identified dominant horizontal structure near zero degrees. This is illustrative: Android processing of source photos still requires device testing; do not promise every photo can be straightened automatically, especially portraits and organic/no-line scenes.

All of v0.6.10's manual ±15° straighten, undo, photo swiping, crop-to-fill and full-resolution export remain. All v0.6.8 four-level photographic Auto, WB, noise/sharpen/detail formulas remain IDENTICAL. No AI, no ML Kit, no TFLite, no Restore Lab.

Starting checkpoint: experiment/dennis-craft-mobile-v0.6.10-straighten-fix commit 356d51e1e4ec4b52cebfad1815684b063b3159a8.


## v0.6.12 — Conservative Auto Straighten safety and perspective guard

Based on preserved v0.6.11 line-aware straightening (commit 32a03aa887b7e9700e672285fb06787193f76e76). No photographic DSP, colour, Auto WB, 4-grid Auto or detail changes. No Restore Lab / AI dependency.

User A/B screenshot demonstrates a confidently proposed -4.1° correction can tilt a complex-perspective outdoor scene incorrectly. Root failure: a scoreAxis peak divided by median (often near zero) can claim very high confidence based on one roof or converging lines, and v0.6.11 allowed one axis to override a contradictory other axis. Build instead:
- Reject if simultaneously strong horizontal and vertical line estimates disagree >1.6°, instead of letting one override the other.
- Analyze horizontal/vertical line evidence separately across left and right halves, and veto strong incompatible regional estimates.
- Require multiple spatially distinct line groups and actual peak prominence versus the second significant angle, not the almost-zero histogram median.
- Apply automatically only for high-consensus small corrections within ±1.5°. Large or uncertain but plausible rotations are proposed in a review dialog, NEVER silently applied. User can Keep Original or Apply Suggested Angle, then use manual slider and Undo.
- Continue recognising well-supported already-level scenes and avoiding needless zoom/crop.
- Preserve full-resolution export and black-corner-free fill, existing Crop ratio/Rotate 90°, manual slider and per-photo undo/session state.

This is deliberately a safety-first version: some photographs without trustworthy straight structural references will still require manual adjustment. A wrong automated 4° tilt is worse than abstaining.


## v0.6.13 Guided Level — Perspective-safe manual reference

Based on preserved working v0.6.12 (commit 66b08e2ddcab08af9d417302486c3758e5e6ffb4). No changes to the existing automatic line detector, photographic 4x4/8x8/16x16/64x64 Auto, WB, denoise, sharpening, detail, export quality, selection or swiping. No AI/Restore Lab models.

**Why:** User comparison shows bathroom screenshot vertical shower-frame edges lean by around 4–5 degrees while window frame edges on the other side remain almost upright. The auto detector's safe abstention is geometrically sound: one angle cannot eliminate camera perspective on all edges. Weakening thresholds would reintroduce aggressive wrong corrections.

New **Crop → GUIDED LEVEL** allows the user to choose a reference instead:
1. Tap GUIDED LEVEL.
2. Tap two fairly distant points along the *same* visible straight horizontal or vertical edge. A marker identifies the first point. The image must be displayed; touches in black letterbox margins are rejected.
3. App computes a relative angle in image-view coordinates (FIT_CENTER uniform scaling preserves line angle). A horizontal edge uses the inverse slope; a vertical edge uses the inverse deviation from true vertical.
4. Review dialog shows the additional and final angle, warns of perspective/cropping, and requires explicit consent before applying. Cancelling leaves the edit untouched.
5. Guided mode temporarily consumes taps, not left/right image swipes. Leaving Crop or moving to another photo cancels guide mode. A second GUIDED LEVEL tap cancels it.
6. Applies as one non-destructive straightenAngle value; old angles are not accumulated through repeated bitmap resampling. Existing undo/redo and full-res export processing are reused.

Current Auto Straighten remains unchanged and conservative, including review dialogs for uncertain large corrections. The 0.1 degree manual slider remains.

The user should test the bathroom by tapping two endpoints of one shower-frame or window-frame vertical (not one point on each). These choose different perspective references, so the best angle is partly a compositional preference. The feature is an experimental quality-of-life fallback until an optional full perspective transform is developed.


## v0.6.14 — Crop & Transform (experimental, non-AI)

**Source:** verified checkpoint/dennis-craft-v0.6.13-before-transform SHA 242b1bac8c6fa13216e44eb7f456f56d05d7e49b. New code isolated on experiment/dennis-craft-mobile-v0.6.14-crop-transform. Absolutely no AI restoration models, no 128/256 analysis experiment, and no changes to the original four-level 4×4/8×8/16×16/64×64 Auto, WB, detail algorithms or full-resolution JPEG export quality.

Crop bottom navigation now contains three sub-tabs: Crop / Straighten / Transform.
- **Crop:** Original, 1:1, 4:5, 16:9, Rotate 90°. Existing capabilities preserved.
- **Straighten:** Existing conservative Auto Straighten, two-tap Guided Level, ±15° manual slider and reset angle. Existing behaviour intentionally unchanged.
- **Transform:** independent Vertical Skew and Horizontal Skew sliders (each ±25.0), 3×3 alignment grid, RESET, **Balanced Auto** and **Full Auto** (always preview/confirm before applying). These are pure deterministic projective transforms, *not* neural Auto photo understanding.
- Auto suggestions use existing StraightenEngine and an additional downsampled Sobel edge-orientation analysis requiring converging slope evidence in opposite sides of the frame; otherwise abstain. Balanced uses up to 8 control units and attenuated intensity; Full uses up to 18. Photo with unresolvable perspective may still need manual control. A confirmation dialog displays all proposed values and prevents incorrect automatic destructive rotations.
- The new TransformEngine uses Android Matrix.setPolyToPoly (4-point projective mapping) and combines roll + vertical + horizontal skew into **one bitmap re-sampling pass**. The existing discrete Rotate-90 operation (if used) remains a prior separate operation. Coverage zoom is binary-searched so the output doesn't show black corners, sacrificing frame edges instead. A second aspect-ratio crop and existing unchanged DetailEngine processing follow geometry.
- All geometry settings are part of Edit.copy, undo/redo, photo-swiping session edits and applied to full-resolution export and quick/full previews. Looks preserve the geometry settings.
- A 3×3 grid overlay is visual only; never burned into exports. No new Android permissions or dependencies.
- This is a candidate to evaluate with bathroom verticals, street rooflines, the playground structure and natural landscapes. If Auto does not find reliable lines, use manual skew or Guided Level. One perspective correction cannot flatten 3D perspective differences across different objects without compromises.

Known performance: Projective bitmap transformation requires one additional filtered bitmap allocation when geometric transforms are nonzero; full-resolution images may be slower to save, especially extreme adjustments. Watch GPU/CPU memory and image quality on the Samsung Galaxy S23 Ultra before promoting v0.6.14.

## v0.6.15 — Stronger architectural-line detection experiment

- Starts from v0.6.14 Crop & Transform. Existing four-layer photographic Auto/WB, original crop, guided leveling, undo, manual Transform sliders, single-pass perspective rendering and full-resolution export unchanged. No AI/ML models bundled.
- Replaces Auto Transform's overly brittle pixel-edge left/right histogram and requirement that perspective slopes have opposite signs. This incorrectly produced “no reliable architecture” even on buildings, fences, roofs and shelter posts.
- New ArchitecturalLines detector downsamples once to 740px, calculates Sobel edge gradients, and accumulates orientation plus *line intercept* Hough votes. It rejects short fragmented edges, requires actual spatial line span and groups separate lines at distinct intercepts, scoring vertical, horizontal, left/right and top/bottom independently.
- Uses coherent line evidence to propose camera roll and/or projective corrections. Perspective convergence can be detected even when overall roll shifts both slope families in the same direction; region and line-strength checks still reduce false positives.
- A distinct status “Architectural lines found · no trustworthy adjustment” is now shown when obvious structures are present but they do not justify an actual correction. “No reliable structural reference” is reserved for scenes without adequate aligned line evidence.
- Both Full Auto and Balanced Auto are *proposals* requiring explicit user review. Balanced remains restrained, Full permits more transformation. No automatic geometric operation on photo opening or main Auto.
- Same on-device Java image algorithms, no network requests. Test first on previously reported scooter shelter, demolition building and bathroom scenes, comparing status + proposed angle/skew. Avoid relying on grids in screenshots as evidence: the engine analyses the raw loaded photo bitmap without overlay.
