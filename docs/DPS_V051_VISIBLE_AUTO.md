# Dennis Photo Studio v0.5.1 — Why Auto looked ineffective

User evidence: photograph of a nighttime wood fire, v0.5 Smart Auto returned
Shadows +11 / Blacks -4 but made practically no visible change.
Auto WB Temp/Tint displayed 0 in a rendered JPEG.

## Root cause verified by source inspection
- The existing `finish.rs` global Shadow adjustment applies an EV shift
  with weight mainly in the deepest log-luminance bands. The object that
  needs more visibility is **flame-lit wood, above black but below midgrey**.
- v0.5 Smart Auto fire heuristic intentionally returned Exposure=0,
  Highlights=0 and Whites=0. These guardrails should remain; bright
  yellow-white flames cannot safely be globally amplified or compressed.
- JPEG WB Temp is displayed as a relative offset (0 = as-shot). Without
  trustworthy neutral material, a zero Auto WB is a defensible result.
  It should never be presented as proof the WB pipeline didn't run.

## Correction
- For fire/dark-background scene with measurable recoverable low midtones,
  set global Shadows ~26-48, and parametric curve Shadows +30,
  Darks +42; preserve global Exposure/Highlights/Whites = 0.
- Renderer parametric curve protects encoded high luminance (above ~0.75)
  by construction, so flames remain bright while visible fire-lit wood lifts.
- Return WB diagnostic JSON showing evidence/confidence/retention status.
  The neutral eyedropper and manually tunable Temp/Tint remain untouched.
- Lightroom APKM resource inspection isn't access to its proprietary Auto.
  No Adobe code, LUTs or proprietary resources included.

## Regression tests
- Synthetic emissive fire + wood: parametric curve lifts encoded 0.20
  by at least 0.025 and 0.38 by 0.06; retains 0.91 highlight and black.
- Empty night scene: zero added curve.
- Normal scene: no fire-region curve.
- Existing WB warm neutral / red-green flowers test cases.
- v0.3.1 Auto Detail suite left intact.

## Real-photo acceptance
Original JPEG + Lightroom Mobile Auto result needed for measurable
side-by-side. The screenshots alone cannot quantify actual pixel-level
improvement or Lightroom's preferred exposure. Inspect on S23 Ultra.
