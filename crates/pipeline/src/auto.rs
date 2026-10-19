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

/// Scene-adaptive tone that protects self-luminous highlights (fire, lamps,
/// sunsets and reflections). A histogram cannot recognise every scene, so
/// corrections are deliberately limited and neutral photographs stay near
/// their source rendering.
///
/// Important fix: Classic Auto computed highlights using an *unrestricted*
/// exposure value. Reusing that highlight value after reducing exposure
/// darkened flames despite the Smart exposure cap. Recalculate highlight and
/// white settings from the final Smart exposure, never from Classic's proposal.
pub fn smart_auto_tone(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings) -> AutoTone {
    let mut old = auto_tone(src, info, s);
    let mut img = lightcraft_raster::resample::fit(
        src, 512, 512, lightcraft_raster::resample::Filter::Box);
    let base = DevelopSettings { wb: s.wb, ..DevelopSettings::default() };
    crate::local::scene_linear_pre(&mut img, info, &base);
    if img.data.is_empty() { return old; }
    let mut ev = Vec::with_capacity(img.data.len());
    let mut colour_mass = 0.0f64;
    let mut colour_count = 0usize;
    let mut shadow_count = 0usize;
    let mut bright_count = 0usize;
    let mut recoverable_midtone_count = 0usize;
    for rgb in &img.data {
        let y = luminance_2020(*rgb).max(1e-6);
        ev.push((y / 0.18).log2());
        if y < 0.028 { shadow_count += 1; }
        if y > 0.70 { bright_count += 1; }
        // Wood, buildings and ground exposed by flame or streetlights:
        // above the noise floor, but far below the bright subject.
        if (0.035..0.28).contains(&y) { recoverable_midtone_count += 1; }
        if (0.025..0.80).contains(&y) {
            let maxc = rgb[0].max(rgb[1]).max(rgb[2]).max(1e-6);
            let minc = rgb[0].min(rgb[1]).min(rgb[2]);
            colour_mass += ((maxc-minc)/maxc).clamp(0.0,1.0) as f64;
            colour_count += 1;
        }
    }
    ev.sort_by(|a,b| a.total_cmp(b));
    let median = percentile(&ev, 0.50) as f64;
    let p05 = percentile(&ev, 0.05) as f64;
    let p95 = percentile(&ev, 0.95) as f64;
    let p995 = percentile(&ev, 0.995) as f64;
    let shadow_fraction = shadow_count as f64 / ev.len() as f64;
    let bright_fraction = bright_count as f64 / ev.len() as f64;
    let recoverable_fraction = recoverable_midtone_count as f64 / ev.len() as f64;

    // Lightroom separates Highlights/Shadows/Whites/Blacks as independent
    // tonal controls. For a dark photo with luminous highlights, preserve
    // emission and lift ONLY recoverable low-midtones, never global exposure.
    // Adobe's proprietary Auto model was NOT copied or reverse engineered.
    // This is a conservative, independently designed histogram estimator.
    let intentional_light = shadow_fraction > 0.50
        && median < -2.3
        && p995 > 1.6
        && p995 - median > 4.5
        && bright_fraction > 0.002
        && bright_fraction < 0.35;
    if intentional_light {
        // V0.4 returned all zero: safe but visibly unhelpful on fire.
        // A measurable small boost to recoverable wood/ground is appropriate
        // ONLY if enough above-black structure exists. Prevent black skies
        // and empty night scenes from turning gray or revealing sensor noise.
        let recoverable = recoverable_fraction > 0.012;
        let shadow_lift = if recoverable {
            (9.0 + 65.0 * recoverable_fraction).clamp(9.0, 22.0).round()
        } else { 0.0 };
        return AutoTone {
            exposure: 0.0, contrast: 0.0, highlights: 0.0,
            shadows: shadow_lift, whites: 0.0,
            blacks: if recoverable { -4.0 } else { 0.0 },
            vibrance: 0.0, saturation: 0.0,
        };
    }

    let spread = p995-p05;
    let high_contrast = spread > 7.7 || p995-median > 4.1;
    let balanced = median.abs() < 0.45;
    if old.exposure > 0.0 {
        let mut cap: f64 = if high_contrast { 0.55 } else { 1.35 };
        if balanced { cap=cap.min(0.20); }
        // Leave headroom for bright surfaces without trying to compress
        // them through an aggressive global highlight curve.
        if p995 > 1.55 {
            cap=cap.min((2.00-p995).max(0.0));
        }
        old.exposure=old.exposure.min(cap).max(0.0);
    } else {
        old.exposure=old.exposure.max(if high_contrast {-0.50}else{-1.25});
        if balanced {old.exposure=old.exposure.max(-0.20);}
    }

    // Recompute using corrected exposure. The prior v0.2-v0.3.1 logic
    // accidentally retained Classic's ~-90 highlight correction here.
    let corrected_top = p995+old.exposure;
    old.highlights=if corrected_top>2.15 {
        -((corrected_top-2.15)*12.0).clamp(0.0,26.0).round()
    } else {0.0};
    old.whites=if corrected_top>2.75 {
        -((corrected_top-2.75)*8.0).clamp(0.0,14.0).round()
    } else {0.0};

    old.contrast=old.contrast.clamp(if high_contrast {-8.0}else{-14.0},14.0);
    old.shadows=old.shadows.clamp(0.0,if high_contrast {20.0}else{38.0});
    old.blacks=old.blacks.clamp(-10.0,12.0);

    // JPEGs have often received camera vibrance already. Avoid fixed boosts.
    let mean_sat=if colour_count>0 {colour_mass/colour_count as f64}else{0.0};
    old.vibrance=((0.23-mean_sat)*25.0).clamp(0.0,6.0).round();
    old.saturation=0.0;
    old
}

/// Automatic edit SETTINGS, not a replacement denoiser or a claimed AI model.
/// Proxy statistics are conservative: thumb data underestimates pixel noise,
/// so never force strong corrections or sharpen flat regions.
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize)]
pub struct AutoDetail {
    pub sharpen_amount: f64,
    pub sharpen_radius: f64,
    pub sharpen_detail: f64,
    pub sharpen_masking: f64,
    pub nr_luminance: f64,
    pub nr_color: f64,
    pub nr_detail: f64,
    pub nr_color_detail: f64,
    pub nr_color_smoothness: f64,
    pub noise_score: f64,
    pub confidence: f64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize)]
pub struct AutoEffects {
    pub texture: f64,
    pub clarity: f64,
    pub dehaze: f64,
    pub confidence: f64,
}

/// Flat-region, multi-channel high-pass statistics, calculated on the native
/// decoded source proxy. Exclude over/under-exposed pixels and large edges.
/// Statistics reflect noise-plus-detail in a JPEG, NOT ground-truth sensor noise.
fn auto_quality_metrics(src: &Rgb32f) -> (f64, f64, f64, f64, f64) {
    let w = src.width;
    let h = src.height;
    if w < 16 || h < 16 { return (0.0, 0.0, 0.0, 0.0, 0.0); }
    let mut smooth_y = Vec::new();
    let mut smooth_color = Vec::new();
    let mut edges = Vec::new();
    let mut valid = 0usize;
    let mut saturated = 0usize;
    // Uniform stride preserves a limited, reproducible CPU budget.
    let stride = (w.max(h) / 512).max(2);
    for y in (2..h - 2).step_by(stride) {
        for x in (2..w - 2).step_by(stride) {
            let i = y * w + x;
            let c = src.data[i];
            let yl = luminance_2020(c);
            if !yl.is_finite() || !(0.018..0.90).contains(&yl) { continue; }
            valid += 1;
            let a=src.data[i-1];
            let b=src.data[i+1];
            let u=src.data[i-w];
            let d=src.data[i+w];
            let g1 = (luminance_2020(a) - luminance_2020(b)).abs();
            let g2 = (luminance_2020(u) - luminance_2020(d)).abs();
            let gradient = (g1 + g2)*0.5;
            edges.push(gradient as f64);
            if c.iter().any(|v| *v > 0.97) { saturated+=1; }
            // Exclude structures. Fine filaments/hair may still reach the
            // collection, which is why final model strengths are bounded.
            if gradient > 0.090 || smooth_y.len() > 160_000 { continue; }
            let mean_y=(luminance_2020(a)+luminance_2020(b)+
                luminance_2020(u)+luminance_2020(d))*0.25;
            let lum=(yl-mean_y).abs() as f64;
            // RG and BG chrominance residuals relative to 4 neighbours.
            let chroma = |v: [f32;3]| -> (f32,f32) {
                (v[0]-v[1],v[2]-v[1])
            };
            let (cr,cblue)=chroma(c);
            let chroma_of_avg = |k:usize| {
                let v=src.data[k];
                (v[0]-v[1],v[2]-v[1])
            };
            let mut avg_cr=0.0;
            let mut avg_cb=0.0;
            for k in [i-1,i+1,i-w,i+w] {
                let (rr,bb)=chroma_of_avg(k);
                avg_cr+=rr*0.25;
                avg_cb+=bb*0.25;
            }
            let chroma_res=(((cr-avg_cr).abs()+(cblue-avg_cb).abs())*0.5) as f64;
            smooth_y.push(lum);
            smooth_color.push(chroma_res);
        }
    }
    if valid < 120 || edges.is_empty() {return (0.0,0.0,0.0,0.0,0.0);}
    smooth_y.sort_by(|a,b| a.total_cmp(b));
    smooth_color.sort_by(|a,b| a.total_cmp(b));
    edges.sort_by(|a,b| a.total_cmp(b));
    // The low quartile rejects many textured pixels; it remains an estimate.
    let q = |v: &Vec<f64>, fraction: f64| -> f64 {
        if v.is_empty(){0.0}else{v[((v.len()-1) as f64*fraction) as usize]}
    };
    let lum=q(&smooth_y,0.55);
    let chrom=q(&smooth_color,0.55);
    let detail=q(&edges,0.75);
    let confidence= (smooth_y.len() as f64 / valid as f64).clamp(0.0,1.0)
        * (smooth_y.len() as f64 / 800.0).clamp(0.0,1.0);
    (lum,chrom,detail,saturated as f64/valid as f64,confidence)
}

/// Choose source-adaptive luminance NR, chroma NR and edge-only sharpening.
/// No cloud, neural network or fake 'AI denoise' claim.
pub fn auto_detail(src: &Rgb32f, info: &SourceInfo) -> AutoDetail {
    let (l,c,edges,clipped,confidence)=auto_quality_metrics(src);
    if confidence<0.20 { return AutoDetail {
        sharpen_radius: 1.0, sharpen_detail:25.0,
        sharpen_masking: 80.0, nr_detail: 65.0,
        nr_color_detail:65.0, nr_color_smoothness:50.0,
        noise_score:l, confidence, ..Default::default()
    }; }
    let lum=((l-0.0018)*2500.0).clamp(0.0,28.0);
    let color=((c-0.0016)*2700.0).clamp(0.0,34.0);
    let noisy=lum>7.0 || color>8.0;
    let already_crisp=edges>0.105;
    // JPEG cameras pre-sharpen photos. Never sharpen through noise.
    let sharpen=if noisy || already_crisp || clipped>0.015 || confidence<0.35 {0.0}
        else if edges>0.043 {8.0} else {12.0};
    AutoDetail {
        nr_luminance: lum.round(),
        nr_color:color.round(),
        nr_detail: if noisy {75.0}else{65.0},
        nr_color_detail: if noisy {72.0}else{65.0},
        nr_color_smoothness:50.0,
        sharpen_amount: sharpen,
        sharpen_radius:0.8,
        sharpen_detail:15.0,
        sharpen_masking:85.0,
        noise_score:l,
        confidence,
    }
}

/// Conservatively suggest texture/clarity; global dehaze is deliberately 0.
/// Haze is an artistic/scene-geometry ambiguity, not inferable from contrast
/// alone. Manual controls remain available for real atmospheric haze.
pub fn auto_effects(src: &Rgb32f, _info: &SourceInfo) -> AutoEffects {
    let (noise,color,edges,clipped,confidence)=auto_quality_metrics(src);
    if confidence<0.25 {return AutoEffects::default();}
    let clean=noise<0.008 && color<0.010 && clipped<0.01;
    let texture=if clean && edges>0.016 && edges<0.105 {5.0} else {0.0};
    let clarity=if clean && edges>0.010 && edges<0.085 {3.0} else {0.0};
    AutoEffects {texture,clarity,dehaze:0.0,confidence}
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

/// Confidence report for an automatic WB decision. Safe to expose in the UI
/// as diagnostics; no calibrated "percent correct" claim is implied.
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
pub struct AutoWbReport {
    pub temp: f64,
    pub tint: f64,
    pub confidence: f64,
    pub neutral_fraction: f64,
    pub spatial_coverage: usize,
    pub camera_balance_retained: bool,
}
fn as_shot_report(info: &SourceInfo) -> AutoWbReport {
    AutoWbReport {
        temp: info.as_shot_temp, tint: info.as_shot_tint,
        confidence: 0.0, neutral_fraction: 0.0,
        spatial_coverage: 0, camera_balance_retained: true,
    }
}

/// Identify likely neutral *surfaces*, not merely average scene colour.
///
/// Real-world colour constancy is underdetermined. We accept neutral-looking
/// regions with mild illuminant tint, require support from multiple areas,
/// and bound the change even when the confidence is high. A saturated floral
/// or warm fire scene must retain camera colour rather than becoming grey.
pub fn auto_wb_report(src: &Rgb32f, info: &SourceInfo) -> AutoWbReport {
    let mut report=as_shot_report(info);
    let img=lightcraft_raster::resample::fit(
        src, 384, 384, lightcraft_raster::resample::Filter::Box);
    if img.width<24 || img.height<24 || img.data.is_empty() {return report;}
    let mut neutral=0usize;
    let mut eligible=0usize;
    let mut blocks=[0usize;16];
    let mut avg=[0.0f64;3];
    let mut total=0.0f64;
    let w=img.width;
    let h=img.height;
    for (index,c) in img.data.iter().enumerate() {
        let y=luminance_2020(*c);
        if !(0.05..=0.78).contains(&y) {continue;}
        eligible+=1;
        let hi=c[0].max(c[1]).max(c[2]).max(1e-6);
        let lo=c[0].min(c[1]).min(c[2]);
        let chroma=((hi-lo)/hi).clamp(0.0,1.0);
        // A mild cast can make a neutral wall a little warmer/cooler, but
        // do not accept strongly coloured petals, grass or skin as neutrals.
        if chroma>0.24 {continue;}
        let weight=((0.24-chroma)/0.24).powi(2) as f64;
        if weight<0.015 {continue;}
        let px=index%w;
        let py=index/w;
        let cell=((py*4/h).min(3))*4+((px*4/w).min(3));
        blocks[cell]+=1;
        for channel in 0..3 {avg[channel]+=c[channel] as f64*weight;}
        total+=weight;
        neutral+=1;
    }
    report.neutral_fraction=if eligible>0 {
        neutral as f64/eligible as f64
    } else {0.0};
    report.spatial_coverage=blocks.iter().filter(|&&n|n>15).count();
    if eligible<200 || neutral<120 || report.neutral_fraction<0.025
        || report.spatial_coverage<4 || total<15.0 { return report; }

    let mean=avg.map(|v|(v/total) as f32);
    let patch=Rgb32f::filled(16,16,mean);
    let (t,tint)=auto_wb_classic(&patch,info);
    if !t.is_finite() || !tint.is_finite() {return report;}

    let coverage=(report.spatial_coverage as f64/12.0).min(1.0);
    let fraction=(report.neutral_fraction/0.22).min(1.0);
    report.confidence=(coverage.sqrt()*fraction).clamp(0.0,1.0);
    if report.confidence<0.12 {return report;}

    let src_mired=1_000_000.0/info.as_shot_temp.clamp(2000.0,50000.0);
    let goal_mired=1_000_000.0/t.clamp(2000.0,50000.0);
    let limit_mired=if info.raw {60.0}else{38.0};
    let limit_tint=if info.raw {27.0}else{20.0};
    let delta=((goal_mired-src_mired)*report.confidence)
        .clamp(-limit_mired,limit_mired);
    report.temp=(1_000_000.0/(src_mired+delta))
        .clamp(2000.0,50000.0).round();
    report.tint=(info.as_shot_tint+
        ((tint-info.as_shot_tint)*report.confidence)
        .clamp(-limit_tint,limit_tint)).round();
    report.camera_balance_retained=
        (report.temp-info.as_shot_temp).abs()<1.0
        && (report.tint-info.as_shot_tint).abs()<1.0;
    report
}

/// White Balance Auto returns the report's actual, bounded correction.
pub fn auto_wb(src: &Rgb32f, info: &SourceInfo) -> (f64,f64) {
    let report=auto_wb_report(src,info);
    (report.temp,report.tint)
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
        assert!((1_000_000.0/temp - 1_000_000.0/info.as_shot_temp).abs()<=38.01);
        assert!((tint-info.as_shot_tint).abs()<=20.01);
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

#[cfg(test)]
mod studio_auto_detail_tests {
    use super::*;
    #[test]
    fn smooth_photo_gets_no_noise_reduction() {
        let i=Rgb32f::filled(128,128,[0.20,0.20,0.20]);
        let d=auto_detail(&i,&SourceInfo::default());
        assert_eq!(d.nr_luminance,0.0);
        assert_eq!(d.nr_color,0.0);
        assert!(d.sharpen_amount<=12.0);
        assert!(auto_effects(&i,&SourceInfo::default()).dehaze==0.0);
    }
    #[test]
    fn structured_edges_do_not_trigger_aggressive_denoising() {
        let img=Rgb32f::from_fn(128,128,|x,y|{
            if ((x/8 + y/8)%2)==0 {[0.1,0.1,0.1]}else{[0.70,0.70,0.70]}
        });
        let d=auto_detail(&img,&SourceInfo::default());
        assert!(d.nr_luminance<=28.0);
        assert!(d.sharpen_amount<=12.0);
    }

    #[test]
    fn noiseless_subject_not_forced_to_denoise() {
        let image=Rgb32f::from_fn(256,256,|x,y|{
           let v=if x<120 {0.18} else {0.38};
           let fade=if y<128 {0.0} else {0.02};
           [v+fade,v+fade,v+fade]
        });
        let d=auto_detail(&image,&SourceInfo::default());
        assert_eq!(d.nr_luminance,0.0,"noisy-looking clean edge: {d:?}");
        assert_eq!(d.nr_color,0.0,"chromatic noise on neutral blocks: {d:?}");
    }

    #[test]
    fn noisy_regions_do_not_get_extra_sharpening() {
        let img=Rgb32f::from_fn(128,128,|x,y|{
            let n=((((x*37+y*101+x*y*13)%257) as f32/257.0)-0.5)*0.09;
            [0.18+n,0.18-n*0.7,0.18+n*0.4]
        });
        let d=auto_detail(&img,&SourceInfo::default());
        assert!(d.nr_luminance>0.0, "luminance noise not detected: {d:?}");
        assert!(d.nr_color>0.0, "colour noise not detected: {d:?}");
        assert!(d.sharpen_masking>=80.0);
        assert!(auto_effects(&img,&SourceInfo::default()).dehaze==0.0);
    }
}

#[cfg(test)]
mod studio_v04_light_and_wb_tests {
    use super::*;
    #[test]
    fn fire_glows_without_global_highlight_compression() {
        let img=Rgb32f::from_fn(192,192,|x,y|{
            let flame=x>50 && x<122 && y>44 && y<83;
            if flame {[1.15,0.77,0.18]}
            else if x>34 && x<144 && y>84 && y<115 {[0.12,0.034,0.01]}
            else {[0.003,0.004,0.003]}
        });
        let a=smart_auto_tone(&img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(a.exposure.abs()<0.05,"fire exposure {a:?}");
        assert!(a.shadows>=9.0,"must lift recoverable wood rather than do nothing {a:?}");
        assert!(a.blacks>=-5.0,"deep black should remain black {a:?}");
        assert_eq!(a.highlights,0.0,"fire highlight protection {a:?}");
        assert_eq!(a.whites,0.0,"fire must stay luminous {a:?}");
        assert_eq!(a.saturation,0.0,"fire colour unchanged {a:?}");
    }
    #[test]
    fn naturally_balanced_scene_is_not_overboosted() {
        let img=Rgb32f::filled(128,128,[0.18,0.18,0.18]);
        let a=smart_auto_tone(&img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(a.exposure.abs()<=0.25,"neutral exposure {a:?}");
        assert_eq!(a.saturation,0.0);
        assert!(a.highlights>=-26.0);
    }
    #[test]
    fn grey_neutral_warm_cast_can_trigger_auto_wb() {
        let info=SourceInfo::default();
        let img=Rgb32f::filled(128,128,[0.245,0.225,0.205]);
        let r=auto_wb_report(&img,&info);
        assert!(!r.camera_balance_retained,"valid neutral not detected: {r:?}");
        assert!(r.confidence>0.20,"low-confidence WB {r:?}");
        assert!((r.tint-info.as_shot_tint).abs()<=20.01);
        assert!((1_000_000.0/r.temp - 1_000_000.0/info.as_shot_temp).abs()<=38.1);
    }
    #[test]
    fn coloured_rose_keeps_original_wb() {
        let info=SourceInfo::default();
        let img=Rgb32f::from_fn(128,128,|x,y|{
            if (x+y)%3==0 {[0.75,0.04,0.09]}else{[0.05,0.31,0.04]}
        });
        let r=auto_wb_report(&img,&info);
        assert!(r.camera_balance_retained,"coloured flower got WB shift: {r:?}");
    }
    #[test]
    fn empty_night_scene_is_not_brightened() {
        let img=Rgb32f::from_fn(192,192,|x,y|{
            if (x>40&&x<110)&&(y>40&&y<80) { [1.1,0.8,0.4] }
            else { [0.003,0.004,0.003] }
        });
        let a=smart_auto_tone(&img,&SourceInfo::default(),&DevelopSettings::default());
        assert_eq!(a.exposure,0.0);
        assert_eq!(a.shadows,0.0,"no recoverable subject; prevent black noise lift");
        assert_eq!(a.highlights,0.0);
    }
}
