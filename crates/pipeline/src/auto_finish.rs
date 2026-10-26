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
    a
}

#[cfg(test)]
mod tests {
    use super::*;
    fn tone(i: &Rgb32f) -> AutoTone {
        crate::auto::smart_auto_tone_report_balanced(
            i, &SourceInfo::default(), &DevelopSettings::default()).final_tone
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
