# Dennis Photo Studio v0.3.1 — Full-resolution Auto Detail hotfix

## Why v0.3 returned zero in the Noise and Color Noise sliders

Investigation of the released v0.3 code confirmed two separate causes:
1. The main Auto button operates **develop.auto** (tone/colour) only. **develop.autoDetail** runs only from **Edit > Detail > Auto Detail**.
2. Auto Detail fed **SourceLevel::Thumb** (<=512 long-edge) into high-frequency analysis. Downsampling removes luminance grain and chroma speckling. Filtering was overly restrictive (gradient max 0.044, residual low-percentile 0.32, luminance cutoff 0.0026, chroma cutoff 0.0030). As a result the output NR values were often 0 even for visibly noisy originals.

## Fix and limits
- Analyse the native decoded source (<=24 million pixels) at sampled pixel positions while preserving original one-pixel neighbours; >24 MP goes to 2560px preview to avoid high RAM use.
- Gate sharp edges less severely (gradient <=0.09), use a median-like 55th percentile instead of the low third, and lower NR cutoffs with capped intensity.
- New regression tests assert that intentionally noisy images get **nonzero** luminance and chroma NR; synthetic clean images keep both at 0. The tests cannot establish that photographic source detail is noise.
- Detail section shows numeric **Luminance NR / Colour NR / Sharpen** even with sliders hidden. User still taps **Auto Detail** separately from main tone Auto.
- All original Detail manual sliders and historic develop settings/presets are unchanged. No automatic change is made upon importing a photo.
- Risk: a full source decode temporarily uses considerable RAM. Downsampling fallback above 24 MP is conservative but can miss sensor grain. Future production algorithm may use a native-scale tile-based estimator and explicit camera ISO/shot-noise models.

## Quality bar
Retain JPEG/ICC and WB pipeline; test at 100% on uniform smooth gradients, dim walls, portrait skin, foliage, fabric, and coloured shadows. Noise reduction should not produce plastic skin, bleeding, haloing, and false texture. No blanket adjustment to all images. v0.3 rollback untouched.
