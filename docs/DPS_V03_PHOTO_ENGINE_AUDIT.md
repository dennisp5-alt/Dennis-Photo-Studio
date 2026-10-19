# Dennis Photo Studio v0.3 — Auto Analysis Lab

## Scope and evidence
Built on v0.2 Smart Colour. This is an *experiment*, not the approved visual look. Side-by-side APK using its own application ID. Preserve all v0.1/v0.2 work and the current photo library.

## External research findings
- Adobe documentation identifies Auto Tone as scene-adaptive analysis of exposure, contrast, highlights, shadows, whites, blacks, saturation and vibrance. Adobe's model is proprietary; no assumption of equivalent ML accuracy.
  https://helpx.adobe.com/lightroom/mobile/adjust-light-and-color/apply-auto-settings.html
- White Balance is an underdetermined illuminant estimation problem. Gray-world estimates fail on dominant colours; scene evidence alone cannot guarantee correct colours. Use calibrated grey cards or known references to validate.
  https://doi.org/10.1002/col.22822
- Luminance noise, chroma noise and sharpening have different effects. Edge masking avoids sharpening noise; settings must be checked at 1:1.
  https://helpx.adobe.com/au/lightroom/web/edit-photos/apply-effects/adjust-details.html
- Dehaze can distort colours and it is impossible to reliably identify intentional fog/haze with simple image histograms. Leave auto Dehaze at 0 and retain manual slider.
  https://www.adobe.com/learn/lightroom-cc/web/apply-clarity-dehaze-sharpen-lightroom-cc

## Architecture audited
| Function | Existing code | Verification / risk |
|---|---|---|
| Global exposure/tonal Auto | crates/pipeline/src/auto.rs | v0.2 Smart caps highlights, v0.3 unchanged as a conservative baseline; test with real photos. |
| Auto White Balance | auto.rs and engine/src/cmd/develop.rs | Confidence-gated neutral sampling; As-Shot fallback for dominant-colour scenes; classic for selected grey-card picker. This is not measured absolute colour accuracy. |
| Manual White Balance | engine develop.wb | Independent camera WB presets/temp/tint and picker, not replaced. |
| Luminance/colour NR, sharpening | crates/develop/src/controls.rs; detail pipeline | v0.3 source-proxy auto estimates bounded settings, not AI denoising. Advanced/manual sliders retained. |
| Texture/clarity/dehaze | effects pipeline and controls | Conservative Auto Effects; dehaze=0 until photo-specific evidence exists. Manual remains available. |
| Tone curves, HSL/mix/grading | existing colour and tone pipeline | Code preserved. Rust pipeline regression tests requested; subjective equivalence to Lightroom not proven. |
| Crop/rotate/perspective | existing geometry pipeline | Existing regression tests, on-device crop/export to verify. |
| Optics/CA/lens | optics.rs and controls | Existing implementation, may depend on supported lens metadata; test 3×/10× sources. |
| Masks/remove | masks.rs/spots.rs and Compose tool | Manual tools preserved. AI subject/sky/background not packaged and not claimed. |
| Presets/XMP | crates/develop/src/presets.rs | Existing implementation preserved; Adobe engine matching not guaranteed. |
| Preview/export/ICC | colour and output pipeline | Requires independent ICC/EXIF and 1:1 image comparison. |
| JPEG/HEIC/RAW | source decoders | JPEG tested previously; HEIC support depends on decoder, not assumed. |

## Automatic detail control algorithm
Samples source thumbnail in scene-linear Rec.2020, rejects clipped and large-gradient pixels, estimates local high-frequency residuals in flat regions and refuses corrections on low confidence. Colour noise is estimated separately from red-green and blue-green residuals. No false model claims. Outputs luminance/color NR and edge-masked sharpening via existing non-destructive settings. Max NR 28/34; sharpen <=12 and only on lower-noise images. This is a *proxy heuristic*: cannot recover missing real detail or prove that high frequencies are sensor noise. Auto Effects leaves dehaze at zero and caps texture/clarity to 5/3. All original sliders remain under Advanced.

## Acceptance checklist
- Check same originals under v0.2 and v0.3, with identical WB and other edits. Compare Smart Auto/Classic at same strength; check against references.
- Auto White Balance: daylight grey card, mixed indoor light, backlit portrait, pink rose, night fire, natural fog, grey concrete. Verify saturation/highlights and confidence fallback.
- Auto Detail: compare 100% crops of ISO-high night sky, hair, fabric, flower filament, grass. Do not approve if denoising produces plastic texture, color bleed or halos.
- Auto Effects: clarity remains near zero on portraits; no false dehaze; Advanced sliders still work.
- Check repeat Auto tap / undo / redo / import / XMP / crop / mask / histogram / export / ICC / EXIF on phone.
- CI Rust test suite and full Android ARM64 build must pass. Compilation only proves integration, not photographic correctness.

**No automatic photographic algorithm can be shown '100% correct' without a calibrated test suite and known scene ground truth.**
