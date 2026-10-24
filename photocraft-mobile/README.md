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
