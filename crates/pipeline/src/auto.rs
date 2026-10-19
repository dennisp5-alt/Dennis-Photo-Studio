//! Auto tone and auto white balance (histogram / grey-world statistics on a proxy).

use lightcraft_color::cct::xy_to_temp_tint;
use lightcraft_color::{REC2020, Xy, bradford, luminance_2020};
use lightcraft_develop::DevelopSettings;
use lightcraft_raster::Rgb32f;
use serde::Serialize;

use crate::SourceInfo;
use crate::local::effective_wb;

#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize)]
pub struct AutoTone {
    pub exposure: f64,
    pub contrast: f64,
    pub highlights: f64,
    pub shadows: f64,
    pub whites: f64,
    pub blacks: f64,
    pub vibrance: f64,
    pub saturation: f64,
}

fn percentile(sorted: &[f32], q: f32) -> f32 {
    if sorted.is_empty() {
        return 0.0;
    }
    sorted[((sorted.len() - 1) as f32 * q.clamp(0.0, 1.0)) as usize]
}

/// Compute auto tone values for `src` under the current white balance (ignores current tone values).
pub fn auto_tone(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings) -> AutoTone {
    let mut img = lightcraft_raster::resample::fit(src, 512, 512, lightcraft_raster::resample::Filter::Box);
    let mut base = DevelopSettings { wb: s.wb, ..DevelopSettings::default() };
    base.light.exposure = 0.0;
    crate::local::scene_linear_pre(&mut img, info, &base);
    let mut ev: Vec<f32> = img.data.iter().map(|c| (luminance_2020(*c).max(1e-6) / 0.18).log2()).collect();
    ev.sort_by(|a, b| a.total_cmp(b));
    let median = percentile(&ev, 0.5);
    let exposure = (-median * 0.85 - 0.1).clamp(-4.0, 4.0);
    let (p01, p05, p95, p995) =
        (percentile(&ev, 0.01) + exposure, percentile(&ev, 0.05) + exposure, percentile(&ev, 0.95) + exposure, percentile(&ev, 0.995) + exposure);
    let highlights = if p995 > 2.2 { -((p995 - 2.2) * 38.0).min(90.0) } else { 0.0 };
    let shadows = if p05 < -4.0 { ((-4.0 - p05) * 22.0).min(70.0) } else { 0.0 };
    let spread = p95 - p05;
    let contrast = ((6.5 - spread) * 6.0).clamp(-20.0, 30.0);
    let whites = if p995 < 1.8 { ((1.8 - p995) * 25.0).min(40.0) } else { -((p995 - 3.5).max(0.0) * 10.0).min(30.0) };
    let blacks = if p01 > -5.0 { -((p01 + 5.0) * 10.0).min(35.0) } else { ((-7.0 - p01).max(0.0) * 8.0).min(20.0) };
    AutoTone {
        exposure: (exposure as f64 * 100.0).round() / 100.0,
        contrast: contrast.round() as f64,
        highlights: highlights.round() as f64,
        shadows: shadows.round() as f64,
        whites: whites.round() as f64,
        blacks: blacks.round() as f64,
        vibrance: 12.0,
        saturation: 3.0,
    }
}

/// Confidence-aware auto tone. The original implementation remains available
/// as "Classic Auto" for exact comparison. We use source-relative scene-linear
/// histogram statistics, not a guessed semantic scene label.
pub fn smart_auto_tone(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings) -> AutoTone {
    let mut old = auto_tone(src, info, s);
    let mut img = lightcraft_raster::resample::fit(
        src, 512, 512, lightcraft_raster::resample::Filter::Box);
    let base = DevelopSettings { wb: s.wb, ..DevelopSettings::default() };
    crate::local::scene_linear_pre(&mut img, info, &base);
    if img.data.is_empty() { return old; }
    let mut ev = Vec::with_capacity(img.data.len());
    let mut colour_mass = 0.0f64;
    let mut colour_count = 0.0f64;
    for rgb in &img.data {
        let y = luminance_2020(*rgb).max(1e-6);
        ev.push((y / 0.18).log2());
        if (0.025..0.80).contains(&y) {
            let maxc = rgb[0].max(rgb[1]).max(rgb[2]).max(1e-6);
            let minc = rgb[0].min(rgb[1]).min(rgb[2]);
            let sat = ((maxc - minc) / maxc).clamp(0.0, 1.0) as f64;
            colour_mass += sat;
            colour_count += 1.0;
        }
    }
    ev.sort_by(|a,b| a.total_cmp(b));
    let median = percentile(&ev, 0.50) as f64;
    let p05 = percentile(&ev, 0.05) as f64;
    let p95 = percentile(&ev, 0.95) as f64;
    let p995 = percentile(&ev, 0.995) as f64;
    let upper_range = p995 - median;
    let high_contrast = upper_range > 4.0 || (p995 - p05) > 8.5;
    let already_balanced = median.abs() < 0.45;
    let highlight_headroom = (2.25 - p995).max(0.0);
    if old.exposure > 0.0 {
        let mut cap: f64 = if high_contrast { 0.60 } else { 1.40 };
        if already_balanced { cap = cap.min(0.28); }
        if p995 > 1.7 { cap = cap.min(highlight_headroom); }
        old.exposure = old.exposure.min(cap).max(0.0);
    } else {
        old.exposure = old.exposure.max(-1.50);
    }
    // Camera JPEGs often already contain boosted saturation. Never add a
    // fixed vibrance/saturation offset. Use measured midtone chroma instead.
    let mean_sat = if colour_count > 0.0 {
        colour_mass / colour_count
    } else { 0.0 };
    old.vibrance = ((0.26 - mean_sat) * 30.0).clamp(0.0, 8.0).round();
    old.saturation = 0.0;
    if high_contrast {
        old.contrast = old.contrast.clamp(-12.0, 10.0);
        old.blacks = old.blacks.clamp(-10.0, 16.0);
        old.shadows = old.shadows.clamp(-10.0, 35.0);
    }
    if p995 > 1.7 {
        old.highlights = old.highlights.min(-20.0);
        old.whites = old.whites.min(0.0);
    }
    old
}

/// An automatic black & white mix (slider values, red … magenta) for `src` under `s`'s white
/// balance and tone: each hue band's colourful pixels are pushed away from the image's mean
/// lightness — bands brighter than average get brighter, darker ones darker — so areas that
/// differ only in colour stay apart in grey. Bands with almost no colourful pixels stay at 0.
pub fn auto_bw_mix(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings) -> [f64; 8] {
    use lightcraft_color::perceptual::{lab_to_lch, oklab_from_2020};
    let mut img = lightcraft_raster::resample::fit(src, 512, 512, lightcraft_raster::resample::Filter::Box);
    let base = DevelopSettings { wb: s.wb, light: s.light, ..DevelopSettings::default() };
    crate::local::scene_linear_pre(&mut img, info, &base);
    let gain = 2f32.powf(base.light.exposure as f32);
    let (mut mass, mut sum_l) = ([0f64; 8], [0f64; 8]);
    let (mut all_l, mut n) = (0f64, 0usize);
    for p in &img.data {
        let lch = lab_to_lch(oklab_from_2020(p.map(|v| (v * gain).max(0.0))));
        all_l += lch[0] as f64;
        n += 1;
        let k = (lch[1] / 0.2).min(1.0) as f64; // as in the B&W conversion
        if k < 0.05 {
            continue;
        }
        let w = crate::colorops::band_weights(lch[2]);
        for i in 0..8 {
            mass[i] += w[i] as f64 * k;
            sum_l[i] += w[i] as f64 * k * lch[0] as f64;
        }
    }
    if n == 0 {
        return [0.0; 8];
    }
    let mean = all_l / n as f64;
    let total: f64 = mass.iter().sum();
    std::array::from_fn(|i| {
        if total <= 0.0 || mass[i] / total < 0.01 {
            return 0.0;
        }
        let sep = sum_l[i] / mass[i] - mean;
        (sep * 400.0).clamp(-60.0, 60.0).round()
    })
}

/// Grey-world white balance weighted towards mid-tone, low-chroma pixels. Returns (temp, tint).
pub fn auto_wb_classic(src: &Rgb32f, info: &SourceInfo) -> (f64, f64) {
    let img = lightcraft_raster::resample::fit(src, 256, 256, lightcraft_raster::resample::Filter::Box);
    let (mut acc, mut wsum) = ([0.0f64; 3], 0.0f64);
    for c in &img.data {
        let y = luminance_2020(*c);
        if !(0.01..=2.0).contains(&y) {
            continue;
        }
        let mx = c[0].max(c[1]).max(c[2]);
        let mn = c[0].min(c[1]).min(c[2]);
        let chroma = (mx - mn) / (mx + 1e-6);
        let w = (1.0 - chroma).powi(2) as f64 * (1.0 - ((y.log2() + 2.5) / 4.0).abs().min(1.0)) as f64;
        for i in 0..3 {
            acc[i] += c[i] as f64 * w;
        }
        wsum += w;
    }
    if wsum <= 0.0 {
        return (info.as_shot_temp, info.as_shot_tint);
    }
    let avg = acc.map(|v| v / wsum);
    // the white that makes `avg` neutral, through the same model the white balance renders with
    // (`local::wb_matrix_for`): the camera's own colour model, else a Bradford adaptation
    let camera = info.camera_color.as_deref().filter(|_| info.raw && !info.relative_wb);
    let seen = match camera.and_then(|cc| lightcraft_raw::color::neutral_white(&cc.tags, cc.developed_for, avg)) {
        Some(white) => white,
        None => {
            let shot = lightcraft_color::cct::temp_tint_to_xy(info.as_shot_temp, info.as_shot_tint);
            Xy::from_xyz(bradford(REC2020.white, shot).apply(REC2020.to_xyz().apply(avg)))
        }
    };
    let (t, tint) = xy_to_temp_tint(seen);
    (t.clamp(2000.0, 50000.0).round(), tint.clamp(-150.0, 150.0).round())
}

/// Conservative neutral-supported Auto White Balance.
///
/// Gray-world on dominant greens, sunsets or saturated petals is ambiguous
/// without a calibrated neutral target. Preserve the camera as-shot balance
/// unless many neutral candidates are distributed across the photograph.
/// Restrict the maximum perceptual WB shift in mireds and tint to protect skin
/// and JPEGs that were already white-balanced by their camera.
pub fn auto_wb(src: &Rgb32f, info: &SourceInfo) -> (f64, f64) {
    let shot = (info.as_shot_temp, info.as_shot_tint);
    let img = lightcraft_raster::resample::fit(
        src, 256, 256, lightcraft_raster::resample::Filter::Box);
    if img.width == 0 || img.height == 0 || img.data.is_empty() {
        return shot;
    }
    let mut neutral = 0usize;
    let mut eligible = 0usize;
    let mut blocks = [0usize; 16];
    let mut avg = [0.0f64; 3];
    let mut total = 0.0f64;
    let w = img.width as usize;
    let h = img.height as usize;
    for (index, c) in img.data.iter().enumerate() {
        let y = luminance_2020(*c);
        if !(0.035..=0.75).contains(&y) { continue; }
        eligible += 1;
        let hi = c[0].max(c[1]).max(c[2]).max(1e-6);
        let lo = c[0].min(c[1]).min(c[2]);
        let chroma = ((hi - lo) / hi).clamp(0.0, 1.0);
        if chroma > 0.16 { continue; }
        let weight = ((0.16 - chroma) / 0.16).powi(2) as f64;
        if weight < 0.015 { continue; }
        let px = index % w;
        let py = index / w;
        let cell = ((py * 4 / h).min(3)) * 4 + ((px * 4 / w).min(3));
        blocks[cell] += 1;
        for channel in 0..3 { avg[channel] += c[channel] as f64 * weight; }
        total += weight;
        neutral += 1;
    }
    let coverage = blocks.iter().filter(|&&count| count > 12).count();
    // Require both numerical support and broad spatial support. A brightly
    // coloured rose against foliage should not be used as a neutral card.
    if eligible == 0 || neutral < 180 ||
        (neutral as f64 / eligible as f64) < 0.035 ||
        coverage < 5 || total < 15.0 {
        return shot;
    }
    let mean = avg.map(|v| (v / total) as f32);
    let provisional = Rgb32f::filled(16, 16, mean);
    let (temp, tint) = auto_wb_classic(&provisional, info);
    if !temp.is_finite() || !tint.is_finite() { return shot; }
    let confidence = ((neutral as f64 / eligible as f64) / 0.26)
        .clamp(0.0, 1.0) * (coverage as f64 / 16.0).sqrt();
    let allowed_shift = if info.raw { 45.0 } else { 28.0 };
    let allowed_tint = if info.raw { 24.0 } else { 16.0 };
    let src_mired = 1_000_000.0 / shot.0.clamp(2000.0, 50000.0);
    let goal_mired = 1_000_000.0 / temp.clamp(2000.0, 50000.0);
    let delta = ((goal_mired - src_mired) * confidence)
        .clamp(-allowed_shift, allowed_shift);
    let safe_temp = (1_000_000.0 / (src_mired + delta))
        .clamp(2000.0, 50000.0);
    let safe_tint = shot.1 +
        ((tint - shot.1) * confidence).clamp(-allowed_tint, allowed_tint);
    (safe_temp.round(), safe_tint.round())
}

/// Temperature/tint currently in effect (for UI display).
pub fn current_wb(info: &SourceInfo, s: &DevelopSettings) -> (f64, f64) {
    effective_wb(info, s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dark_image_gets_positive_exposure() {
        let img = Rgb32f::from_fn(64, 64, |x, _| [0.01 + x as f32 * 0.0003; 3]);
        let a = auto_tone(&img, &SourceInfo::default(), &DevelopSettings::default());
        assert!(a.exposure > 1.5, "{a:?}");
        let bright = Rgb32f::from_fn(64, 64, |x, _| [0.8 + x as f32 * 0.01; 3]);
        let b = auto_tone(&bright, &SourceInfo::default(), &DevelopSettings::default());
        assert!(b.exposure < -1.0, "{b:?}");
    }

    #[test]
    fn neutral_image_keeps_as_shot_wb() {
        let img = Rgb32f::from_fn(32, 32, |x, y| [0.05 + (x + y) as f32 * 0.004; 3]);
        let (t, tint) = auto_wb_classic(&img, &SourceInfo::default());
        assert!((t - 6500.0).abs() < 150.0, "{t}");
        assert!(tint.abs() < 6.0, "{tint}");
    }

    #[test]
    fn blue_cast_is_corrected_by_higher_temp() {
        // A bluish cast should be neutralised by telling the pipeline the light was bluer (higher K).
        let img = Rgb32f::from_fn(32, 32, |_, _| [0.16, 0.18, 0.24]);
        let (t, _) = auto_wb_classic(&img, &SourceInfo::default());
        assert!(t > 7000.0, "{t}");
        let mut s = DevelopSettings::default();
        s.wb.mode = lightcraft_develop::WbMode::Custom;
        s.wb.temp = t;
        let (t2, tint2) = auto_wb_classic(&img, &SourceInfo::default());
        let _ = (t2, tint2);
        let mut out = img.clone();
        let (tt, ti) = auto_wb_classic(&img, &SourceInfo::default());
        s.wb.temp = tt;
        s.wb.tint = ti;
        crate::local::scene_linear_pre(&mut out, &SourceInfo::default(), &s);
        let c = out.get(0, 0);
        assert!((c[0] - c[2]).abs() < 0.02, "{c:?}");
    }
}

#[cfg(test)]
mod bw_tests {
    use super::*;

    #[test]
    fn auto_bw_mix_pushes_light_and_dark_hues_apart() {
        // left: a light yellow, right: a dark blue (scene-linear Rec. 2020)
        let src = Rgb32f::from_fn(64, 32, |x, _| if x < 32 { [0.55, 0.5, 0.05] } else { [0.01, 0.02, 0.12] });
        let m = auto_bw_mix(&src, &SourceInfo::default(), &DevelopSettings::default());
        let (yellow, blue) = (m[2], m[5]);
        assert!(yellow > 0.0 && blue < 0.0, "{m:?}");
        // an image without colour leaves the mix alone
        let grey = Rgb32f::from_fn(16, 16, |x, _| [x as f32 / 16.0; 3]);
        assert_eq!(auto_bw_mix(&grey, &SourceInfo::default(), &DevelopSettings::default()), [0.0; 8]);
    }
}

#[cfg(test)]
mod tint_tests {
    use super::*;

    #[test]
    fn auto_wb_and_picker_correct_green_with_positive_tint() {
        // The picker delegates a sampled patch to this same auto_wb implementation.
        for (rgb, sign) in [([0.18, 0.24, 0.18], 1.0), ([0.24, 0.18, 0.24], -1.0)] {
            let img = Rgb32f::filled(16, 16, rgb);
            let info = SourceInfo { raw: true, relative_wb: true, ..Default::default() };
            let (temp, tint) = auto_wb_classic(&img, &info);
            assert!(tint * sign > 0.0, "{rgb:?}: temp {temp}, tint {tint}");
            let mut s = DevelopSettings::default();
            s.wb.mode = lightcraft_develop::WbMode::Custom;
            s.wb.temp = temp;
            s.wb.tint = tint;
            let mut corrected = img;
            crate::local::white_balance(&mut corrected, &info, &s);
            let p = corrected.get(0, 0);
            let spread = p[0].max(p[1]).max(p[2]) - p[0].min(p[1]).min(p[2]);
            assert!(spread < 0.003, "{rgb:?} -> {p:?}");
        }
    }
}

#[cfg(test)]
mod studio_auto_safety {
    use super::*;
    #[test]
    fn colourful_scenes_keep_camera_white_balance() {
        let info=SourceInfo::default();
        let flower=Rgb32f::from_fn(64,64,|x,y| {
            if (x+y)%5 == 0 { [0.60,0.04,0.13] }
            else { [0.03,0.31,0.035] }
        });
        assert_eq!(auto_wb(&flower,&info),
            (info.as_shot_temp,info.as_shot_tint));
    }
    #[test]
    fn neutral_image_not_overcorrected() {
        let info=SourceInfo::default();
        let img=Rgb32f::filled(64,64,[0.18,0.18,0.18]);
        let (temp,tint)=auto_wb(&img,&info);
        assert!((1_000_000.0/temp - 1_000_000.0/info.as_shot_temp).abs()<=28.01);
        assert!((tint-info.as_shot_tint).abs()<=16.01);
    }
    #[test]
    fn smart_auto_protects_bright_subject_on_dark_scene() {
        let image=Rgb32f::from_fn(96,96,|x,y| {
            if x>35 && x<65 && y>20 && y<50 { [1.4,0.70,0.55] }
            else { [0.007,0.009,0.006] }
        });
        let info=SourceInfo::default();
        let settings=DevelopSettings::default();
        let classic=auto_tone(&image,&info,&settings);
        let smart=smart_auto_tone(&image,&info,&settings);
        assert!(smart.exposure <= classic.exposure + 1e-6);
        assert!(smart.vibrance <= classic.vibrance);
        assert!(smart.saturation == 0.0);
    }
}
