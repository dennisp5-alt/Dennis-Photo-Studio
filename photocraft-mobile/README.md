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
