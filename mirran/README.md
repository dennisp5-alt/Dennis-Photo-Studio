# MIRRAN — Intelligent Photo Studio (v0.1.4 experimental)

Native Java Android project, fully separate from approved Dennis Craft Mobile, LumaWorks, and other production editors. Main branch and all approved branches are left unchanged.

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
