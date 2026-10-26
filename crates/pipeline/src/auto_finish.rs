//! Scene-aware photographic finish for Balanced Auto.
//! Uses the existing editable controls and OkLCh colour operations.
//! The original rendered-image quality guard makes the final decision.

use lightcraft_color::luminance_2020;
use lightcraft_develop::DevelopSettings;
use lightcraft_raster::Rgb32f;
use crate::SourceInfo;
use crate::auto::{AutoTone, AutoToneEvidence};

#[derive(Default)]
struct ColourEvidence {
    visible_fraction: f64,
    colour_fraction: f64,
    soft_fraction: f64,
    vivid_fraction: f64,
    skin_like_fraction: f64,
    mean_saturation: f64,
}

// Approximate perceived channel spread; this is only for Auto measurement.
// The actual edit is rendered in the original OkLCh colour engine.
fn encoded_channel(linear: f32) -> f64 {
    let x = linear.clamp(0.0, 1.0) as f64;
    if x <= 0.0031308 { x * 12.92 }
    else { 1.055 * x.powf(1.0 / 2.4) - 0.055 }
}

fn colour_evidence(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings) -> ColourEvidence {
    let mut proxy = lightcraft_raster::resample::fit(
        src, 256, 256, lightcraft_raster::resample::Filter::Box);
    if proxy.data.is_empty() { return ColourEvidence::default(); }
    let neutral = DevelopSettings { wb: s.wb, ..DevelopSettings::default() };
    crate::local::scene_linear_pre(&mut proxy, info, &neutral);
    let mut count = 0usize;
    let mut colour = 0usize;
    let mut soft = 0usize;
    let mut vivid = 0usize;
    let mut skin_like = 0usize;
    let mut total_sat = 0.0;
    for c in &proxy.data {
        let y = luminance_2020(*c);
        // Dark noise and speculars cannot establish a need for colour.
        if !(0.025..0.80).contains(&y) { continue; }
        let r = encoded_channel(c[0]);
        let g = encoded_channel(c[1]);
        let b = encoded_channel(c[2]);
        let hi = r.max(g).max(b);
        let lo = r.min(g).min(b);
        let sat = (hi - lo) / hi.max(1e-6);
        count += 1;
        total_sat += sat;
        if sat > 0.085 { colour += 1; }
        if (0.09..0.48).contains(&sat) { soft += 1; }
        if sat > 0.68 { vivid += 1; }
        // Conservative warm-chroma protection, not face detection.
        if (0.12..0.56).contains(&sat)
            && r > g * 1.07 && g > b * 1.03 && r < b * 2.10 {
            skin_like += 1;
        }
    }
    if count == 0 { return ColourEvidence::default(); }
    let n = count as f64;
    ColourEvidence {
        visible_fraction: n / proxy.data.len() as f64,
        colour_fraction: colour as f64 / n,
        soft_fraction: soft as f64 / n,
        vivid_fraction: vivid as f64 / n,
        skin_like_fraction: skin_like as f64 / n,
        mean_saturation: total_sat / n,
    }
}

/// Refine v0.6.22's existing proposal; no separate Auto engine or WB changes.
pub(super) fn refine(
    src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings,
    ev: &AutoToneEvidence, mut a: AutoTone,
) -> AutoTone {
    let intentional_dark = (ev.median_ev < -2.3 && ev.dark_fraction > 0.60)
        || (ev.median_ev < -1.65 && ev.dark_fraction > 0.72
            && ev.bright_fraction < 0.055);
    let isolated_lights = ev.median_ev < -1.7 && ev.dark_fraction > 0.56
        && ev.p995_ev > 1.2 && ev.bright_fraction < 0.055;
    // Bright neutral subjects surrounded by darkness (white flowers, a lit
    // object against a shaded pool) should retain luminous whites. This does
    // not re-expose the dark background, nor is it a generic night booster.
    // Require the existing multiscale neutral-white evidence rather than
    // using overall darkness alone (firelight is deliberately excluded).
    if ev.zonal.micro.neutral_white_subject && ev.dark_fraction > 0.72
        && ev.recoverable_fraction < 0.12 && ev.bright_fraction > 0.025
        && ev.bright_fraction < 0.16 && ev.p95_ev > 1.5 {
        a.highlights = a.highlights.max(-8.0);
        a.whites = a.whites.max(-2.0);
        a.shadows = a.shadows.min(12.0);
        return a;
    }
    if intentional_dark || isolated_lights { return a; }

    let c = colour_evidence(src, info, s);
    // Muted coloured areas gain Vibrance; vivid subjects, neutral scenes and
    // skin-like warm areas remain protected. The existing ColorOps adds its
    // own per-pixel skin-hue protection.
    if c.visible_fraction > 0.17 && c.colour_fraction > 0.24
        && c.soft_fraction > 0.18 && c.vivid_fraction < 0.43
        && c.mean_saturation < 0.49 {
        let desired: f64 = if c.mean_saturation < 0.21 { 15.0 }
            else if c.mean_saturation < 0.29 { 12.0 }
            else if c.mean_saturation < 0.37 { 9.0 }
            else { 6.0 };
        let desired = if c.skin_like_fraction > 0.20 {
            desired.min(8.0)
        } else { desired };
        a.vibrance = a.vibrance.max(desired);
        if c.mean_saturation < 0.32 && c.vivid_fraction < 0.13
            && c.skin_like_fraction < 0.10 {
            a.saturation = a.saturation.max(
                if c.mean_saturation < 0.22 { 4.0 } else { 2.0 });
        }
    }

    let spread = ev.p95_ev - ev.p05_ev;
    if ev.p995_ev > 1.75 && ev.bright_fraction > 0.055
        && ev.dark_fraction < 0.68 {
        let need = (14.0 + (ev.p995_ev - 1.75) * 9.0).clamp(14.0, 32.0);
        a.highlights = a.highlights.min(-need.round());
    }
    if ev.recoverable_fraction > 0.24 && ev.dark_fraction > 0.12
        && ev.dark_fraction < 0.60 && ev.median_ev < 0.22
        && ev.p95_ev > 0.1 {
        let need = (10.0 + (ev.recoverable_fraction - 0.24) * 36.0)
            .clamp(10.0, 22.0);
        a.shadows = a.shadows.max(need.round());
        if ev.p05_ev > -5.8 { a.blacks = a.blacks.min(-3.0); }
    }
    if spread > 2.5 && spread < 5.1 && ev.dark_fraction < 0.54
        && ev.bright_fraction < 0.17 && ev.median_ev > -1.55 {
        a.contrast = a.contrast.max(if spread < 3.8 { 7.0 } else { 5.0 });
    }
    if ev.p995_ev < 1.70 && ev.p95_ev < 1.3
        && ev.bright_fraction < 0.06 && ev.dark_fraction < 0.50
        && ev.median_ev > -1.4 && ev.median_ev < 0.6 {
        a.whites = a.whites.max(4.0);
    }
    refine_reference_tone(ev, &mut a);
    a
}

// Small reference-guided adjustments, using measured scene evidence. The
// existing render safety check still runs after this function. In particular,
// already-clipped highlights cannot be reconstructed from camera JPEGs.
fn refine_reference_tone(ev: &AutoToneEvidence, a: &mut AutoTone) {
    // An isolated bright subject plus deep surroundings is not a globally
    // underexposed image: preserve the low-key atmosphere instead of using
    // a +0.3 EV lift or blanket Shadows correction.
    if ev.median_ev < -1.50 && ev.median_ev > -1.95
        && ev.p05_ev > -5.20 && ev.p95_ev > 1.30
        && ev.p995_ev < 2.10
        && ev.p95_ev - ev.median_ev > 2.90
        && ev.bright_fraction < 0.010
        && ev.dark_fraction > 0.19 && ev.dark_fraction < 0.36
        && ev.recoverable_fraction > 0.32 {
        a.exposure = a.exposure.min(0.07);
        a.shadows = a.shadows.min(8.0);
        a.curve_shadows = a.curve_shadows.min(5.0);
        a.curve_darks = a.curve_darks.min(4.0);
        a.curve_lights = a.curve_lights.min(1.0);
        return;
    }

    // On photos with small bright regions that are below severe-clipping
    // evidence, a large negative Highlights adjustment removes the subject's
    // sparkle. Keep substantial recovery for genuinely extreme highlights.
    if ev.p995_ev > 1.75 && ev.p995_ev < 2.36
        && ev.bright_fraction > 0.006 && ev.bright_fraction < 0.13
        && ev.dark_fraction < 0.60 {
        a.highlights = a.highlights.max(-10.0);
        a.whites = a.whites.max(-4.0);
    }

    // A naturally well-exposed daylit image may need a subtle positive
    // upper-midtone finish, not blanket Shadows/HDR. This is not a face
    // detector and cannot change intentional colour grading.
    // Require genuine dark midtone structure as well as a modest key.
    // Bright, already-clipped flower petals and shiny subjects must not get
    // extra global exposure merely because their histograms include shade.
    if ev.median_ev > 0.15 && ev.median_ev < 0.59
        && ev.p95_ev > 1.45 && ev.p995_ev < 2.20
        && ev.dark_fraction > 0.075 && ev.dark_fraction < 0.20
        && ev.bright_fraction < 0.025
        && ev.recoverable_fraction > 0.28 {
        a.curve_lights = a.curve_lights.max(3.0);
        a.exposure = a.exposure.max(0.02);
        a.highlights = a.highlights.max(-9.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn tone(i: &Rgb32f) -> AutoTone {
        crate::auto::smart_auto_tone_report_balanced(
            i, &SourceInfo::default(), &DevelopSettings::default()).final_tone
    }
    fn sample_evidence() -> AutoToneEvidence {
        let scene = Rgb32f::from_fn(80, 64, |x, y| {
            let t = 0.10 + ((x * 7 + y * 11) % 17) as f32 * 0.005;
            [t, t, t]
        });
        crate::auto::smart_auto_tone_report(
            &scene, &SourceInfo::default(), &DevelopSettings::default()).evidence
    }
    #[test]
    fn white_rose_background_retains_depth() {
        let mut e=sample_evidence();
        e.median_ev=-1.78; e.p95_ev=1.59; e.p995_ev=1.91;
        e.bright_fraction=0.0016; e.dark_fraction=0.275;
        e.recoverable_fraction=0.52;
        let mut a=AutoTone { exposure:0.36, shadows:20.0,
            curve_darks:7.0, curve_lights:3.0, ..AutoTone::default() };
        refine_reference_tone(&e,&mut a);
        assert!(a.exposure<=0.07 && a.shadows<=8.0);
        assert!(a.curve_darks<=4.0 && a.curve_lights<=1.0);
    }
    #[test]
    fn saturated_petals_retain_bright_midtones() {
        let mut e=sample_evidence();
        e.median_ev=-1.19; e.p95_ev=2.03; e.p995_ev=2.20;
        e.bright_fraction=0.079; e.dark_fraction=0.273;
        e.recoverable_fraction=0.398;
        let mut a=AutoTone {highlights:-18.0, ..AutoTone::default()};
        refine_reference_tone(&e,&mut a);
        assert_eq!(a.highlights,-10.0);
    }
    #[test]
    fn full_daylight_keeps_moderate_positive_finish() {
        let mut e=sample_evidence();
        e.median_ev=0.49; e.p95_ev=1.79; e.p995_ev=2.12;
        e.bright_fraction=0.013; e.dark_fraction=0.125;
        e.recoverable_fraction=0.382;
        let mut a=AutoTone {exposure:-0.01, highlights:-13.0, ..AutoTone::default()};
        refine_reference_tone(&e,&mut a);
        assert!(a.exposure>=0.02 && a.curve_lights>=3.0 && a.highlights>=-9.0);
    }
    #[test]
    fn bright_orange_flower_does_not_get_global_exposure() {
        let mut e=sample_evidence();
        e.median_ev=0.598; e.p05_ev=-2.13; e.p95_ev=1.74; e.p995_ev=2.16;
        e.bright_fraction=0.015; e.dark_fraction=0.034;
        e.recoverable_fraction=0.468;
        let mut a=AutoTone {exposure:-0.03, highlights:0.0, ..AutoTone::default()};
        refine_reference_tone(&e,&mut a);
        assert_eq!(a.exposure,-0.03);
        assert_eq!(a.curve_lights,0.0);
    }
    #[test]
    fn misty_park_is_not_a_single_white_subject() {
        let mut e=sample_evidence();
        e.median_ev=-1.645; e.p05_ev=-6.565;
        e.p95_ev=1.45; e.p995_ev=1.95;
        e.bright_fraction=0.004; e.dark_fraction=0.302;
        e.recoverable_fraction=0.471;
        let mut a=AutoTone {exposure:0.54, shadows:18.0, ..AutoTone::default()};
        refine_reference_tone(&e,&mut a);
        assert_eq!(a.exposure,0.54);
        assert_eq!(a.shadows,18.0);
    }
    #[test]
    fn dark_foliage_does_not_force_extra_clipping() {
        let mut e=sample_evidence();
        e.median_ev=-2.036; e.p05_ev=-7.412;
        e.p95_ev=1.601; e.p995_ev=2.202;
        e.bright_fraction=0.015; e.dark_fraction=0.458;
        e.recoverable_fraction=0.330;
        let mut a=AutoTone {exposure:0.0, shadows:13.0, ..AutoTone::default()};
        refine_reference_tone(&e,&mut a);
        assert_eq!(a.exposure,0.0);
        assert_eq!(a.shadows,13.0);
    }
    #[test]
    fn severe_highlights_and_night_remain_protected() {
        let mut e=sample_evidence();
        e.median_ev=-3.1; e.p95_ev=2.0; e.p995_ev=2.8;
        e.bright_fraction=0.08; e.dark_fraction=0.75;
        e.recoverable_fraction=0.03;
        let mut a=AutoTone {exposure:0.0, highlights:-25.0, ..AutoTone::default()};
        refine_reference_tone(&e,&mut a);
        assert_eq!(a.highlights,-25.0);
        assert_eq!(a.exposure,0.0);
    }
    #[test]
    fn strong_backlight_retains_highlight_protection() {
        let mut e=sample_evidence();
        e.median_ev=-0.8; e.p95_ev=2.2; e.p995_ev=2.65;
        e.bright_fraction=0.25; e.dark_fraction=0.20;
        e.recoverable_fraction=0.38;
        let mut a=AutoTone { highlights:-28.0, ..AutoTone::default() };
        refine_reference_tone(&e,&mut a);
        assert_eq!(a.highlights,-28.0);
    }
    #[test]
    fn muted_colour_receives_perceptual_vibrance() {
        let image = Rgb32f::from_fn(320, 240, |x, y| {
            let t = 0.12 + ((x * 7 + y * 11) % 43) as f32 * 0.004;
            if y < 90 { [t * 0.73, t * 0.91, t * 1.17] }
            else { [t * 0.83, t * 1.12, t * 0.80] }
        });
        let a = tone(&image);
        assert!(a.vibrance >= 6.0, "Muted scene needs colour: {a:?}");
    }
    #[test]
    fn vivid_petals_do_not_force_saturation() {
        let image = Rgb32f::from_fn(320, 240, |x, y| {
            if x > 70 && x < 235 && y > 40 && y < 208 {
                [0.73, 0.045, 0.24]
            } else { [0.03, 0.23, 0.043] }
        });
        let a = tone(&image);
        assert!(a.saturation <= 2.0 && a.vibrance <= 8.0,
            "Vivid flower should retain chroma: {a:?}");
    }
    #[test]
    fn neutral_pixels_remain_neutral() {
        let image = Rgb32f::from_fn(320, 240, |x, y| {
            let v = 0.13 + ((x + y) % 31) as f32 * 0.005;
            [v, v, v]
        });
        let a = tone(&image);
        assert_eq!((a.vibrance, a.saturation), (0.0, 0.0));
    }
    #[test]
    fn small_bright_emitter_keeps_night_atmosphere() {
        let image = Rgb32f::from_fn(320, 240, |x, y| {
            if (140..173).contains(&x) && (70..135).contains(&y) {
                [0.99, 0.49, 0.04]
            } else { [0.006, 0.009, 0.006] }
        });
        let a = tone(&image);
        assert!(a.exposure <= 0.18 && a.saturation <= 2.0,
            "Night atmosphere preserved: {a:?}");
    }
    #[test]
    fn shaded_daylight_receives_real_tone_adjustments() {
        let image = Rgb32f::from_fn(320, 240, |x, y| {
            let t = ((x * 3 + y * 7) % 31) as f32 * 0.002;
            if y < 98 { [0.31 + t, 0.43 + t, 0.55 + t] }
            else { [0.055 + t, 0.10 + t, 0.060 + t] }
        });
        let a = tone(&image);
        assert!(a.shadows >= 7.0 || a.highlights <= -10.0 || a.contrast >= 4.0,
            "Daylight should improve without flattening: {a:?}");
    }
}
