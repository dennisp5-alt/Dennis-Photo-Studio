<p align="center">
  <img src="assets/app-icon/lightcraft-1024.png" alt="LightCraft's app icon" width="128">
</p>

<h1 align="center">LightCraft for Android</h1>

<p align="center">
  <b>LightCraft-android is an open-source reimplementation of the Adobe Lightroom Android app, created using the same Rust engine, <a href="https://github.com/storytold/lightcraft">LightCraft</a>, as the desktop app, and built with a Kotlin/Jetpack Compose UI framework.</b><br>
  Many credits to the original maintainers, <a href="https://github.com/storytold">storytold</a>.
</p>

<p align="center">
  <a href="https://github.com/artcraft-android/lightcraft-android/releases"><img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" height="60"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/artcraft-android/lightcraft-android"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="60"></a>
  <br><sub>Each release has two APKs: the plain one for most phones, the <code>-armeabi-v7a</code> one for 32-bit ARM phones.</sub>
</p>

<p align="center">
  <img alt="Rust engine" src="https://img.shields.io/badge/engine-Rust-lightblue?style=flat&logo=rust&logoColor=white">
  <img alt="Kotlin and Jetpack Compose" src="https://img.shields.io/badge/UI-Kotlin%20%C2%B7%20Jetpack%20Compose-blue?style=flat">
  <img alt="Minimum: Android 10 (API 29)" src="https://img.shields.io/badge/min-Android%2010%20%28API%2029%29-blue?style=flat">
  <img alt="Target: Android 16 (API 36)" src="https://img.shields.io/badge/target-Android%2016%20%28API%2036%29-blue?style=flat">
  <img alt="License: MIT OR Apache-2.0" src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue?style=flat">
  <img alt="Status: beta" src="https://img.shields.io/badge/status-beta-lightblue?style=flat">
</p>

<br>

<p align="center">
  <img src="docs/images/hero-tetons.jpg" alt="LightCraft for Android held sideways: Ansel Adams' The Tetons and the Snake River on the left, the Light panel with Exposure, Contrast, Highlights, Shadows, Whites and Blacks on the right, then the Edit sections and the tools in columns at the edge" width="100%">
  <br>
  <sub><i>Ansel Adams, "The Tetons and the Snake River" (1942). Public domain, U.S. National Archives. Developed in LightCraft for Android.</i></sub>
</p>

<br>

<p align="center">
  <img src="docs/images/curve-tetons.jpg" alt="The point curve drawn over The Tetons and the Snake River with the phone held sideways: a gentle S with the black point lifted and the white point lowered, the curve on its grid in the panel at right, under its channels and Done" width="100%">
  <br>
  <sub>A gentle S on the RGB point curve, drawn right over the photo. <i>Ansel Adams, 1942 (public domain).</i></sub>
</p>

<br>

## Color grading

<p align="center">
  <img src="docs/images/grading-migrant-mother.jpg" alt="Migrant Mother toned warm like a print, with the Grading panel at right: Shadows, Midtones, Highlights and Global beside the highlights colour wheel, Hue 42 and Saturation 16 under it" width="100%">
  <br>
  <sub>The Highlights wheel in the Color panel's Grading. <i>Dorothea Lange, "Migrant Mother" (1936). Public domain, Library of Congress.</i></sub>
</p>

<br>

## Before & after

<table>
<tr>
<td width="50%"><img src="docs/images/ba-tetons.jpg" alt="Before and after of The Tetons and the Snake River, side by side: the after has deeper clouds and more open shadows along the river"><br><sub><b>The Tetons and the Snake River.</b> Highlights −45, Shadows +38, Clarity +28, Dehaze +18. <i>Ansel Adams, 1942 (public domain).</i></sub></td>
<td width="50%"><img src="docs/images/ba-migrant-mother.jpg" alt="Before and after of Migrant Mother, side by side: the after is warmer, with lifted shadows"><br><sub><b>Migrant Mother.</b> Shadows +42, Texture +18, split-toned grade, vignette. <i>Dorothea Lange, 1936 (public domain).</i></sub></td>
</tr>
<tr>
<td width="50%"><img src="docs/images/ba-earthrise.jpg" alt="Before and after of Earthrise, side by side: the Earth over the lunar horizon, the after slightly warmer and richer"><br><sub><b>Earthrise.</b> Dehaze +22, Highlights −30, warmer white balance, Vibrance +22. <i>NASA / Bill Anders, Apollo 8, 1968 (public domain).</i></sub></td>
<td width="50%"><img src="docs/images/ba-blue-marble.jpg" alt="Before and after of The Blue Marble, side by side: the after has deeper blacks and firmer cloud detail"><br><sub><b>The Blue Marble.</b> Highlights −38, Blacks −20, Dehaze +15, Vibrance +30. <i>NASA, Apollo 17, 1972 (public domain).</i></sub></td>
</tr>
</table>

<br>

## Masking

<p align="center">
  <img src="docs/images/masking.jpg" alt="A radial gradient mask drawn as a red overlay around the sun over a lake at first light, with its handles, the list of masks open beside it (Sky and Sun glow), and the mask's Color settings at right (Temp +40, Saturation +15) above its colour swatch" width="100%">
  <br>
  <sub>A radial sun-glow mask (Temp +40, Exposure +0.5) layered over a linear sky mask (Exposure −0.6, Dehaze +20). <i>Photo from LightCraft's procedurally generated demo library.</i></sub>
</p>

<br>

## Presets, profiles & the Color Mixer

<table>
<tr>
<td width="50%"><img src="docs/images/presets.jpg" alt="The Presets panel beside a lavender field: Golden Hour chosen at an amount of 100, among Crisp Landscape, Blue Hour Boost, Crisp Vista, Deep Sky and Lush Greens in the Landscape group"><br><sub>The Presets panel: Golden Hour at 100 %, each preset previewed on the photo. <i>Demo library.</i></sub></td>
<td width="50%"><img src="docs/images/mixer.jpg" alt="The 8-band Mix in the Color panel, open on purple, with its Hue, Saturation and Luminance sliders beside the lavender field"><br><sub>The 8-band Mix, open on purple. <i>Demo library.</i></sub></td>
</tr>
</table>

<table>
<tr>
<td width="50%"><img src="docs/images/crop-tetons.jpg" alt="Crop on The Tetons and the Snake River: a 16 by 9 crop straightened by 2.48 degrees on its dial, the aspect's name over it, and the Aspect tab at right: Original, Ratios, Instagram and TikTok"><br><sub>A 16:9 crop straightened +2.5°. <i>Ansel Adams, 1942 (public domain).</i></sub></td>
<td width="50%"><img src="docs/images/bw-split.jpg" alt="Sand dunes in colour on the left and in high-contrast black and white with vignette and grain on the right"><br><sub>The colour original on the left; the High Contrast B&W preset with Vignette and Grain on the right. <i>Demo library.</i></sub></td>
</tr>
</table>

<br>

## Organization

<table>
<tr>
<td width="50%"><img src="docs/images/grid-demo.jpg" alt="The library held sideways: the justified grid of all 28 photos (the demo library and the four showcase photos), clean cells, beside the pane of views and albums"><br><sub>The library sideways: the grid beside the views and albums. <i>Demo library.</i></sub></td>
<td width="50%"><img src="docs/images/grid-pd.jpg" alt="The Showcase album in the square grid: the four public-domain showcase photos beside the albums pane"><br><sub>Square grid of the four public-domain showcase photos.</sub></td>
</tr>
<tr>
<td colspan="2"><img src="docs/images/info-earthrise.jpg" alt="Info and rating for Earthrise: the photo over a sheet with five stars, Pick chosen, then Info and Metadata tabs with the file name, 5.8 megapixels, 2400 by 2400 and JPEG"><br><sub>More › Info and rating: the rating, the flags and the photo's file, size and format. <i>NASA / Bill Anders, "Earthrise", Apollo 8, 1968 (public domain).</i></sub></td>
</tr>
</table>

<br>

## How it's organized

<table>
<tr>
<td width="50%"><img src="docs/images/organized-tetons.jpg" alt="The editor held sideways with no tool open: The Tetons and the Snake River, and the six tools labelled in a column at the right edge: Actions, Presets, Crop, Edit, Masking and Remove"><br><sub>The editor: the photo, and its six tools in a column at the edge. <i>Ansel Adams, 1942 (public domain).</i></sub></td>
<td width="50%"><img src="docs/images/organized-menu.jpg" alt="The library's side menu open over the grid: LightCraft's lynx icon, Preferences, Local storage, About LightCraft and Help"><br><sub>The library's menu: Preferences, Local storage, About and Help.</sub></td>
</tr>
<tr>
<td colspan="2"><img src="docs/images/organized-export.jpg" alt="The Export as page: Settings Large JPG, file type JPG, dimensions Full size (2400 by 1922), quality 90%, and switches to include a watermark and a border"><br><sub>Export as, from the editor's download button (or Share › Export as…): the settings, file type, size, quality, watermark and border.</sub></td>
</tr>
</table>

<br>

## Architecture

<p align="center">
  <img src="docs/images/architecture.png" alt="Architecture diagram: the photographer uses the Android app (App navigation, which shows the Photo editor and the Photo library and uses the App state, which calls the Session bridge); the editor adjusts Develop controls, selects Presets and profiles and configures Masking, offers Denoise and HDR and panorama, and exports; the Session bridge opens the Mobile Rust API, which uses Photo sources and the Catalog and search the library browses; Photo sources read Photo metadata, render with the Develop pipeline (which produces the Export) and decode with the Image codecs" width="700">
  <br>
  <sub>How the app and the engine fit together. Generated with <a href="https://gitdiagram.com/artcraft-android/lightcraft-android">GitDiagram</a>.</sub>
</p>

<br>

## Planned features

LightCraft for Android is in beta.

| Area | Status |
|---|---|
| Library: views, albums (create, rename, delete, cover), filter, sort, search, select mode, justified and square grids, date segments, slideshow | ✅ |
| Import: photo picker, files (raws), camera, Share from other apps; the Gallery tab (the phone's photos) | ✅ |
| Light, Color (white balance, eyedropper), Mix, Grading, Effects, Detail, Optics, Tone Curve (+ targeted) | ✅ |
| Profiles and presets (with amount), copy and paste settings | ✅ |
| Crop (ratios, Instagram and TikTok formats), straighten, Upright, turns and flips, manual transforms | ✅ |
| Masking: brush, linear, radial, colour and luminance range (a point or an area), add and subtract, each part's menu, a colour tint | ✅ · AI masks (subject, sky) ⬜ |
| Remove, heal and clone spots | ✅ |
| Save or share: JPG, TIF, AVIF (DNG for raws) or the original; small, full or custom size; quality, naming, sharpening, colour space, metadata, watermark and border | ✅ |
| Upright and sideways layouts | ✅ |
| Lens blur | ⬜ (the engine doesn't render it yet) |
| Community, cloud sync | 🚫 LightCraft is a local app |

<sub>✅ works today · 🚧 in progress · ⬜ not started · 🚫 not planned</sub>

<br>

## Building Instructions
*One Gradle command builds the whole app: it compiles the Rust engine with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) and generates the Kotlin bindings.*

* Clone this repository
* Make sure you have at least 3 GB of free disk space
* enter `apps/lightcraft-android/android` and run `./gradlew assembleRelease`, or open that folder in Android Studio and press Run.

Build time dependencies:

<details>
  <summary>required tools</summary>

  ```
      rustup (it installs the Rust release and Android targets rust-toolchain.toml names)
      cargo install cargo-ndk --version 4.1.2 --locked
      Android SDK (API 37) and NDK 29.0.14206865
      JDK 17 or newer
  ```
</details>

*Builds are only tested on Windows 11.*

<br>

## License

[MIT](LICENSE-MIT) OR [Apache-2.0](LICENSE-APACHE), at your option. LightCraft for Android is not affiliated with Adobe;
Lightroom is a trademark of Adobe Inc.

<br>

## Misc info for myself

Try to use [semantic commit](https://kapeli.com/cheat_sheets/Semantic_Commits.docset/Contents/Resources/Documents/index)
messages. Use `feat:` / `fix:` / `chore:` / `docs:` / etc.
