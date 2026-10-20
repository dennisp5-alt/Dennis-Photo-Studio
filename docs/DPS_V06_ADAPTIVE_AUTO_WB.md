# Dennis Photo Studio v0.6 – Adaptive Smart Auto and White Balance
Checkpoint: experimental branch studio-v0.6-adaptive-auto-wb.
Baseline: v0.5.1's proven Auto Detail and manual editor untouched.

## Findings from the actual Kodak FZ55 photographs
Analysed locally using real JPEGs in user-provided Bulk.zip / Bulk2.zip;
the uploaded bytes and exact filenames were verified. These photographs
remain private to the conversation and are NOT checked into GitHub.

* 100_0788~2.jpg: multiple pink daisies on leaves.
* 100_0791.jpg: outdoor portrait of a young woman with glasses beside a house.
* 100_0806.jpg: dense white clouds and blue sky over trees.

The old Smart Auto model proposed +0.93 EV on flowers but applied 0.00,
and proposed +0.24 EV on the cloud photo but again applied 0.00 because
a tiny fraction of white pixels imposed an absolute exposure veto.
The v0.5.1 fire-only parametric correction never ran on these pictures.

Old WB selection: flower ~2.1% of candidate neutral pixels (<2.5% rule)
was rejected despite hundreds of qualified pixels in multiple independent
areas. Portrait and sky passed the candidate coverage test. Their actual
camera colour may already be acceptable, so an unchanged result is allowed,
but must have truthful diagnostics rather than falsely saying NO neutrals.

## What this experiment changes
* Analyse local texture/structured subject regions alongside global light
  percentiles. Per-scene luminance correction with separate highlight
  safety and encoded parametric Shadows/Darks curves.
* Identify tiny emissive highlights on dark backgrounds without arbitrary
  >0.2% bright pixel test; retain fire's glowing flame tips.
* WB estimator aggregates candidate neutral patches in spatial 4x4 grid.
  A narrow global coverage cutoff does not cause automatic rejection.
  Spatial consistency moderates confidence and allows small, bounded
  reciprocal-K/tint change only when supported.
* A one-tap Smart Auto may apply WB if sufficiently supported, but cannot
  override an intentional manually selected Custom WB.
* Explicit WB states: corrected / already neutral / ambiguous / no neutral.
  UI displays diagnostics instead of treating every zero delta as failure.
* Does not auto-run expensive full-resolution detail detection; v0.3.1
  Auto Detail remains a separate tested command and is unchanged.

## Verified/required quality gates
* Pipeline Rust unit tests including synthesized white-highlight landscape,
  textured fire, neutral/tinted regions, vivid coloured flower and black sky.
* Render curve regression from v0.5.1; calibrated photographic results
  still require on-device testing against Lightroom.
* Android ARM64 build, independent app identifier and release publication.
* Human test: same Kodak files and original fire; inspect actual preview
  and full-size export, ICC colour, haloing, skin tone, cloud detail,
  petal hue, recovered shadows, and Auto WB diagnostics.

## Limitations
This is independently designed scene-adaptive photographic analysis,
not Adobe's proprietary Auto implementation. The earlier Polished AI's
pretrained adaptive-LUT ONNX weights are missing in the inspected source
branch, so this build does not claim neural colour grading or copy models.
This release is a controlled experimental refinement. It must not be
promoted without real-photo quality checks.
