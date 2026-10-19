# Dennis Photo Studio v0.5 — Lightroom reference audit and changes

## What we learned from the uploaded Lightroom package
User-provided Adobe Lightroom Mobile 11.6.11 APKM contains Android APK splits, proprietary native rendering and 531 XMP resources. XML samples such as `Color - Natural.xmp`, `Color - Bright.xmp`, and `Color - High Contrast & Detail.xmp` are static recipes with Contrast2012, Highlights2012, Shadows2012, Whites2012, Blacks2012, Clarity2012 and Vibrance fields. They do NOT reveal Lightroom's ML Auto algorithm. We did not copy Lightroom native code, bundled profiles or its preset assets.

Adobe documentation: tone Auto edits exposure/contrast/highlights/shadows/whites/blacks/vibrance/saturation; WB Auto is separate, neutral-based As Shot/Auto/Custom plus eyedropper.
- https://helpx.adobe.com/lightroom/mobile/adjust-light-and-color/apply-auto-settings.html
- https://helpx.adobe.com/lightroom/mobile/adjust-light-and-color/adjust-colors.html
- https://www.adobe.com/au/creativecloud/photography/discover/white-balance.html

## Cause of observed v0.4 fire no-op
For bright flames with a dark background, v0.4's intentional-light mode returned ALL ZERO adjustments. The result preserved flames but made no useful enhancement. New v0.5 independently chooses a modest shadow lift only when there is visible recoverable content in low-midtone firelit wood (scene-linear Y 0.035–0.28); otherwise it does nothing rather than brighten empty black sky.

### v0.5 Smart Auto
- Bright, small self-luminous subjects: Exposure=0; Highlights=0; Whites=0; Saturation=0; Vibrance=0; Contrast=0.
- If recoverable wood/scene surfaces are present, Shadows becomes +9..+22; Blacks -4 to preserve the nighttime black point. No destructive pixel blending.
- If no recoverable content (e.g., stars on black), no forced lifting or noise.
- Other scenes continue using v0.4 adaptive tone, not a claimed Adobe-equivalent ML model.

### Auto White Balance
- The same source-confidence neutral candidate analysis, bounded mired/tint correction and original camera As-Shot fallback remain.
- Source sampling uses a 2560px preview instead of the former 512px thumbnail. Natural flame/sunset warmth should be protected when no trustworthy neutral reference exists. Such preservation is a valid colour decision, not a requirement to change pixels.
- A clear Color-panel explanation plus an Auto WB toast explains why neutralization may not occur. The manual white balance eyedropper still allows a calibrated target-based correction.

### Preserved source
v0.3.1 native-resolution Auto Detail, luminance and colour NR, sharpening, Photo Studio icon, XMP, crop, masks, optics and full-resolution JPEG export are unchanged. App package ID com.dennis.photostudio.lightref.v05 is independent.

## Acceptance
Repeat identical original JPEG in v0.4 vs v0.5: fire, rose, grey card, dark unlit sky, neutral room, portrait. Fire's luminous whites/oranges must be protected and wood lifted subtly. WB in fire may correctly be preserved, neutral grey should respond within limits. Check v0.3.1 Noise/Detail values and exports unchanged, undo/redo and sequential Auto taps. Build/tests alone are not a visual approval; colour truth needs reference targets.
