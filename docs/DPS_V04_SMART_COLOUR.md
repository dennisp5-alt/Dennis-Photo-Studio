# Dennis Photo Studio v0.4 — One-tap Smart Auto and white balance

Branch: studio-v0.4-one-tap-smart. Derived from tested v0.3.1, with independent
application ID, no in-place replacement of user data.

## User feedback incorporated
- Auto was too aggressive on fire, regardless of whether Classic or Smart.
- WB Auto sometimes did nothing. It must adjust neutral surfaces if evidence
  supports a correction, not force a global cast on flowers or coloured lights.
- Remove the Classic selector and Amount slider. Smart Auto should work in one tap.
- Preserve v0.3.1 native-resolution Auto Detail for luminance/color noise and sharpening.

## Root cause and engineering decisions
The v0.2/v0.3.1 Smart tone first obtained Classic's adjustments. Classic calculated
aggressive highlight compression using Classic's own exposure value, then Smart
capped exposure but retained the already large -highlights, often darkening
small self-luminous subjects. v0.4 recalculates highlights/whites from the
**final** exposure, and skips all global tone/colour changes in predominantly
black, high-contrast, compact self-luminous scenes. This is an intentional
risk-averse photographic decision, not semantic AI fire detection.

WB confidence assessment accepts mild illuminant chroma on grey-looking
surfaces, checks multiple independent image regions, and caps temperature
in reciprocal kelvin (mireds) and tint changes. If a scene lacks reliable
neutral evidence (sunset, foliage, flower close-up), preserve as-shot WB.
Manual WB and the user-selected neutral eyedropper still operate normally.
The command remains separate from tone Smart Auto and users can select
WB Auto in Color. Both adjustments are non-destructive.

## Unit/regression tests
- Simulated bright flames on dark background must have zero exposure/
  highlight/white correction, preserving luminous appearance.
- Balanced mid-grey must avoid excessive exposure changes.
- Slightly warm neutral surface must allow bounded positive-confidence WB.
- Bright red/green floral subject must retain camera white balance.
- v0.3.1 Auto Detail noise test suite runs without altered code.
- Full pipeline/develop Rust regression suite and ARM64 Android build required.

## Real-phone acceptance
Use EXACT original files on v0.3.1 and v0.4:
- Bright orange fire against black sky: Auto must not dull yellow-white flames.
- Pink rose and bright bottlebrush: petals, reds, stamens, greens stay natural.
- Grey/white card photographed under daylight and indoor illumination:
  Auto WB should make grounded, small correction if reliably measured.
- Mixed warm/cool lighting: camera fallback may be appropriate.
- Low-light portrait: shadow skin colour, highlight clip and black level.
- A noise-rich JPEG: Auto Detail v0.3.1 behaviour and displayed values
  should be identical, inspect at 100%.
- Undo/Redo, two quick Auto taps, saved full-size JPEG, profile and EXIF.

No algorithm can guarantee 100% physically accurate colour from an arbitrary
JPEG without a calibrated target/camera reference. A no-op can be correct.
The test APK is for quality assessment, not a promoted production build.
