# MIRRAN — Intelligent Photo Studio (v0.1.6 experimental)

Native Java Android project, fully separate from approved Dennis Craft Mobile, LumaWorks, and other production editors. Main branch and all approved branches are left unchanged.

## v0.1.6 — offline photographic engine (experimental)

- First working **real semantic foreground masking** using Google's ML Kit Subject Segmentation API (beta). Model is free but must download once via Google Play services before mask inference works offline. No fake subjects or masks when unavailable.
- Presence adds edge-aware local Denoise and cautious physically-inspired Dehaze. Both run on preview and full-sized exports.
- Masks tab adds independent Subject and Background exposure, only enabled when an actual on-device segmentation mask is ready; masks are cached for up to four photos.
- Explicit sRGB decoding for ICC-tagged JPEG/HEIC/PNG. This is a *colour-consistent sRGB entry point*, **not yet a 16-bit linear-light pipeline** or full ICC-preserving export system.
- Automatic local noise assessment may apply modest Denoise; cautious Dehaze applies only on low-contrast candidate scenes.
- Processing order is consistent for preview and export: edge-aware denoise, established tone, conservative dehaze, masked exposure; original photo untouched.
- No OpenAI API key, account or paid provider is needed. Old Android picker, album swipe, saved in-session adjustments and JPEG quality 100 are preserved.
- Performance and edge cases need real-device tests, particularly 50 MP exports and segmentation model downloads.

## v0.1.5 scene-quality audit

Audited user-provided daytime portrait, duck/water, two outdoor family scenes, and low-light flower. Private reference images are **not** committed to the repository.

- Replaced the flat mean-only decision model with mean + global percentiles, central brightness and central upper quartile, per-channel clipping, blue-area share, neutral-pixel fraction, and a cautious high-frequency noise proxy. Sampling remains fast and entirely offline.
- Dark-background / bright-subject detection protects a well-lit flower from inappropriate global exposure/black lift. Existing low-key/night/fire safety is preserved.
- Neutral-area-gated warmth eliminates global R-minus-B false positives from blue skies or water. It does **not** claim semantic or colour-calibrated white balance.
- High blue share / channel clipping restrict extra vibrance; highlight roll-off remains conservative.
- Avoids amplifying existing JPEG grain: noisy samples turn off Auto Clarity; pixel-level Clarity now includes deep-shadow/highlight guards. No claim of denoising.
- Reduced overly broad default contrast and vibrance on already punchy JPEGs, without removing manual controls.
- Synthetic regression tests cover 11 scenarios including bright-subject background, saturated sky, clipped channels, fine noise, shadows and midtones.
- Intentionally **unchanged**: album swipe, native photo picker, private AI endpoint, original-comparison gestures, JPEG encoder quality 100 and original-dimension export.

Limits: These improvements use statistics and heuristics, **not actual subject masks or neural visual understanding**. No full-resolution image was device-rendered or colour-managed by these tests. Already clipped channels and baked JPEG sharpening are not recoverable. Additional on-device A/B testing is required.

## v0.1.4 changes

- **Albums** button opens a built-in gallery, grouped by MediaStore album buckets. Tap an album, tap a photo, and swipe horizontally through neighbouring photos without reopening the picker or exporting.
- Gallery access requires explicit Android media permission. Android 14+ limited-photo grants may expose only the selected images; full album navigation requires **Allow all photos**. The Android system Photo Picker remains for single images and does not grant access to adjacent album images.
- Touch handling distinguishes horizontal swipes from a stationary press-and-hold original comparison.
- Stores separate per-photo edit recipes in memory while the app session is alive; returning to another photo restores the edit. Navigation does **not** auto-export files.
- Full-resolution JPEG export uses Android encoder **quality 100** (not mathematically lossless; metadata/ICC restoration and huge images remain limitations).
- Keeps all v0.1.3 Auto and shadow protections intact.

## v0.1.3 changes

- Replaced the older file-based primary picker with Android's native Photo Picker on Android 13+, showing Photos and Albums. A system document picker fallback remains for unsupported devices.
- No storage/gallery permission is requested: the picker grants read access only to the selected item. Cancel keeps the current photograph.
- Increased the **effective pixel-level Auto contrast** substantially rather than only inflating displayed slider numbers.
- Midtone-focused S-curve protects blacks, near-white highlights and bright skin; conservative night/fire handling unchanged.
- Slightly stronger adaptive vibrance with additional near-clipping protection on saturated highlights.
- Photo-tested analysis based on the user-supplied portrait capture and red/orange flowers; on-device visual confirmation is still required.
- Expanded the deterministic regression suite to seven checks of shadow protection, midtone separation, vivid colour, and upper-highlight headroom.
- Preserved the separate server-backed AI architecture and existing full-resolution JPEG export.

## v0.1.2 changes

- Slightly stronger midtone contrast (approximately +5 to +18 local Auto) without another shadow lift.
- Adaptive vibrance: muted scenes receive more, highly saturated images receive less.
- Low-key night / fire protection: no automatic exposure boost, shadow lift, or artificial colour boost when the image is intentionally near black.
- JVM regression tests for natural midtone punch, fire protection, saturation limits and true-black output.
- Existing v0.1.1 new-photo control and shadow safeguards preserved.
- Only local Auto's recipe changed. No AI server settings, image picker, manual control scale, or other existing app touched.

## v0.1.1 changes

- Added a permanently visible **+ NEW PHOTO** button to the assessment bar: select a different image without exporting the previous photo. Canceling the Android picker keeps the current image and adjustments.
- Local Auto now suggests less aggressive shadows (approximately +3 to +16 maximum), rather than up to +40.
- Shadow rendering now protects deep blacks using a smooth tonal gate, reducing flat/grey shadow appearance.
- Resets queued previews when a new photo is selected, preventing a stale render from displaying on the next photograph.

## Current working scope

- Distinct MIRRAN bright editorial user interface, cobalt/navy/ivory palette, original icon.
- Android SAF image picker (JPEG / HEIC / PNG supported when decodable by Android).
- Photo-specific **local tonal assessment** (statistics and deterministic heuristics; **not** a visual-language AI).
- User-editable Exposure, Highlights, Shadows, Contrast, Vibrance, Warmth, Clarity.
- Non-destructive recipe, press-and-hold before/after, render from original, no source overwrite.
- Export edited JPEG using original decoded dimensions to Pictures/MIRRAN, memory permitting; 60 MP safety guard.
- Optional **online visual assessment** using a private HTTPS proxy and an OpenAI vision model. No OpenAI key is placed in the Android app.

## Honest boundaries

v0.1.0 is a prototype for the **AI decides, native engine renders** architecture. The Android renderer is a basic global editing engine; it has **no semantic masking, RAW development, panorama pipeline, sophisticated dehaze, local spatial edits, or true machine vision while offline**. It is **not** capable of identical results to ChatGPT's image-editing generator. The local assessor should not be represented as an AI vision model. On-device quality requires actual-photo validation; a compiled APK by itself is not a quality certification.

The private AI service is **not hosted** in this repository, so out of the box MIRRAN operates in local mode. Online AI analysis requires deployment and user configuration; an API subscription incurs usage charges independently from ChatGPT.

## Android build

Use Android SDK 35, JDK 17 and Gradle 8.11.1:
```sh
cd mirran
gradle :app:assembleDebug
```
Output: `mirran/app/build/outputs/apk/debug/app-debug.apk`.
GitHub Actions workflow `.github/workflows/mirran-android.yml` builds the branch-specific APK.

## Private OpenAI bridge

Deploy `mirran/server/index.mjs` to a private Node.js 20+ service **behind HTTPS**. In the server environment, set:

- `OPENAI_API_KEY` — your OpenAI API credential (server only)
- `MIRRAN_ACCESS_TOKEN` — independent random 32+ character token authenticating your Android app
- `OPENAI_MODEL` — optional override for an eligible vision/structured-output model (default `gpt-5.4-mini`)
- `PORT` — optional (default 8080)

Start: `node server/index.mjs` from `mirran` directory.

Use a properly authenticated HTTPS reverse proxy; never publicly expose the bare HTTP server or your secret. Server should be rate-limited and access-logged **without** logging photos, passwords, tokens, or API keys. The server receives only a downscaled JPEG preview, while full-resolution rendering remains on the phone. However, transmitting a preview may expose personal content; obtain user consent and choose trusted hosting.

In MIRRAN Settings, enter the HTTPS base URL (no /analyze suffix) and the MIRRAN access token. When you tap Refine, the app sends the reduced preview to your server which calls OpenAI. If the server is offline or fails, a labeled local tone assessment is used.

## Development safety and known limitations

- Full-res bitmap memory use can be high, especially above 12 MP. The app explicitly rejects images over 60 MP on export rather than quietly downsampling, but OOM may still occur on constrained devices.
- Preview colour and exported photo depend on Android decoding, JPEG encoding, and screen calibration; ICC and metadata retention are not engineered yet. Exports are JPEG, without original EXIF metadata.
- AI results are recommendations only; user may refine with sliders. The preview is not a guarantee of printer colour or perfect tone mapping.
- Security next steps before public use: hardened user authentication, quota/rate limits, certificate pinning if appropriate, cryptographically protected client token storage and privacy disclosures.
- The editing pipeline must be validated against diverse real photographs (landscape, portrait, night, hazy, difficult white balance) before calling it professional quality.
