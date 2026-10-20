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
    /// Parametric-curve lift for dark textured subject matter.
    /// Unlike the global Shadows control, it acts on the visible lower
    /// midtones of developed JPEGs while retaining bright emitters.
    pub curve_shadows: f64,
    pub curve_darks: f64,
    /// Automatic encoded-domain upper-midtones and highlight protection.
    pub curve_lights: f64,
    pub curve_highlights: f64,
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
        curve_shadows: 0.0,
        curve_darks: 0.0,
        curve_lights: 0.0,
        curve_highlights: 0.0,
    }
}

/// Image-adaptive automatic development for photographs already rendered
/// by a camera. Analyse regional *structure* as well as scene-wide brightness:
/// the median of a dark sky is not the exposure of its fire, while a bright
/// cloud should not freeze corrections to foreground landscape details.
///
/// The model estimates parameters that the real render pipeline understands.
/// It uses NO Adobe assets/models or claimed semantic/object recognition.
pub fn smart_auto_tone(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings) -> AutoTone {
    let mut img=lightcraft_raster::resample::fit(
        src,512,512,lightcraft_raster::resample::Filter::Box);
    let base=DevelopSettings { wb:s.wb, ..DevelopSettings::default() };
    crate::local::scene_linear_pre(&mut img,info,&base);
    if img.data.is_empty() || img.width==0 {return AutoTone::default();}

    let lum:Vec<f32>=img.data.iter()
        .map(|c|luminance_2020(*c).max(0.0)).collect();
    let mut ev:Vec<f32>=lum.iter()
        .map(|&y|(y.max(1e-6)/0.18).log2()).collect();
    ev.sort_by(|a,b|a.total_cmp(b));
    let (median,p05,p95,p995)=(
        percentile(&ev,0.50) as f64,
        percentile(&ev,0.05) as f64,
        percentile(&ev,0.95) as f64,
        percentile(&ev,0.995) as f64
    );
    let mut dark=0usize;
    let mut bright=0usize;
    let mut recoverable=0usize;
    let mut colour_eligible=0usize;
    let mut coloured=0usize;
    let mut soft_colour=0usize;
    let mut rich_colour=0usize;
    let mut skin_like_colour=0usize;
    let mut colour_mass=0.0f64;
    let mut structured=Vec::new();
    for (i,&y) in lum.iter().enumerate() {
        if y<0.028 {dark+=1;}
        if y>0.70 {bright+=1;}
        if (0.035..0.28).contains(&y) {recoverable+=1;}
        let c=img.data[i];
        if (0.025..0.80).contains(&y) {
            // Work on visible, unclipped colours, not dark-noise chroma or
            // small bright lamps. Relative channel spread is only a proxy
            // for existing colourfulness, not an object/skin classifier.
            let mx=c[0].max(c[1]).max(c[2]).max(1e-6);
            let mn=c[0].min(c[1]).min(c[2]);
            let spread=((mx-mn)/mx).clamp(0.0,1.0) as f64;
            colour_eligible+=1;
            colour_mass+=spread;
            if spread>0.065 {coloured+=1;}
            if (0.075..0.45).contains(&spread) {soft_colour+=1;}
            if spread>0.66 {rich_colour+=1;}
            // Extra protection for warm, moderate-chroma regions that may
            // contain faces. A heuristic cap, NOT facial recognition.
            if (0.12..0.55).contains(&spread)
                && c[0]>c[1]*1.08 && c[1]>c[2]*1.02
                && c[0]<c[2]*2.10 {
                skin_like_colour+=1;
            }
        }
        // Edges and textured midtones act as a proxy for actual subject
        // detail; flat backgrounds are deliberately downweighted.
        if i>=img.width && i%img.width>0 && (0.028..0.8).contains(&y) {
            let left=lum[i-1];
            let top=lum[i-img.width];
            let contrast=((y-left).abs()+(y-top).abs())
                /(0.03+y+left+top);
            if contrast>0.07 {structured.push((y.max(1e-6)/0.18).log2());}
        }
    }
    let n=lum.len() as f64;
    let shadow_fraction=dark as f64/n;
    let bright_fraction=bright as f64/n;
    let recoverable_fraction=recoverable as f64/n;
    let structure_fraction=structured.len() as f64/n;
    structured.sort_by(|a,b|a.total_cmp(b));
    let subject_ev=if structured.len()>100 {
        percentile(&structured,0.5) as f64
    } else {median};

    // Emissive highlights on dark: lower threshold than v0.5.1. Real
    // photographed flames are often below 0.2% of all pixels despite
    // dominating the eye. Do not use global exposure/highlight compression.
    let intentional_light=shadow_fraction>0.50 && median< -2.25
        && p995>1.2 && p995-median>4.0
        && (bright_fraction>0.0005 || p995>1.9)
        && bright_fraction<0.35;
    if intentional_light {
        let visible_wood=recoverable_fraction>0.012;
        return AutoTone {
            exposure:0.0,contrast:0.0,highlights:0.0,
            shadows:if visible_wood {
                (26.0+145.0*recoverable_fraction).clamp(26.0,48.0).round()
            }else{0.0},
            whites:0.0,blacks:if visible_wood {-4.0}else{0.0},
            vibrance:0.0,saturation:0.0,
            curve_shadows:if visible_wood {30.0}else{0.0},
            curve_darks:if visible_wood {42.0}else{0.0},
            curve_lights:0.0,curve_highlights:0.0,
        };
    }

    // Global exposure considers textured subjects AND overall key.
    // Previously "p99.5 > 1.55" could forcibly set an otherwise useful
    // positive correction to zero on *any* JPEG with white clouds.
    let proposed=(-0.15-subject_ev)*0.16+(-0.40-median)*0.15;
    let balanced=median.abs()<0.40 && structure_fraction<0.09;
    let high_key=median>0.75 && bright_fraction>0.22;
    let low_key=median< -2.5 && bright_fraction<0.01;
    let mut exposure=proposed.clamp(-0.18,0.36);
    if high_key {exposure=exposure.max(-0.10).min(0.02);}
    if low_key {exposure=exposure.min(0.18);}
    if p995>2.25 {exposure=exposure.min(0.18);}
    if balanced {exposure=exposure.clamp(-0.10,0.12);}
    if exposure<0.0 && median<0.55 {exposure=exposure.max(-0.06);}

    // Soft highlight protection follows the FINAL exposure setting. It is
    // not a request to make naturally bright objects grey.
    let highlights=if p995+exposure>1.95 {
        -((p995+exposure-1.95)*20.0).clamp(0.0,19.0).round()
    }else{0.0};
    let tonal_structure=(structure_fraction/0.24).min(1.0);
    let local_opportunity=if recoverable_fraction>0.09 {
        ((recoverable_fraction-0.09)/0.30).clamp(0.0,1.0)
    }else{0.0};
    let lift_strength=local_opportunity*(0.50+0.50*tonal_structure);
    let natural_tone= !high_key && !low_key && !balanced;
    let shadows=if natural_tone {
        (6.0+16.0*lift_strength+5.0*shadow_fraction).clamp(0.0,26.0).round()
    }else{0.0};
    let curve_darks=if natural_tone {
        (9.0+14.0*lift_strength).clamp(0.0,23.0).round()
    }else{0.0};
    let curve_shadows=if natural_tone {
        (6.0+12.0*lift_strength).clamp(0.0,18.0).round()
    }else{0.0};
    let dynamic_range=p95-p05;
    let contrast=((5.5-dynamic_range)*1.2).clamp(-6.0,8.0).round();

    // Auto Curve uses all four existing *parametric* regions. Keep a
    // photographer's RGB point curves intact. Upper-midtones gain a little
    // luminosity only when they are genuinely subdued; the top region is
    // gently rolled off only where bright non-emissive content supports it.
    // The earlier fire exit intentionally bypasses these two operations.
    let p75=percentile(&ev,0.75) as f64;
    let curve_lights=if natural_tone && p75<0.95 && p95<2.1 {
        (5.0+9.0*((0.95-p75)/2.0).clamp(0.0,1.0)).round()
    }else{0.0};
    let curve_highlights=if !high_key && !low_key
        && p95>1.30 && bright_fraction>0.012 {
        -(3.0+7.0*((p95-1.30)/1.8).clamp(0.0,1.0)).round()
    }else{0.0};

    // Automatic colour is a *separate* decision from Auto WB. Work out
    // whether there is recoverable low-chroma colour, or whether the camera
    // has already rendered strongly coloured flowers/sunsets. Unlike v0.6,
    // an ordinary photograph is not hard-coded to Saturation=0.
    let eligible=colour_eligible.max(1) as f64;
    let colour_fraction=coloured as f64/eligible;
    let soft_fraction=soft_colour as f64/eligible;
    let rich_fraction=rich_colour as f64/eligible;
    let skin_fraction=skin_like_colour as f64/eligible;
    let mean_sat=colour_mass/eligible;
    let chroma_need=((0.38-mean_sat)/0.38).clamp(0.0,1.0);
    let has_colour=colour_fraction>0.16 && colour_eligible as f64>n*0.10;
    let mut vibrance=if has_colour {
        ((9.0+12.0*soft_fraction+7.0*chroma_need)
            *(1.0-1.35*rich_fraction).clamp(0.0,1.0))
            .clamp(0.0,22.0)
    }else{0.0};
    let mut saturation=if has_colour && soft_fraction>0.14
        && rich_fraction<0.24 {
        (2.0+3.0*soft_fraction+2.0*chroma_need).clamp(0.0,6.0)
    }else{0.0};
    if skin_fraction>0.20 {
        vibrance=vibrance.min(12.0);
        saturation=saturation.min(2.0);
    }
    if high_key {
        vibrance*=0.70;
        saturation*=0.70;
    }

    AutoTone {
        exposure:(exposure*100.0).round()/100.0,
        contrast:if balanced {0.0}else{contrast},
        highlights,shadows,whites:0.0,
        blacks:if natural_tone && shadow_fraction>0.25 {-3.0}else{0.0},
        vibrance:vibrance.round(),saturation:saturation.round(),
        curve_shadows,curve_darks,curve_lights,curve_highlights,
    }
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

/// Diagnostic details for scene white-balance, not a claim of ground-truth
/// illuminant recovery. The user can always override it with the picker.
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
pub struct AutoWbReport {
    pub temp:f64,
    pub tint:f64,
    pub confidence:f64,
    pub neutral_fraction:f64,
    pub spatial_coverage:usize,
    pub camera_balance_retained:bool,
    /// "corrected", "already_neutral", "ambiguous" or "no_neutral".
    pub decision:&'static str,
    pub candidate_temp:f64,
    pub candidate_tint:f64,
}
fn as_shot_report(info:&SourceInfo)->AutoWbReport{
    AutoWbReport {
        temp:info.as_shot_temp,tint:info.as_shot_tint,
        confidence:0.0,neutral_fraction:0.0,spatial_coverage:0,
        camera_balance_retained:true,decision:"no_neutral",
        candidate_temp:info.as_shot_temp,candidate_tint:info.as_shot_tint,
    }
}

/// Weighted neutral-candidate consensus in 4x4 spatial cells.
/// One large blue wall or pink flower should not dictate the entire image's
/// illuminant. Supporting neutral clusters, even when only ~2% of pixels,
/// can suggest a modest correction if they agree spatially. This does not
/// treat grey-world colour averaging as semantic scene understanding.
pub fn auto_wb_report(src:&Rgb32f,info:&SourceInfo)->AutoWbReport{
    let mut report=as_shot_report(info);
    let img=lightcraft_raster::resample::fit(
        src,384,384,lightcraft_raster::resample::Filter::Box);
    if img.width<24 || img.height<24 || img.data.is_empty(){return report;}
    let (w,h)=(img.width,img.height);
    let mut eligible=0usize;
    let mut neutral=0usize;
    let mut sum=[0.0f64;3];
    let mut weight_sum=0.0f64;
    let mut cell_rgb=[[0.0f64;3];16];
    let mut cell_weights=[0.0f64;16];
    let mut cell_counts=[0usize;16];
    let mut strong_colour=0usize;
    for (index,c) in img.data.iter().enumerate(){
        let y=luminance_2020(*c);
        if !(0.05..=0.78).contains(&y){continue;}
        eligible+=1;
        let hi=c[0].max(c[1]).max(c[2]).max(1e-6);
        let lo=c[0].min(c[1]).min(c[2]);
        let chroma=((hi-lo)/hi).clamp(0.0,1.0);
        if chroma>0.48{strong_colour+=1;}
        // Keep stricter chroma criteria to avoid mistaking coloured petals
        // and blue sky for a neutral surface; improve *confidence* rather
        // than relaxing chroma until everything is "neutral".
        if chroma>0.24{continue;}
        let weight=((0.24-chroma)/0.24).powi(2) as f64;
        if weight<0.015{continue;}
        let x=index%w;
        let y=index/w;
        let cell=((y*4/h).min(3))*4+((x*4/w).min(3));
        for channel in 0..3 {
            sum[channel]+=c[channel] as f64*weight;
            cell_rgb[cell][channel]+=c[channel] as f64*weight;
        }
        cell_weights[cell]+=weight;
        cell_counts[cell]+=1;
        weight_sum+=weight;
        neutral+=1;
    }
    report.neutral_fraction=if eligible>0{
        neutral as f64/eligible as f64
    }else{0.0};
    report.spatial_coverage=cell_counts.iter()
        .filter(|&&n|n>15).count();
    if eligible<200 || neutral<100 || report.spatial_coverage<3
        || weight_sum<13.0 || report.neutral_fraction<0.012 {
        return report;
    }
    let mean=sum.map(|v|(v/weight_sum) as f32);
    let patch=Rgb32f::filled(16,16,mean);
    let (t,tint)=auto_wb_classic(&patch,info);
    if !t.is_finite() || !tint.is_finite(){return report;}
    report.candidate_temp=t;
    report.candidate_tint=tint;

    // Agreement between independent spatial neutral candidates limits the
    // influence of neutral-ish but intrinsically coloured objects.
    let mut variance=0.0f64;
    let mut votes=0usize;
    let baseline_r=(mean[0].max(1e-6)/mean[1].max(1e-6)).ln() as f64;
    let baseline_b=(mean[2].max(1e-6)/mean[1].max(1e-6)).ln() as f64;
    for cell in 0..16 {
        if cell_counts[cell]<16 || cell_weights[cell]<2.0 {continue;}
        let c=cell_rgb[cell].map(|v|v/cell_weights[cell]);
        let lr=(c[0].max(1e-6)/c[1].max(1e-6)).ln();
        let lb=(c[2].max(1e-6)/c[1].max(1e-6)).ln();
        variance+=(lr-baseline_r).powi(2)+(lb-baseline_b).powi(2);
        votes+=1;
    }
    let consistency=if votes>0 {
        (1.0+18.0*variance/votes as f64).recip()
    } else {0.0};
    let density=(report.neutral_fraction/0.14).sqrt().min(1.0);
    let coverage=(report.spatial_coverage as f64/10.0).sqrt().min(1.0);
    let saturation=if eligible>0{
        strong_colour as f64/eligible as f64
    }else{0.0};
    let colour_protection=if saturation>0.55 && report.neutral_fraction<0.035 {
        0.70
    }else{1.0};
    report.confidence=(density*coverage*consistency*colour_protection)
        .clamp(0.0,1.0);
    if report.confidence<0.14 {
        report.decision="ambiguous";
        return report;
    }

    // JPEG colour balance is *relative* to the camera-rendered look, not an
    // absolute measured illuminant. Stay within bounded reciprocal-K changes.
    let source_mired=1_000_000.0/info.as_shot_temp.clamp(2000.0,50000.0);
    let desired_mired=1_000_000.0/t.clamp(2000.0,50000.0);
    let limit_mired=if info.raw {65.0}else{45.0};
    let limit_tint=if info.raw {27.0}else{22.0};
    let diff=((desired_mired-source_mired)*report.confidence)
        .clamp(-limit_mired,limit_mired);
    report.temp=(1_000_000.0/(source_mired+diff))
        .clamp(2000.0,50000.0).round();
    report.tint=(info.as_shot_tint+
        ((tint-info.as_shot_tint)*report.confidence)
        .clamp(-limit_tint,limit_tint)).round();
    report.camera_balance_retained=
        (report.temp-info.as_shot_temp).abs()<1.0 &&
        (report.tint-info.as_shot_tint).abs()<1.0;
    report.decision=if report.camera_balance_retained{
        "already_neutral"
    }else{"corrected"};
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
        assert!(a.shadows>=26.0,"must lift recoverable wood visibly {a:?}");
        assert!(a.curve_shadows>=30.0,"needed lower-shadow tonal recovery {a:?}");
        assert!(a.curve_darks>=42.0,"needed visible dark-midtones lift {a:?}");
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

#[cfg(test)]
mod studio_v051_visible_auto_tests {
    use super::*;
    use lightcraft_develop::ToneCurve;

    #[test]
    fn auto_dark_curve_lifts_wood_but_preserves_flames() {
        let img=Rgb32f::from_fn(192,192,|x,y|{
            if x>40 && x<120 && y>30 && y<73 {[1.20,0.78,0.20]}
            else if x>26 && x<149 && y>72 && y<133 {[0.16,0.058,0.021]}
            else {[0.003,0.003,0.004]}
        });
        let auto=smart_auto_tone(&img,&SourceInfo::default(),&DevelopSettings::default());
        let curve=ToneCurve {
            shadows:auto.curve_shadows,
            darks:auto.curve_darks,
            ..ToneCurve::default()
        };
        let lut=crate::finish::parametric_curve(&curve,1024);
        assert!(lut.eval(0.20)>0.20+0.025,
            "visible bark/ground midtones should brighten: {}",lut.eval(0.20));
        assert!(lut.eval(0.38)>0.38+0.06,
            "visible midtones should brighten: {}",lut.eval(0.38));
        assert!((lut.eval(0.91)-0.91).abs()<0.004,
            "flame highlights must remain anchored: {}",lut.eval(0.91));
        assert!((lut.eval(0.002)-0.002).abs()<0.003,
            "unlit black remains black: {}",lut.eval(0.002));
        assert!(auto.exposure.abs()<0.05 && auto.highlights==0.0 && auto.whites==0.0);
    }

    #[test]
    fn no_wood_no_added_dark_curve() {
        let img=Rgb32f::from_fn(192,192,|x,y|{
            if x>60 && x<102 && y>40 && y<76 {[1.3,0.8,0.20]}
            else {[0.002,0.002,0.002]}
        });
        let a=smart_auto_tone(&img,&SourceInfo::default(),&DevelopSettings::default());
        assert_eq!(a.curve_shadows,0.0);
        assert_eq!(a.curve_darks,0.0);
    }

    #[test]
    fn normal_photo_has_no_automatic_fire_curve() {
        let img=Rgb32f::from_fn(128,128,|x,y|{
            let v=0.12+(x+y) as f32/256.0*0.4;[v,v,v]
        });
        let a=smart_auto_tone(&img,&SourceInfo::default(),&DevelopSettings::default());
        // Ordinary photographs may receive a *different, smaller*
        // regional correction than fire; the fire-strength curve is
        // reserved for self-luminous night scenes.
        assert!(a.curve_shadows<=18.0,"unexpected night-strength curve {a:?}");
        assert!(a.curve_darks<=23.0,"unexpected night-strength curve {a:?}");
    }
}

#[cfg(test)]
mod studio_v06_adaptive_tests {
    use super::*;

    #[test]
    fn jpeg_white_highlights_do_not_veto_all_positive_exposure() {
        // Strong highlights, dark structured foliage, white cloud: previous
        // p99.5 exposure cap returned exactly zero.
        let image=Rgb32f::from_fn(192,144,|x,y|{
            if y<54 {[0.82,0.87,0.97]}
            else if x>40 && x<148 {[0.06+(x%9) as f32*0.005,0.12,0.05]}
            else {[0.025,0.055,0.028]}
        });
        let a=smart_auto_tone(&image,&SourceInfo::default(),&DevelopSettings::default());
        assert!(a.exposure>0.0,"white sky vetoed regional exposure: {a:?}");
        assert!(a.exposure<0.25,"risk of blowing white sky: {a:?}");
        assert!(a.curve_darks>0.0,"missing regional dark-ground recovery: {a:?}");
    }

    #[test]
    fn tinted_neutral_clusters_receive_a_reported_correction() {
        let image=Rgb32f::from_fn(192,144,|x,y|{
            if x%11<7 && y%9<7 {[0.245,0.225,0.205]}
            else {[0.3,0.3,0.3]}
        });
        let info=SourceInfo::default();
        let r=auto_wb_report(&image,&info);
        assert_eq!(r.decision,"corrected","neutral WB: {r:?}");
        assert!(r.confidence>0.14,"low confidence: {r:?}");
        assert!((1e6/r.temp-1e6/info.as_shot_temp).abs()<=45.1);
    }

    #[test]
    fn highly_saturated_flower_does_not_get_forced_white_balance() {
        let image=Rgb32f::from_fn(128,128,|x,y|{
            if (x+y)%7==0 {[0.82,0.035,0.19]}
            else{[0.02,0.35,0.04]}
        });
        let r=auto_wb_report(&image,&SourceInfo::default());
        assert!(r.camera_balance_retained,"petals incorrectly neutralised: {r:?}");
    }

    #[test]
    fn luminescent_subject_keeps_emission() {
        let image=Rgb32f::from_fn(256,256,|x,y|{
            if x>60 && x<95 && y>60 && y<120 {[1.35,0.76,0.12]}
            else if x>52 && x<145 && y>125 && y<175 {[0.1,0.04,0.014]}
            else {[0.003,0.003,0.003]}
        });
        let a=smart_auto_tone(&image,&SourceInfo::default(),&DevelopSettings::default());
        assert_eq!(a.exposure,0.0,"fire exposure should not be raised");
        assert_eq!(a.highlights,0.0,"flames should not be dimmed");
        assert!(a.curve_darks>0.0,"recoverable charcoal");
    }
}

#[cfg(test)]
mod studio_v061_auto_colour_and_curve_tests {
    use super::*;
    use lightcraft_develop::ToneCurve;

    fn muted_landscape() -> Rgb32f {
        Rgb32f::from_fn(160, 112, |x, y| {
            let grad = (x % 40) as f32 * 0.00045;
            if y < 46 {
                [0.20 + grad, 0.24 + grad, 0.28 + grad]
            } else if (x / 20) % 2 == 0 {
                [0.060 + grad, 0.095 + grad, 0.070 + grad]
            } else {
                [0.090 + grad, 0.105 + grad, 0.072 + grad]
            }
        })
    }

    #[test]
    fn auto_colour_is_visible_on_subdued_landscape() {
        let a = smart_auto_tone(&muted_landscape(), &SourceInfo::default(), &DevelopSettings::default());
        assert!(a.vibrance >= 12.0 && a.vibrance <= 22.0, "need useful, limited auto vibrance: {a:?}");
        assert!(a.saturation >= 2.0 && a.saturation <= 6.0, "saturation must not be hard-coded off: {a:?}");
        assert!(a.curve_lights > 0.0, "missing auto lights curve: {a:?}");
    }

    #[test]
    fn already_rich_flowers_are_not_overcooked() {
        let image = Rgb32f::from_fn(160, 112, |x, y| {
            if ((x / 16) + (y / 16)) % 3 == 0 { [0.75, 0.035, 0.20] }
            else { [0.04, 0.34, 0.045] }
        });
        let a = smart_auto_tone(&image, &SourceInfo::default(), &DevelopSettings::default());
        assert_eq!(a.saturation, 0.0, "no global colour boost on vivid petals: {a:?}");
        assert!(a.vibrance <= 3.0, "petals already have sufficient chroma: {a:?}");
    }

    #[test]
    fn true_grey_remains_without_auto_colour() {
        let image=Rgb32f::from_fn(160, 112, |x, y| {
            let v=0.06 + x as f32 * 0.001 + y as f32 * 0.0004;
            [v,v,v]
        });
        let a=smart_auto_tone(&image,&SourceInfo::default(),&DevelopSettings::default());
        assert_eq!((a.vibrance,a.saturation),(0.0,0.0));
    }

    #[test]
    fn all_parametric_auto_curve_regions_are_wired() {
        let a=smart_auto_tone(&muted_landscape(),&SourceInfo::default(),&DevelopSettings::default());
        let curve=ToneCurve {
            shadows:a.curve_shadows,
            darks:a.curve_darks,
            lights:a.curve_lights,
            highlights:a.curve_highlights,
            ..ToneCurve::default()
        };
        let lut=crate::finish::parametric_curve(&curve,1024);
        assert!(lut.eval(0.63)>0.63+0.002,"underused upper midtones: {}",lut.eval(0.63));
        assert!((lut.eval(0.995)-0.995).abs()<0.005,"must anchor bright white: {}",lut.eval(0.995));
        assert!((lut.eval(0.002)-0.002).abs()<0.003,"must anchor true black: {}",lut.eval(0.002));
    }

    #[test]
    fn auto_colour_and_curves_each_change_rendered_pixels() {
        use crate::{RenderRequest, render};
        let src=muted_landscape();
        let info=SourceInfo::default();
        let a=smart_auto_tone(&src,&info,&DevelopSettings::default());
        for edge in [80,160] {
            let req=RenderRequest::fit(edge,edge);
            let base=render(&src,&info,&DevelopSettings::default(),&req).image;
            for (name,value) in [("vibrance",a.vibrance),("saturation",a.saturation),
                                 ("curve_lights",a.curve_lights)] {
                assert!(value>0.0,"expected positive {name}: {a:?}");
                let mut s=DevelopSettings::default();
                match name {
                    "vibrance"=>s.color.vibrance=value,
                    "saturation"=>s.color.saturation=value,
                    _=>s.curve.lights=value,
                }
                let edited=render(&src,&info,&s,&req).image;
                let changed=base.data.iter().zip(edited.data.iter())
                    .filter(|(p,q)|p[0..3]!=q[0..3]).count();
                assert!(changed>base.len()/25,"{name} produced too few changed pixels at {edge}px: {changed}/{}",base.len());
            }
        }
    }
}
