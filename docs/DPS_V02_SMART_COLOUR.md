# Dennis Photo Studio v0.2 — Smart Colour Engineering Trial

Source branch: `studio-v0.2-smart-colour` (from approved compiling editable source v0.1).

## Precise goals
- Fix Auto WB tendency to produce very large temperature/tint shifts on flowers or colour-dominated scenes. Detect candidate neutral pixels and demand enough independent support across the frame. On insufficient evidence, preserve source `As Shot` WB. Cap automatic changes in mireds/tint while preserving the original manual White Balance controls, preset modes and eyedropper. Calibration eyedropper continues to use the original neutral-sample solution.
- Add **Smart Auto** with protection for high highlights amid a dark background, no unconditional +12 vibrance or +3 saturation, and adaptive restraint on contrast and shadows. **Classic Auto** remains selectable in the existing Auto Amount dialog for comparison. Keep both non-destructive (parametric edits, not rendered pixel blends).
- App icon redesigned as a dark camera lens with mountain, sunrise and teal rim inspired by the Dennis Photo Studio design, as a native adaptive Android vector drawable. The existing Lightroom/LightCraft icon resource is no longer copied during building.
- No claims of perfect objective colour accuracy: JPEG camera rendering is a scene-dependent, imperfect reference. Exact colour requires calibration cards/profile-aware reference measurements. Neither Smart Auto nor Auto WB can truthfully guarantee '100% correct' for all images.
- No semantic AI masking model included yet. Keep functional manual Brush, Radial, Linear, Colour Range and Luminance Range masks; Subject/Sky/Background remain a separately measurable model-integration step, not fake UI options.
- Full offline LightCraft Rust editor, white-balance matrix, curves, HSL, DevelopSettings, library, presets and export are otherwise unchanged.
- Version number 0.2.0-beta; independent app ID com.dennis.photostudio (upgrades v0.1 if signed compatibly; Github Actions debug signatures are runner-dependent, reinstall may be required).

## Acceptance
- Compare SAME source rose, portrait, sunset, twilight fire, white interior neutral target, green foliage, blue ocean and gray calibration target across v0.1 and v0.2.
- For rose with near-white petals against dark background, Smart Auto should limit exposure and avoid forcing saturation; Classic should reproduce old LightCraft Auto at 100%.
- Neutral/white-card scene Auto WB should give near-neutral colour. Pink and orange flowers, foliage, sunsets and stage lighting should not be forced neutral.
- With dubious neutrals, auto WB should display source/original WB rather than arbitrary huge shifts. Manual WB and picker must still work.
- Real histogram vs clipping, RGB/gray patches and export fidelity (JPEG, XMP presets, EXIF) should be checked with an external colour-managed tool.
- Test package icon on S23 Ultra launcher's light/dark background.
- The build validates source/compilation and tests. It cannot validate physical chromatic accuracy without photographed known references.

## Rollback
First v0.1 release: `dennis-photo-studio-v0.1-experiment`; editable source `studio-source-v0.1`. Never force-push those branches.
