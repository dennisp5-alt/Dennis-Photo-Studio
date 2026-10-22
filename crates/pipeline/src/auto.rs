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
fn smart_auto_candidate(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings) -> (AutoTone,bool) {
    smart_auto_candidate_mode(src,info,s,false)
}

/// All-guards-off test uses the same Auto measurements, but disables
/// photographic scene/veto rules. Never used by the production function.
fn smart_auto_candidate_mode(
    src:&Rgb32f, info:&SourceInfo, s:&DevelopSettings, no_guards:bool
) -> (AutoTone,bool) {
    let mut img=lightcraft_raster::resample::fit(
        src,512,512,lightcraft_raster::resample::Filter::Box);
    let base=DevelopSettings { wb:s.wb, ..DevelopSettings::default() };
    crate::local::scene_linear_pre(&mut img,info,&base);
    if img.data.is_empty() || img.width==0 {return (AutoTone::default(),false);}

    // Evaluate all nine image regions before deciding on global sliders.
    // Unlike full-frame percentiles, spatial evidence can detect a dark
    // detailed subject within otherwise bright surroundings.
    let zonal=crate::scene::analyse_linear(&img);
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
    let mut clipped_colour=0usize;
    let mut warm_emitters=0usize;
    let mut structured=Vec::new();
    for (i,&y) in lum.iter().enumerate() {
        if y<0.028 {dark+=1;}
        if y>0.70 {bright+=1;}
        if (0.035..0.28).contains(&y) {recoverable+=1;}
        let c=img.data[i];
        // A genuine bright, warm emitter is distinct from white petals,
        // moonlight, lit faces or fireworks on an otherwise dark frame.
        if y>0.45 && c[0]>0.60 && c[0]>c[1]*1.45
            && c[1]>c[2]*1.65 { warm_emitters+=1; }
        if (0.025..0.80).contains(&y) {
            // Work on visible, unclipped colours, not dark-noise chroma or
            // small bright lamps. Relative channel spread is only a proxy
            // for existing colourfulness, not an object/skin classifier.
            let mx=c[0].max(c[1]).max(c[2]).max(1e-6);
            let mn=c[0].min(c[1]).min(c[2]);
            let spread=((mx-mn)/mx).clamp(0.0,1.0) as f64;
            colour_eligible+=1;
            colour_mass+=spread;
            if c.iter().any(|&v| v>=0.985) { clipped_colour+=1; }
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
    // Do not apply the large fire/charcoal lift to ordinary bright subjects
    // merely because their background is dark. Preserve the earlier fire
    // behaviour only with supporting warm-emitter pixels.
    if !no_guards && intentional_light && warm_emitters as f64 / n > 0.002 {
        let visible_wood=recoverable_fraction>0.012;
        return (AutoTone {
            exposure:0.0,contrast:0.0,highlights:0.0,
            shadows:if visible_wood {
                (26.0+145.0*recoverable_fraction).clamp(26.0,48.0).round()
            }else{0.0},
            whites:0.0,blacks:if visible_wood {-4.0}else{0.0},
            vibrance:0.0,saturation:0.0,
            curve_shadows:if visible_wood {30.0}else{0.0},
            curve_darks:if visible_wood {42.0}else{0.0},
            curve_lights:0.0,curve_highlights:0.0,
        },true);
    }

    // v0.6.4: structure measures *subject evidence*, never exposure
    // correctness. Fine texture by itself is not an exposure defect.
    // The two tails and meaningful midtones must agree before a correction.
    let high_key=!no_guards && median>0.75 && bright_fraction>0.22;
    let low_key=!no_guards && ((median < -2.5 && bright_fraction<0.015)
        || (shadow_fraction>0.64 && median< -2.1 && bright_fraction<0.35)
        // A well-lit subject in a purposely dark setting should not be
        // normalized to grey merely because the background owns the median.
        || (median< -1.6 && p95> -1.1 && p95<0.55 && p95-median>1.3
            && bright_fraction<0.10));
    let is_shadow_deficit=(no_guards && median< -0.05) || (!low_key && median< -0.65
        && (subject_ev< -0.75 || median< -1.25));
    let is_bright_deficit=(no_guards && median>0.05) || (median>0.85 && subject_ev>0.45
        && (bright_fraction>0.12 || p95>2.0));
    let exposure=if is_shadow_deficit {
        let need=(0.26*(-0.60-median).max(0.0)
            +0.30*(-0.80-subject_ev).max(0.0)).clamp(0.0,if no_guards {2.0} else {0.46});
        if no_guards || need>=0.065 {need}else{0.0}
    }else if is_bright_deficit {
        let need=(0.30*(median-0.85).max(0.0)
            +0.25*(subject_ev-0.45).max(0.0)).clamp(0.0,if no_guards {2.0} else {0.35});
        if no_guards || need>=0.065 {-need}else{0.0}
    }else{0.0};
    let exposure=if !no_guards && exposure>0.0 && p995>2.25 {
        exposure.min(0.18)
    }else{exposure};
    let exposure=(exposure*100.0).round()/100.0;

    let dynamic_range=p95-p05;
    let protected_warm_colour=!no_guards && colour_eligible>100
        && colour_mass/colour_eligible as f64>0.20
        && rich_colour as f64/colour_eligible as f64>0.09;
    // Shadow recovery requires *three* signals: deeply compressed dark
    // values, actual recoverable mid-shadow data, and a dark subject.
    // This avoids lifting a deliberately deep sunset or night portrait
    // merely because the photo has plenty of visible texture.
    let mut shadow_need=if low_key || protected_warm_colour {
        0.0
    }else{
        let compressed=((-3.0-p05)/2.0).clamp(0.0,1.0)
            * ((recoverable_fraction-0.15)/0.29).clamp(0.0,1.0)
            * ((-0.45-subject_ev)/1.5).clamp(0.0,1.0);
        // Bright sky with an underlit, recoverable foreground can need
        // *local* recovery even where global exposure would blow clouds.
        let backlit=if bright_fraction>0.12 && median< -0.62
            && p95>1.35 && recoverable_fraction>0.20 {
            ((bright_fraction-0.12)/0.35).clamp(0.0,1.0)
            * ((-0.30-median)/1.2).clamp(0.0,1.0)
            * ((recoverable_fraction-0.20)/0.30).clamp(0.0,1.0)
            * 1.00
        }else{0.0};
        compressed.max(backlit)
    };
    let mut backlit_daylight_scene=false;
    // Backlit daylight photographs can have correctly exposed blue skies
    // and a dark but recoverable foreground. Global Exposure is unsafe
    // for the sky, so favour small editable Shadows/Curve corrections.
    // The warm-sky and lamp guards prevent mistaking sunsets/fire for
    // a failed landscape exposure.
    if img.height>=24 && img.width>=24 {
        let third=(img.height/3).max(1);
        let top_len=third*img.width;
        let bottom_start=(img.height-third)*img.width;
        let top_bright=lum[..top_len].iter()
            .filter(|&&y|y>0.65).count() as f64/top_len as f64;
        let top_warm=img.data[..top_len].iter()
            .filter(|c|c[0]>c[1]*1.25 && c[1]>c[2]*1.10)
            .count() as f64/top_len as f64;
        let foreground=&lum[bottom_start..];
        let foreground_useful=foreground.iter()
            .filter(|&&y|(0.035..0.28).contains(&y)).count() as f64
            /foreground.len() as f64;
        let mut foreground_sorted=foreground.to_vec();
        foreground_sorted.sort_by(|a,b|a.total_cmp(b));
        let foreground_median=percentile(&foreground_sorted,0.5) as f64;
        if top_bright>0.27 && top_warm<0.12
            && foreground_useful>0.30 && foreground_median<0.085
            && warm_emitters as f64/n<0.002 && !protected_warm_colour {
            backlit_daylight_scene=true;
            shadow_need=shadow_need.max(0.37);
        }
    }
    let shadows=if shadow_need>0.20 {
        (shadow_need*27.0).round()
    }else{0.0};
    let curve_shadows=if shadow_need>0.24 {
        (shadow_need*20.0).round()
    }else{0.0};
    let curve_darks=if shadow_need>0.20 {
        (shadow_need*26.0).round()
    }else{0.0};
    // A high-key reflective background does not mean that an internally
    // textured dark subject is properly developed. Keep global exposure
    // stable; let genuine detail regions justify a modest Shadows/Curve
    // proposal. The established render guard may further reduce it.
    //
    // A bright label on detailed dark fabric can justify a still smaller
    // shadows-only correction despite a globally low-key histogram.
    // Flat night backgrounds have no detailed shadow evidence.
    let allow_zonal=no_guards || (warm_emitters as f64/n<0.002
        && !protected_warm_colour && !backlit_daylight_scene);
    let zonal_shadow=if allow_zonal
        && (zonal.bright_surround_dark_detail
            || zonal.dark_texture_with_bright_reference) {
        zonal.proposed_shadow_recovery
    }else{0.0};
    let zonal_darks=if zonal_shadow>0.0 {
        zonal.proposed_darks_curve
    }else{0.0};
    let shadows=shadows.max(zonal_shadow);
    let curve_darks=curve_darks.max(zonal_darks);

    // Midtone luminance repair is separate from shadow lift. A sunset with
    // already appropriate upper midtones should not get a default +5.
    let p75=percentile(&ev,0.75) as f64;
    let light_need=if low_key || protected_warm_colour {0.0}else{
        ((-0.26-p75)/1.3).clamp(0.0,1.0)
        * ((-0.40-median)/1.3).clamp(0.0,1.0)
    };
    let curve_lights=if light_need>0.23 {
        (light_need*14.0).round()
    }else{0.0};

    // Shadows and highlights may need correction in either direction, but
    // genuine high-key whites and emissive subjects must retain their mood.
    let highlight_need=if p995>2.35 && bright_fraction>0.06 {
        ((p995-2.35)/1.8).clamp(0.0,1.0)
    }else{0.0};
    let highlights=if highlight_need>0.18 && !high_key {
        -(highlight_need*17.0).round()
    }else{0.0};
    let curve_highlights=if highlight_need>0.28 && !high_key {
        -(highlight_need*10.0).round()
    }else{0.0};
    // Contrast only where a compressed tonal range and actual internal
    // structure support improvement. No fixed base S-curve.
    let contrast=if dynamic_range<3.7 && structure_fraction>0.035
        && !low_key && !high_key && p95<1.9 {
        ((3.7-dynamic_range)*5.0).clamp(0.0,14.0).round()
    }else if dynamic_range>7.0 && p05< -5.0
        && p95>2.4 && bright_fraction>0.08 && !low_key {
        -((dynamic_range-7.0)*2.0).clamp(0.0,6.0).round()
    }else{0.0};
    let blacks=if !low_key && shadow_need>0.45 && shadow_fraction>0.20 {
        -2.0
    }else{0.0};

    // Colourfulness is not itself a deficiency. Require measurable low
    // chroma in sufficiently many actual colours. Neutral greys, skin-like
    // midtones, saturated flowers and already-balanced sunsets stay put.
    let eligible=colour_eligible.max(1) as f64;
    let colour_fraction=coloured as f64/eligible;
    let soft_fraction=soft_colour as f64/eligible;
    let rich_fraction=rich_colour as f64/eligible;
    let skin_fraction=skin_like_colour as f64/eligible;
    let mean_sat=colour_mass/eligible;
    let has_colour=colour_fraction>0.22 && colour_eligible as f64>n*0.10;
    let weak_colour=if no_guards {
        colour_eligible>0 && colour_fraction>0.05 && mean_sat<0.30
    }else{
        has_colour && mean_sat<0.17
            && soft_fraction>0.14 && rich_fraction<0.08
            && skin_fraction<0.26
    };
    let chroma_need=if weak_colour {
        (((if no_guards {0.30} else {0.17})-mean_sat)
            /(if no_guards {0.23} else {0.13})).clamp(0.0,1.0)
    }else{0.0};
    let mut vibrance=if chroma_need>0.22 {
        (chroma_need*19.0).clamp(0.0,19.0).round()
    }else{0.0};
    let mut saturation=if chroma_need>0.44 {
        ((chroma_need-0.35)*8.0).clamp(0.0,5.0).round()
    }else{0.0};
    // Genuine channel clipping *plus* unusually strong colour is
    // evidence for a mild negative correction; vivid colours alone are not.
    let clipped_fraction=clipped_colour as f64/eligible;
    if !no_guards && has_colour && rich_fraction>0.40 && mean_sat>0.63
        && clipped_fraction>0.065 {
        vibrance=-((clipped_fraction-0.065)*27.0).clamp(0.0,9.0).round();
        saturation=-((clipped_fraction-0.065)*12.0).clamp(0.0,5.0).round();
    }

    // Smart Auto may return zero for every control. Under- and overexposed
    // photos still receive real exposure changes where the evidence agrees.
    // A bright protected sky makes positive global exposure inappropriate,
    // even if most of the frame is a dark foreground. Repair the ground
    // with Shadows/Curve instead; keep a small exposure margin only.
    let exposure=if !no_guards && backlit_daylight_scene && exposure>0.0 {
        exposure.min(0.12)
    }else{exposure};
    // v0.6.8: Lightcraft-style finishing. The defect-driven v0.6.5
    // analysis often gives a genuine photograph twelve zero sliders:
    // useful *corrections* were preserved, but normal studio refinements
    // disappeared. Give normally exposed photographs a measured nudge
    // through their REAL editable Lightroom-style controls instead of
    // requiring a defect before any adjustment is allowed.
    //
    // Avoid universal fixed curves, saturation boosts or exposure lifts:
    // a high-key image, intentional night, warm emitter or clipped sky
    // needs a different result from a softly lit landscape.
    let ordinary_photo=!low_key && !high_key;
    let polish_structure=structure_fraction>0.010
        && dynamic_range>2.5 && dynamic_range<6.8;
    let exposure=if ordinary_photo && !backlit_daylight_scene
        && !protected_warm_colour && exposure.abs()<0.005 {
        let nudge=if median< -0.10 && median> -1.45 {
            (0.09*(-0.10-median)/1.10).clamp(0.0,0.07)
        }else if median>0.35 && median<0.75 && bright_fraction<0.18 {
            -(0.06*(median-0.35)/0.40).clamp(0.0,0.05)
        }else {0.0};
        (nudge*100.0).round()/100.0
    }else{exposure};
    // Small, data-responsive contrast is a finish, not a claim that the
    // photograph was incorrectly exposed. A genuine exposure correction
    // or a strongly compressed dynamic range still uses the main Auto.
    let contrast=if ordinary_photo && polish_structure
        && contrast.abs()<0.5 {
        if dynamic_range<3.8 {4.0}
        else if dynamic_range<5.3 {3.0}
        else {2.0}
    }else{contrast};
    // Keep the existing scene-derived contrast correction, but avoid
    // unnecessary heavy contrast on already normally exposed photographs.
    let contrast=if ordinary_photo &&
        !no_guards && contrast>8.0 && exposure.abs()<=0.06 && !backlit_daylight_scene {
        8.0
    }else{contrast};
        // Subtle upper-midtones only on cool/neutral moderate-key scenes.
    // Do not brighten a warm sunset's sky or intentionally black areas.
    let curve_lights=if ordinary_photo && !protected_warm_colour
        && !backlit_daylight_scene && curve_lights==0.0
        && median< -0.52 && median> -1.35 && p75<0.25
        && dynamic_range<6.5 && structure_fraction>0.015 {
        3.0
    }else{curve_lights};
    // Real scenes with ordinary gentle colours benefit from a tiny
    // vibrance finish; vivid flowers, faces, sunsets and greys do not.
    let vibrance=if ordinary_photo && !protected_warm_colour
        && vibrance==0.0 && has_colour && mean_sat<0.34
        && rich_fraction<0.10 && skin_fraction<0.22
        && soft_fraction>0.12 {
        if mean_sat<0.19 {3.0}else{2.0}
    }else{vibrance};
    // v0.6.12 A/B EXPERIMENT — less restrictive estimation, NOT production.
    // The preceding deficit-based policy frequently vetoed an edit before
    // the render guard could test it. Use multi-region and tonal evidence to
    // propose corrections, then let the actual output render constrain them.
    let mut candidate=AutoTone {
        exposure, contrast, highlights, shadows, whites:0.0,
        blacks, vibrance, saturation,
        curve_shadows, curve_darks, curve_lights, curve_highlights,
    };
    // Dark, textured photographs with usable tonal range and no dominant
    // bright reference can need much more than the historical 0.46 EV cap.
    // Strongly coloured warm emitters, sunsets, flat night sky and already
    // well-lit subjects cannot qualify from histogram darkness alone.
    // A coherent dark subject against a darker background is not evidence
    // of underexposure: this includes naturally darker skin and low-key
    // portraits. The scene's existing tonal separation can be intentional.
    // Only the genuine whole-frame deficit path may request strong global
    // exposure in this experiment; low-key subjects remain eligible for
    // separately justified *local* shadow development.
    let recoverable_dark_subject=if no_guards {
        recoverable_fraction>0.035 && structure_fraction>0.002
            && median< -0.20
    }else{
        recoverable_fraction>0.17
            && structure_fraction>0.013 && subject_ev< -0.70
            && median< -0.58 && p95<0.72
            && warm_emitters as f64/n<0.002
            && !protected_warm_colour && !high_key && !low_key
    };
    if recoverable_dark_subject {
        let need=(0.24+0.29*(-0.7-subject_ev).max(0.0)
            +0.13*(-0.7-median).max(0.0)).clamp(0.24,1.20);
        candidate.exposure=candidate.exposure.max((need*100.0).round()/100.0);
        candidate.shadows=candidate.shadows.max(
            (8.0+20.0*(recoverable_fraction-0.17).max(0.0)).clamp(8.0,18.0).round());
    }
    // A photograph with bright reflective surroundings and recoverable
    // dark subject structure needs joint highlight AND shadow development.
    // Treat these as evidence from zones, not object recognition.
    if zonal.bright_surround_dark_detail && (no_guards || warm_emitters as f64/n<0.002) {
        candidate.shadows=candidate.shadows.max(18.0);
        candidate.curve_darks=candidate.curve_darks.max(8.0);
        if !no_guards {candidate.exposure=candidate.exposure.min(0.12);}
    }
    if zonal.dark_texture_with_bright_reference
        && (no_guards || (warm_emitters as f64/n<0.002 && p95<1.85)) {
        candidate.shadows=candidate.shadows.max(12.0);
        candidate.curve_darks=candidate.curve_darks.max(7.0);
    }
    // Permit highlight compression on bright non-emissive images, even
    // when a coarse high-key label previously forced this control to zero.
    if p995>1.70 && bright_fraction>0.16
        && (no_guards || (warm_emitters as f64/n<0.002 && !protected_warm_colour)) {
        let h=-(8.0+12.0*(p995-1.7).max(0.0))
            .clamp(8.0,24.0).round();
        candidate.highlights=candidate.highlights.min(h);
        // Use the already-editable Whites control, previously hardcoded 0.
        if p995>2.1 && bright_fraction>0.21 {
            candidate.whites=-((p995-2.1)*9.0).clamp(0.0,12.0).round();
        }
    }
    // A nearly neutral moderate-key image can still benefit from normal
    // development. Never add unconditional saturation.
    if !low_key && !high_key && dynamic_range<5.5
        && p95<1.6 && structure_fraction>0.025 {
        candidate.contrast=candidate.contrast.max(4.0);
    }
    // Keep adjustments editable and bounded for an experimental comparison.
    candidate.exposure=candidate.exposure.clamp(if no_guards {-4.0}else{-1.2},if no_guards {4.0}else{1.2});
    candidate.shadows=candidate.shadows.clamp(if no_guards {-100.0}else{-35.0},if no_guards {100.0}else{35.0});
    candidate.highlights=candidate.highlights.clamp(if no_guards {-100.0}else{-35.0},if no_guards {100.0}else{20.0});
    candidate.curve_darks=candidate.curve_darks.clamp(if no_guards {-100.0}else{-24.0},if no_guards {100.0}else{24.0});
    // Propose an image-dependent edit before any output-quality vetoes.
    (candidate,false)

}

/// The production Smart Auto still uses real output-quality protections.
pub fn smart_auto_tone(src:&Rgb32f,info:&SourceInfo,s:&DevelopSettings)->AutoTone {
    let (proposal,emissive)=smart_auto_candidate(src,info,s);
    if emissive {proposal}
    else {preview_quality_guard(src,info,s,proposal)}
}

/// Lightweight diagnostic measurements; no pixels or source images are saved.
#[derive(Clone, Debug, Serialize)]
pub struct AutoToneEvidence {
    pub source_raw:bool,
    pub as_shot_kelvin:f64,
    pub median_ev:f64,
    pub p05_ev:f64,
    pub p95_ev:f64,
    pub p995_ev:f64,
    pub dark_fraction:f64,
    pub bright_fraction:f64,
    pub recoverable_fraction:f64,
    pub analysed_pixels:usize,
    pub zonal:crate::scene::ZonalSceneEvidence,
}

/// Records estimation separately from protection decisions.
#[derive(Clone, Debug, Serialize)]
pub struct AutoToneAuditReport {
    pub evidence:AutoToneEvidence,
    pub proposed:AutoTone,
    pub final_tone:AutoTone,
    pub guard_reasons:Vec<&'static str>,
    pub new_white_clip_fraction:f64,
    pub new_black_clip_fraction:f64,
    pub comparison_exposure_ev:f64,
    pub quality_guard_skipped_for_emission:bool,
}

/// Production audit: retain all established output-quality protections.
pub fn smart_auto_tone_report(src:&Rgb32f,info:&SourceInfo,s:&DevelopSettings)
    -> AutoToneAuditReport {
    smart_auto_tone_report_mode(src,info,s,true)
}

/// v0.6.12c TEST ONLY: apply the complete scene-dependent Auto candidate
/// directly, without the preview-render clipping/overlift/contrast vetoes.
/// The candidate's inherent scene detection and valid slider bounds remain.
/// Keep this separate from normal Auto; do not promote without photo review.
pub fn smart_auto_tone_report_unguarded(src:&Rgb32f,info:&SourceInfo,s:&DevelopSettings)
    -> AutoToneAuditReport {
    smart_auto_tone_report_mode(src,info,s,false)
}

/// v0.6.12d: no photographic Auto Tone guard or candidate veto.
pub fn smart_auto_tone_report_all_guards_off(
    src:&Rgb32f,info:&SourceInfo,s:&DevelopSettings
) -> AutoToneAuditReport {
    let mut audit=smart_auto_tone_report_mode(src,info,s,false);
    let (proposal,_)=smart_auto_candidate_mode(src,info,s,true);
    audit.proposed=proposal;
    audit.final_tone=proposal;
    audit.guard_reasons=vec!["all_photographic_auto_guards_disabled_test_only"];
    audit
}

fn smart_auto_tone_report_mode(
    src:&Rgb32f,info:&SourceInfo,s:&DevelopSettings,enforce_render_guard:bool
) -> AutoToneAuditReport {
    let proxy=lightcraft_raster::resample::fit(
        src,256,256,lightcraft_raster::resample::Filter::Box);
    let mut linear=proxy;
    let analysis_base=DevelopSettings {wb:s.wb,..DevelopSettings::default()};
    crate::local::scene_linear_pre(&mut linear,info,&analysis_base);
    let mut ev:Vec<f32>=linear.data.iter().map(|c|
        (luminance_2020(*c).max(1e-6)/0.18).log2()
    ).collect();
    let len=ev.len().max(1);
    let dark=ev.iter().filter(|&&x|x< -2.68).count();
    let bright=linear.data.iter()
        .filter(|c|luminance_2020(**c)>0.70).count();
    let recover=linear.data.iter().filter(|c|
        (0.035..0.28).contains(&luminance_2020(**c))
    ).count();
    ev.sort_by(|a,b|a.total_cmp(b));
    let evidence=AutoToneEvidence {
        source_raw:info.raw, as_shot_kelvin:info.as_shot_temp,
        median_ev:percentile(&ev,0.50) as f64,
        p05_ev:percentile(&ev,0.05) as f64,
        p95_ev:percentile(&ev,0.95) as f64,
        p995_ev:percentile(&ev,0.995) as f64,
        dark_fraction:dark as f64/len as f64,
        bright_fraction:bright as f64/len as f64,
        recoverable_fraction:recover as f64/len as f64,
        analysed_pixels:ev.len(),
        zonal:crate::scene::analyse_linear(&linear),
    };
    let (proposal,emissive)=smart_auto_candidate(src,info,s);
    let guarded=if !enforce_render_guard {
        // Deliberately permit over-brightening, new clipping and dramatic
        // curves for an isolated A/B photograph experiment. The diagnostic
        // reports the true unmodified proposal that Auto will apply.
        GuardOutcome {
            tone:proposal,
            reasons:vec!["experimental_render_quality_guards_bypassed"],
            white_clip_change:0.0,black_clip_change:0.0,
        }
    }else if emissive {
        GuardOutcome {
            tone:proposal,
            reasons:vec!["warm_emitter_specialized_recovery"],
            white_clip_change:0.0,black_clip_change:0.0,
        }
    }else{preview_quality_guard_report(src,info,s,proposal)};
    AutoToneAuditReport {
        evidence, proposed:proposal, final_tone:guarded.tone,
        guard_reasons:guarded.reasons,
        new_white_clip_fraction:guarded.white_clip_change,
        new_black_clip_fraction:guarded.black_clip_change,
        comparison_exposure_ev:s.light.exposure,
        quality_guard_skipped_for_emission:emissive,
    }
}

struct GuardOutcome {
    tone:AutoTone,
    reasons:Vec<&'static str>,
    white_clip_change:f64,
    black_clip_change:f64,
}
/// Fraction of RGB channels that cross into clipping at the same pixel.
fn newly_clipped_rgb_fraction(
    before:&[[u8;4]], after:&[[u8;4]], bright:bool,
) -> f64 {
    if before.is_empty() || before.len()!=after.len() { return 0.0; }
    let newly=before.iter().zip(after).map(|(b,a)|
        (0..3).filter(|&c|
            if bright {b[c]<254 && a[c]>=254}
            else {b[c]>1 && a[c]<=1}
        ).count()
    ).sum::<usize>();
    newly as f64/(before.len()*3) as f64
}

fn preview_quality_guard(
    src:&Rgb32f,info:&SourceInfo,s:&DevelopSettings,a:AutoTone,
) -> AutoTone {
    preview_quality_guard_report(src,info,s,a).tone
}

fn preview_quality_guard_report(
    src:&Rgb32f,info:&SourceInfo,s:&DevelopSettings,mut a:AutoTone,
) -> GuardOutcome {
    let mut reasons=Vec::new();
    let strength=a.exposure.abs()+a.contrast.abs()+a.highlights.abs()
        +a.shadows.abs()+a.whites.abs()+a.blacks.abs()+a.vibrance.abs()
        +a.saturation.abs()+a.curve_shadows.abs()+a.curve_darks.abs()
        +a.curve_lights.abs()+a.curve_highlights.abs();
    if strength==0.0 {
        return GuardOutcome {
            tone:a,reasons,white_clip_change:0.0,black_clip_change:0.0
        };
    }
    // This is an inexpensive *safety* gate, not a learned aesthetic scorer.
    // A 160px full-pipeline render tests the actual RGB output including
    // tone curves and colour transforms, unlike checking slider positions.
    let proxy=lightcraft_raster::resample::fit(
        src,160,160,lightcraft_raster::resample::Filter::Box);
    let req=crate::RenderRequest::fit(160,160);
    // Compare against the full current edit, including existing exposure,
    // effects, profile, tone and curves—not a fabricated default baseline.
    let baseline=s.clone();
    let mut changed=baseline.clone();
    changed.light.exposure=a.exposure;
    changed.light.contrast=a.contrast;
    changed.light.highlights=a.highlights;
    changed.light.shadows=a.shadows;
    changed.light.blacks=a.blacks;
    changed.light.whites=a.whites;
    changed.color.vibrance=a.vibrance;
    changed.color.saturation=a.saturation;
    changed.curve.shadows=a.curve_shadows;
    changed.curve.darks=a.curve_darks;
    changed.curve.lights=a.curve_lights;
    changed.curve.highlights=a.curve_highlights;
    let before=crate::render(&proxy,info,&baseline,&req).image;
    let after=crate::render(&proxy,info,&changed,&req).image;
    if before.data.is_empty() || before.data.len()!=after.data.len() {
        reasons.push("guard_render_failed");
        return GuardOutcome {
            tone:AutoTone::default(),reasons,
            white_clip_change:0.0,black_clip_change:0.0
        };
    }
    // Measure *newly* clipped channels at their original pixel positions,
    // rather than subtracting whole-frame clipped totals. Otherwise one
    // highlight becoming clipped can be hidden by a different highlight
    // becoming unclipped in the same render (the net total would be zero).
    // Existing JPEG clipping is excluded so Auto is not penalized for it.
    let new_whites=newly_clipped_rgb_fraction(&before.data,&after.data,true);
    let new_blacks=newly_clipped_rgb_fraction(&before.data,&after.data,false);
    // Experimental threshold: permit modest output changes rather than
    // treating ~1% newly bright RGB samples as an automatic full veto.
    // Severe clipping still triggers the established correction.
    if new_whites>0.028 {
        reasons.push("added_highlight_clipping");
        if a.exposure>0.0 {a.exposure=0.0;}
        a.curve_lights=0.0;
        a.vibrance=0.0;
        a.saturation=0.0;
    }
    if new_blacks>0.028 {
        reasons.push("added_shadow_clipping");
        if a.exposure<0.0 {a.exposure=0.0;}
        a.blacks=0.0;
        if a.contrast<0.0 {a.contrast=0.0;}
    }
    // Protect deep intentional shadows from broad lifts, unless a
    // separately established exposure deficit justified the lift.
    let mut dark_count=0usize;
    let mut dark_lift=0.0f64;
    let mut grad0=0.0f64;
    let mut grad1=0.0f64;
    for (i,(b,c)) in before.data.iter().zip(&after.data).enumerate() {
        let y=|p:&[u8;4]|(54u32*p[0] as u32
            +183u32*p[1] as u32+19u32*p[2] as u32) as f64/256.0;
        let y0=y(b);
        let y1=y(c);
        if y0>4.0 && y0<67.0 {
            dark_count+=1;
            dark_lift+=(y1-y0).max(0.0);
        }
        if i>0 {
            let p=&before.data[i-1];
            let q=&after.data[i-1];
            grad0+=(y(b)-y(p)).abs();
            grad1+=(y(c)-y(q)).abs();
        }
    }
    if dark_count>200 && a.exposure<=0.0
        && dark_lift/dark_count as f64>24.0 {
        reasons.push("dark_region_overlift");
        a.shadows=0.0;
        a.curve_shadows=0.0;
        a.curve_darks=0.0;
    }
    // A major collapse of brightness edges means lost useful contrast:
    // reject the contrast/curve proposal, but keep warranted exposure.
    if grad0>1000.0 && grad1<grad0*0.61 {
        reasons.push("structural_contrast_loss");
        a.contrast=0.0;
        a.curve_shadows=0.0;
        a.curve_darks=0.0;
        a.curve_lights=0.0;
        a.curve_highlights=0.0;
    }
    GuardOutcome {
        tone:a,reasons,
        white_clip_change:new_whites,
        black_clip_change:new_blacks,
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

/// Per-photograph Texture and Clarity recommendations. Unlike the old
/// fixed (+5, +3, or zero) thresholds, this estimates two *different* detail
/// scales on the source preview: fine local changes for Texture and broader
/// edge transitions for Clarity. The calculation is deterministic and runs
/// entirely offline. Do not infer photographic haze from contrast alone.
pub fn auto_effects(src: &Rgb32f, _info: &SourceInfo) -> AutoEffects {
    let (noise, colour_noise, _edges, clipped, noise_confidence) = auto_quality_metrics(src);
    // A 1024px analysis proxy keeps both measurements at a repeatable spatial
    // scale while retaining far more usable structure than the old thumbnail.
    let img = lightcraft_raster::resample::fit(
        src, 1024, 1024, lightcraft_raster::resample::Filter::Box);
    if img.width < 32 || img.height < 32 { return AutoEffects::default(); }
    let w = img.width;
    let h = img.height;
    let lum: Vec<f32> = img.data.iter().map(|p| luminance_2020(*p)).collect();
    let mut fine = Vec::new();
    let mut middle = Vec::new();
    let mut fine_edges = 0usize;
    let mut middle_edges = 0usize;
    let mut smooth_warm = 0usize;
    let mut valid = 0usize;

    // Measure at two scales on genuinely visible pixels. The 1px and 7px
    // opposing-neighbour contrasts are normalized by local luminance, so
    // dark foliage and light architecture can be compared. Stride 3 keeps
    // the analysis inexpensive even on a 16 MP phone photograph.
    for y in (8..h - 8).step_by(3) {
        for x in (8..w - 8).step_by(3) {
            let i = y*w+x;
            let v = lum[i];
            if !v.is_finite() || !(0.035..0.85).contains(&v) { continue; }
            let norm = v+0.08;
            let f = ((lum[i-1]-lum[i+1]).abs()
                    +(lum[i-w]-lum[i+w]).abs())*0.5/norm;
            let m = ((lum[i-7]-lum[i+7]).abs()
                    +(lum[i-7*w]-lum[i+7*w]).abs())*0.5/norm;
            if !f.is_finite() || !m.is_finite() {continue;}
            valid+=1;
            fine.push(f);
            middle.push(m);
            if f>0.035 {fine_edges+=1;}
            if m>0.075 {middle_edges+=1;}
            // A large area of relatively smooth, warm, moderate-chroma
            // pixels may be skin. Limit microcontrast there rather than
            // claiming to recognise a person or to know where skin is.
            let c=img.data[i];
            if f<0.055 && m<0.16
                && c[0]>c[1]*1.08 && c[1]>c[2]*1.02
                && c[0]<c[2]*2.1 {
                let max=c[0].max(c[1]).max(c[2]);
                let min=c[0].min(c[1]).min(c[2]);
                let sat=(max-min)/max.max(1e-6);
                if (0.12..0.55).contains(&sat) {smooth_warm+=1;}
            }
        }
    }
    if valid<120 {return AutoEffects::default();}
    fine.sort_by(f32::total_cmp);
    middle.sort_by(f32::total_cmp);
    let q=|v:&Vec<f32>,p:f64| -> f64 {
        v[(((v.len()-1) as f64*p) as usize)] as f64
    };
    let fine_q=q(&fine,0.80);
    let middle_q=q(&middle,0.75);
    let fine_density=fine_edges as f64/valid as f64;
    let middle_density=middle_edges as f64/valid as f64;
    let warm_fraction=smooth_warm as f64/valid as f64;

    // No broad haloes on crisp JPEG edges and no microcontrast boost to
    // sensor noise. Noise estimates use the existing high-resolution noise
    // analysis; their thresholds are gently tapered, not binary gates.
    let noise_safety=(1.0-(noise-0.006).max(0.0)/0.012).clamp(0.0,1.0)
        *(1.0-(colour_noise-0.008).max(0.0)/0.012).clamp(0.0,1.0);
    let highlight_safety=if clipped>0.025 {0.72}else{1.0};
    let confidence=(valid as f64/1800.0).clamp(0.0,1.0)
        * (0.55+0.45*noise_confidence).clamp(0.0,1.0);
    let mut texture=if fine_q>0.009 || fine_density>0.14 {
        (fine_q*100.0+fine_density*7.0-1.0).clamp(0.0,18.0)
    }else{0.0};
    let mut clarity=if middle_q>0.015 || middle_density>0.12 {
        (middle_q*62.0+middle_density*10.0-1.8).clamp(0.0,14.0)
    }else{0.0};

    // Extremely strong existing pixel-level edges are often already
    // sharpened in a camera JPEG. Ease off Texture rather than layering
    // another maximal microcontrast pass over those edges.
    let already_crisp=(1.0-0.55*((fine_q-0.14)/0.18).clamp(0.0,1.0));
    texture*=noise_safety*highlight_safety*already_crisp;
    clarity*=noise_safety*highlight_safety;
    if warm_fraction>0.18 {
        texture=texture.min(7.0);
        clarity=clarity.min(6.0);
    }
    // Round only at the final slider boundary. A clear sky or smooth face
    // may legitimately produce zero, but unlike v0.6.1, detailed scenes
    // can receive an image-specific setting instead of a fixed +5/+3.
    AutoEffects {
        texture:texture.round(),
        clarity:clarity.round(),
        dehaze:0.0,
        confidence,
    }
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
    /// Why this correction was adopted or rejected.
    pub reason:&'static str,
    pub eligible_fraction:f64,
    pub deep_shadow_fraction:f64,
    pub warm_midtones_fraction:f64,
    pub warm_emitter_fraction:f64,
    pub raw_source:bool,
    /// Confidence support from the least-coloured pixels, separate from
    /// the wider (possibly subject-coloured) candidate pool.
    pub high_trust_neutral_fraction:f64,
    pub high_trust_neutral_tiles:usize,
    pub high_trust_bright_fraction:f64,
    /// Difference in chromatic ratios between broad and high-trust pools.
    pub neutral_estimator_disagreement:f64,
    pub bright_cool_dominance:bool,
}
fn as_shot_report(info:&SourceInfo)->AutoWbReport{
    AutoWbReport {
        temp:info.as_shot_temp,tint:info.as_shot_tint,
        confidence:0.0,neutral_fraction:0.0,spatial_coverage:0,
        camera_balance_retained:true,decision:"no_neutral",
        candidate_temp:info.as_shot_temp,candidate_tint:info.as_shot_tint,
        reason:"no_reliable_neutral_reference",
        eligible_fraction:0.0,
        deep_shadow_fraction:0.0,
        warm_midtones_fraction:0.0,
        warm_emitter_fraction:0.0,
        raw_source:info.raw,
        high_trust_neutral_fraction:0.0,
        high_trust_neutral_tiles:0,
        high_trust_bright_fraction:0.0,
        neutral_estimator_disagreement:0.0,
        bright_cool_dominance:false,
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
    if img.width<24 || img.height<24 || img.data.is_empty(){
        report.reason="insufficient_image_data";
        return report;
    }
    let (w,h)=(img.width,img.height);
    // Colour casts cannot be judged by grey-world statistics alone:
    // neutral-looking surfaces illuminated by fire or warm lamps are
    // deliberately warm. Inspect lighting context before cooling a JPEG.
    let mut deep_shadow_pixels=0usize;
    let mut warm_lit_pixels=0usize;
    let mut warm_emitter_pixels=0usize;
    for c in &img.data {
        let y=luminance_2020(*c);
        if y<0.028 {deep_shadow_pixels+=1;}
        if (0.04..0.60).contains(&y) &&
            c[0]>c[1]*1.12 && c[1]>c[2]*1.08 {
            warm_lit_pixels+=1;
        }
        if y>0.45 && c[0]>0.60 &&
            c[0]>c[1]*1.45 && c[1]>c[2]*1.65 {
            warm_emitter_pixels+=1;
        }
    }
    let total_pixels=img.data.len() as f64;
    let shadow_share=deep_shadow_pixels as f64/total_pixels;
    let warm_midtone_share=warm_lit_pixels as f64/total_pixels;
    let warm_emitter_share=warm_emitter_pixels as f64/total_pixels;
    report.deep_shadow_fraction=shadow_share;
    report.warm_midtones_fraction=warm_midtone_share;
    report.warm_emitter_fraction=warm_emitter_share;
    // Preserve the camera look under visible warm practical lighting
    // rather than treating amber-lit greys as neutral reference cards.
    // Manual picker/temperature controls are unaffected by this gate.
    let intentional_warm_light=shadow_share>0.38
        && (warm_emitter_share>0.0015
            || (shadow_share>0.50 && warm_midtone_share>0.11));
    let mut eligible=0usize;
    let mut neutral=0usize;
    let mut sum=[0.0f64;3];
    let mut weight_sum=0.0f64;
    let mut cell_rgb=[[0.0f64;3];16];
    let mut cell_weights=[0.0f64;16];
    let mut cell_counts=[0usize;16];
    let mut strong_colour=0usize;
    let mut near_count=0usize;
    let mut near_bright=0usize;
    let mut near_sum=[0.0f64;3];
    let mut near_weight=0.0f64;
    let mut near_cells=[0usize;16];
    for (index,c) in img.data.iter().enumerate(){
        let y=luminance_2020(*c);
        if !(0.05..=0.78).contains(&y){continue;}
        eligible+=1;
        let hi=c[0].max(c[1]).max(c[2]).max(1e-6);
        let lo=c[0].min(c[1]).min(c[2]);
        let chroma=((hi-lo)/hi).clamp(0.0,1.0);
        if chroma>0.48{strong_colour+=1;}
        // A high-trust, relatively achromatic anchor is evidence of an
        // object's reflectance. Record it independently of the wider pool;
        // the latter can include warm skin or cool water.
        if chroma<0.085 {
            let x=index%w;
            let yy=index/w;
            let cell=((yy*4/h).min(3))*4+((x*4/w).min(3));
            near_cells[cell]+=1;
            near_count+=1;
            if y>0.50 {near_bright+=1;}
            let weight=(1.0-chroma/0.085) as f64;
            near_weight+=weight;
            for ch in 0..3 {
                near_sum[ch]+=c[ch] as f64*weight;
            }
        }
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
    report.eligible_fraction=eligible as f64/total_pixels;
    report.spatial_coverage=cell_counts.iter()
        .filter(|&&n|n>15).count();
    if eligible<200 || neutral<100 || report.spatial_coverage<3
        || weight_sum<13.0 || report.neutral_fraction<0.012 {
        report.reason="insufficient_spatial_neutral_support";
        return report;
    }
    let broad_mean=sum.map(|v|v/weight_sum);
    report.high_trust_neutral_fraction=near_count as f64/eligible as f64;
    report.high_trust_neutral_tiles=near_cells.iter().filter(|&&v|v>=12).count();
    report.high_trust_bright_fraction=near_bright as f64/(near_count.max(1) as f64);
    let trusted=near_count>=100 && near_weight>12.0
        && report.high_trust_neutral_tiles>=3;
    let near_mean=if trusted {
        near_sum.map(|v|v/near_weight)
    }else{broad_mean};
    let ratio=|rgb:[f64;3]|[
        rgb[0].max(1e-6)/rgb[1].max(1e-6),
        rgb[2].max(1e-6)/rgb[1].max(1e-6),
    ];
    let br=ratio(broad_mean);
    let nr=ratio(near_mean);
    report.neutral_estimator_disagreement=if trusted {
        (br[0]/nr[0]).ln().abs()+(br[1]/nr[1]).ln().abs()
    }else{0.0};
    // Strong, independent neutral anchors take precedence over light skin
    // and other intrinsically coloured material that slips under the
    // broader 0.24 chroma threshold.
    let mean=if trusted {
        std::array::from_fn(|i| (near_mean[i]*0.78+broad_mean[i]*0.22) as f32)
    }else{broad_mean.map(|v|v as f32)};
    let mr=ratio(mean.map(f64::from));
    // A large cool, bright neutral-looking plane (e.g. sky reflections on
    // water) does not prove a blue illuminant. For *rendered JPEGs*,
    // treat a mild blue bias of this one appearance class as provisional.
    report.bright_cool_dominance=!info.raw
        && report.high_trust_neutral_fraction>0.16
        && report.high_trust_bright_fraction>0.88
        && mr[0]<0.988 && mr[1]>1.018;
    let patch=Rgb32f::filled(16,16,mean);
    let (t,tint)=auto_wb_classic(&patch,info);
    if !t.is_finite() || !tint.is_finite(){
        report.reason="invalid_candidate_illuminant";
        return report;
    }
    report.candidate_temp=t;
    report.candidate_tint=tint;
    if intentional_warm_light {
        report.decision="warm_light_preserved";
        report.reason="warm_lowlight_atmosphere_detected";
        return report;
    }

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
    let reference_weight=if report.bright_cool_dominance {
        0.38
    } else {1.0};
    report.confidence=(density*coverage*consistency*colour_protection*reference_weight)
        .clamp(0.0,1.0);
    if report.confidence<0.14 {
        report.decision="ambiguous";
        report.reason="neutral_region_evidence_ambiguous";
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
    // Tiny computed changes are below useful JPEG WB correction
    // confidence: preserve the actual as-shot setting, especially with
    // warm or naturally colourful light. Correct each axis independently.
    report.temp=if diff.abs()<4.0 {
        info.as_shot_temp
    }else{
        (1_000_000.0/(source_mired+diff))
            .clamp(2000.0,50000.0).round()
    };
    let tint_change=((tint-info.as_shot_tint)*report.confidence)
        .clamp(-limit_tint,limit_tint);
    report.tint=if tint_change.abs()<5.0 {
        info.as_shot_tint
    }else{(info.as_shot_tint+tint_change).round()};
    report.camera_balance_retained=
        (report.temp-info.as_shot_temp).abs()<1.0 &&
        (report.tint-info.as_shot_tint).abs()<1.0;
    report.decision=if report.camera_balance_retained{
        "already_neutral"
    }else{"corrected"};
    report.reason=if report.bright_cool_dominance {
        "bright_cool_reference_may_be_reflection"
    } else if report.camera_balance_retained {
        "correction_below_reliable_threshold"
    } else if report.neutral_estimator_disagreement>0.045 {
        "high_trust_neutrals_override_coloured_regions"
    } else {"consistent_spatial_neutral_estimate"};
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
        // New clipping gate may reject *global* exposure; local dark
        // recovery should remain available without destroying clouds.
        assert!(a.exposure>0.0 || a.curve_darks>0.0,
            "white sky vetoed all justified improvements: {a:?}");
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
        // This candidate differs only by ~75 K and one tint unit:
        // evidence exists, but it does not justify changing the JPEG.
        assert_eq!(r.decision,"already_neutral","tiny WB drift should be ignored: {r:?}");
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

    // Deliberately weak *colour and tone*, not simply a normally
    // saturated detailed landscape. It should be eligible for enhancement.
    fn muted_landscape() -> Rgb32f {
        Rgb32f::from_fn(160, 112, |x, y| {
            let grad = (x % 40) as f32 * 0.00015;
            if y < 18 {
                // A small actually mid-bright patch gives the Lights
                // parametric curve real pixels to influence.
                [0.37 + grad, 0.40 + grad, 0.41 + grad]
            } else if (x / 20) % 2 == 0 {
                [0.038 + grad, 0.042 + grad, 0.039 + grad]
            } else {
                [0.023 + grad, 0.025 + grad, 0.022 + grad]
            }
        })
    }

    #[test]
    fn auto_colour_is_visible_on_subdued_landscape() {
        let a = smart_auto_tone(&muted_landscape(), &SourceInfo::default(), &DevelopSettings::default());
        assert!(a.vibrance >= 6.0 && a.vibrance <= 19.0, "weak colour should receive evidence-driven vibrance: {a:?}");
        assert!(a.saturation >= 0.0 && a.saturation <= 5.0, "saturation must be gentle and independently optional: {a:?}");
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
            for (name,value) in [("vibrance",a.vibrance),
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

#[cfg(test)]
mod studio_v062_adaptive_auto_effects_tests {
    use super::*;

    fn portrait_like() -> Rgb32f {
        Rgb32f::from_fn(600,420, |x,y| {
            if y<70 { [0.065,0.038,0.030] }
            else if y>350 { [0.045,0.18,0.12] }
            else {
                let v=(x as f32/600.0*0.006)+(y as f32/420.0*0.004);
                [0.25+v,0.19+v,0.15+v]
            }
        })
    }
    fn leaves() -> Rgb32f {
        Rgb32f::from_fn(600,420, |x,y| {
            if y<100 { [0.22,0.29,0.38] }
            else {
                let a=((x/4+y/6)%3) as f32;
                let b=((x/16+y/13)%4) as f32;
                [0.05+a*0.013+b*0.004,0.13+a*0.032+b*0.008,
                 0.048+a*0.016+b*0.005]
            }
        })
    }
    fn building() -> Rgb32f {
        Rgb32f::from_fn(600,420,|x,y| {
            if x%35<4 || y%28<3 {[0.105,0.115,0.12]}
            else {[0.30,0.31,0.32]}
        })
    }

    #[test]
    fn settings_respond_individually_to_subject_structure() {
        let info=SourceInfo::default();
        let smooth=auto_effects(&portrait_like(),&info);
        let foliage=auto_effects(&leaves(),&info);
        let architecture=auto_effects(&building(),&info);
        assert!(foliage.texture>smooth.texture,
            "fine leaves should get more Texture than a smooth portrait: {smooth:?} {foliage:?}");
        assert!(architecture.clarity>smooth.clarity,
            "defined architecture should get more Clarity: {smooth:?} {architecture:?}");
        assert!((foliage.texture,foliage.clarity)!=(architecture.texture,architecture.clarity),
            "the engine must not emit the same effect preset on every scene");
        for result in [smooth,foliage,architecture] {
            assert!((0.0..=24.0).contains(&result.texture));
            assert!((0.0..=18.0).contains(&result.clarity));
            assert_eq!(result.dehaze,0.0);
        }
    }

    #[test]
    fn smooth_neutral_photo_is_not_force_sharpened() {
        let src=Rgb32f::filled(512,384,[0.20,0.20,0.20]);
        let a=auto_effects(&src,&SourceInfo::default());
        assert_eq!((a.texture,a.clarity,a.dehaze),(0.0,0.0,0.0));
    }

    #[test]
    fn strong_pixel_noise_is_not_mistaken_for_texture() {
        let src=Rgb32f::from_fn(512,384,|x,y|{
            let n=((((x*37+y*101+x*y*13)%257) as f32/257.0)-0.5)*0.11;
            [0.18+n,0.18-n*0.7,0.18+n*0.4]
        });
        let a=auto_effects(&src,&SourceInfo::default());
        assert!(a.texture<=3.0 && a.clarity<=3.0,
            "must avoid increasing high-ISO noise: {a:?}");
    }

    #[test]
    fn photograph_preview_and_export_use_real_effect_pixels() {
        use crate::{RenderRequest,render};
        let src=leaves();
        let info=SourceInfo::default();
        let a=auto_effects(&src,&info);
        assert!(a.texture>0.0 || a.clarity>0.0,"detail must be detected: {a:?}");
        let mut settings=DevelopSettings::default();
        settings.effects.texture=a.texture;
        settings.effects.clarity=a.clarity;
        for edge in [160,600] {
            let req=RenderRequest::fit(edge,edge);
            let before=render(&src,&info,&DevelopSettings::default(),&req).image;
            let after=render(&src,&info,&settings,&req).image;
            let count=before.data.iter().zip(&after.data)
                .filter(|(b,a)| b[0..3]!=a[0..3]).count();
            assert!(count>before.len()/80,"Auto Effects did not change output at {edge}px: {count}/{}",before.len());
        }
    }

    #[test]
    fn repeated_analysis_is_deterministic_and_does_not_edit_detail() {
        let src=leaves();
        let a=auto_effects(&src,&SourceInfo::default());
        let b=auto_effects(&src,&SourceInfo::default());
        assert_eq!(a,b);
        assert_eq!(DevelopSettings::default().detail,DevelopSettings::default().detail);
    }
}

#[cfg(test)]
mod studio_v064_defect_driven_smart_auto_tests {
    use super::*;

    fn settings() -> DevelopSettings {DevelopSettings::default()}
    fn auto(img:&Rgb32f)->AutoTone {
        smart_auto_tone(img,&SourceInfo::default(),&settings())
    }
    fn finished_sunset()->Rgb32f {
        Rgb32f::from_fn(256,192,|x,y|{
            // Elaborate sunset with purposeful dark horizon, rich orange
            // clouds, bright sky and considerable texture, not a flat test.
            let fine=((x*31+y*13)%19) as f32/19.0*0.025;
            if y<85 {
                if x%43<24 {[0.68+fine,0.29+fine*0.35,0.10+fine*0.15]}
                else {[0.41+fine,0.20+fine*0.5,0.18+fine*0.4]}
            } else if y<145 {
                [0.19+fine,0.095+fine*0.35,0.073+fine*0.25]
            } else {
                if (x/9+y/8)%2==0 {[0.040+fine*0.2,0.033,0.030]}
                else {[0.090,0.052+fine*0.2,0.032]}
            }
        })
    }
    fn textured_finished_landscape()->Rgb32f {
        Rgb32f::from_fn(256,192,|x,y|{
            let detail=((x*29+y*43)%23) as f32/23.0*0.024;
            if y<64 {[0.43+detail,0.55+detail,0.66+detail]}
            else if y<145 {[0.15+detail,0.29+detail,0.15+detail]}
            else {[0.055+detail,0.070+detail,0.052+detail]}
        })
    }
    #[test]
    fn textured_balanced_landscape_is_not_assumed_defective() {
        let a=auto(&textured_finished_landscape());
        assert!(a.exposure.abs()<=0.06,"texture is not exposure error {a:?}");
        assert_eq!(a.curve_darks,0.0,"no unconditional lower-tone enhancement {a:?}");
        assert_eq!(a.vibrance,0.0,"already colourful landscapes need no vibrance {a:?}");
    }
    #[test]
    fn finished_sunset_does_not_receive_v061_positive_baselines() {
        let a=auto(&finished_sunset());
        assert!(a.exposure.abs()<=0.06,"balanced sunset moved: {a:?}");
        assert_eq!(a.vibrance,0.0,"v0.6.3 +9 colour bias recurred: {a:?}");
        assert_eq!(a.saturation,0.0);
        assert_eq!(a.curve_darks,0.0,"v0.6.3 +23 dark curve bias recurred: {a:?}");
        assert_eq!(a.curve_lights,0.0,"v0.6.3 +6 light curve bias recurred: {a:?}");
    }
    #[test]
    fn underexposure_still_gets_positive_exposure() {
        let img=Rgb32f::from_fn(192,128,|x,y|{
            let v=0.044+((x*17+y*11)%29) as f32*0.0002;
            [v,v*0.94,v*0.88]
        });
        let a=auto(&img);
        assert!(a.exposure>0.13,"genuinely underexposed frame ignored: {a:?}");
    }
    #[test]
    fn overexposure_gets_negative_exposure_without_forced_undersaturation() {
        let img=Rgb32f::from_fn(192,128,|x,y|{
            let v=0.87+((x*13+y*17)%17) as f32*0.0008;
            [v,v*0.98,v*0.96]
        });
        let a=auto(&img);
        assert!(a.exposure < -0.08,"genuinely overexposed frame ignored: {a:?}");
    }
    #[test]
    fn low_contrast_but_structured_frame_still_gets_contrast() {
        let img=Rgb32f::from_fn(192,128,|x,y|{
            let v=0.125+((x/5+y/8)%5) as f32*0.035;
            [v,v*1.02,v*0.98]
        });
        let a=auto(&img);
        assert!(a.contrast>0.0,"flat image with real structure ignored: {a:?}");
    }
    #[test]
    fn weak_colour_is_not_confused_with_neutral_grey() {
        let muted=Rgb32f::from_fn(192,128,|x,y|{
            if (x/20+y/20)%2==0 {[0.18,0.17,0.16]}
            else {[0.13,0.14,0.15]}
        });
        let a=auto(&muted);
        assert!(a.vibrance>0.0,"washed-out existing colours not improved: {a:?}");
        let grey=Rgb32f::filled(192,128,[0.18,0.18,0.18]);
        assert_eq!(auto(&grey).vibrance,0.0);
    }
    #[test]
    fn high_chroma_not_auto_desaturated_without_clip_evidence() {
        let img=Rgb32f::from_fn(192,128,|x,y|{
            if (x/22+y/19)%2==0 {[0.80,0.04,0.11]}
            else {[0.07,0.39,0.08]}
        });
        let a=auto(&img);
        assert_eq!((a.vibrance,a.saturation),(0.0,0.0));
    }
    #[test]
    fn objectively_clipped_colour_can_receive_mild_negative_colour() {
        let img=Rgb32f::from_fn(192,128,|x,y|{
            if (x/24+y/24)%2==0 {[1.06,0.025,0.07]}
            else {[0.028,0.99,0.026]}
        });
        let a=auto(&img);
        assert!(a.vibrance<=0.0 && a.saturation<=0.0,"negative corrections unsupported {a:?}");
    }
    #[test]
    fn intentional_low_key_preserves_mood() {
        let img=Rgb32f::from_fn(192,128,|x,y|{
            if x>77 && x<117 && y>32 && y<74 {[0.15,0.092,0.066]}
            else {[0.004,0.003,0.003]}
        });
        let a=auto(&img);
        assert_eq!(a.exposure,0.0,"low-key mood changed: {a:?}");
        assert_eq!((a.shadows,a.curve_darks,a.curve_lights),(0.0,0.0,0.0));
    }
    #[test]
    fn variable_complexions_keep_midtone_and_hair_relation() {
        for skin in [[0.13,0.078,0.054],[0.31,0.21,0.16]] {
            let img=Rgb32f::from_fn(192,128,|x,y|{
                if x>38 && x<146 && y>16 && y<110 {
                    if x<50||x>134 {[0.024,0.021,0.020]} else {skin}
                }else {[0.034,0.034,0.037]}
            });
            let a=auto(&img);
            assert!(a.exposure.abs()<0.10,"natural skin luminance shifted: {skin:?} {a:?}");
            assert_eq!(a.curve_darks,0.0,"dark hair lifted unnecessarily: {a:?}");
        }
    }
    #[test]
    fn pixel_guard_will_reject_new_highlight_clipping() {
        let img=Rgb32f::from_fn(192,128,|x,y|{
            if x<96 {[0.92,0.92,0.92]}
            else {[0.30,0.30,0.30]}
        });
        let before=AutoTone {exposure:0.9,curve_lights:20.0,..Default::default()};
        let after=preview_quality_guard(&img,&SourceInfo::default(),&settings(),before);
        assert_eq!(after.exposure,0.0,"safety gate failed to prevent new RGB clipping");
    }
    #[test]
    fn smart_auto_does_not_mutate_manual_point_curves() {
        use lightcraft_geom::Point;
        let mut s=settings();
        s.curve.master=vec![Point::new(0.0,0.0),Point::new(0.5,0.58),Point::new(1.0,1.0)];
        let unchanged=s.curve.master.clone();
        let _=smart_auto_tone(&finished_sunset(),&SourceInfo::default(),&s);
        assert_eq!(s.curve.master,unchanged);
    }
}


#[cfg(test)]
mod studio_v065_evidence_gate_regressions {
    use super::*;

    fn auto(img: &Rgb32f) -> AutoTone {
        smart_auto_tone(img, &SourceInfo::default(), &DevelopSettings::default())
    }

    #[test]
    fn white_flower_on_dark_background_does_not_trigger_fire_recovery() {
        // A brightly illuminated neutral petal surrounded by black foliage
        // is not evidence of fire or recoverable charcoal.
        let img=Rgb32f::from_fn(192,128,|x,y| {
            if (x>40 && x<155) && (y>26 && y<87) {
                [0.82,0.82,0.74]
            } else {
                [0.005,0.006,0.004]
            }
        });
        let a=auto(&img);
        assert_eq!(a.exposure,0.0,"bright flower against dark should keep its mood: {a:?}");
        assert_eq!(a.shadows,0.0,"not charcoal requiring +48: {a:?}");
        assert_eq!(a.curve_darks,0.0,"not fire requiring +42: {a:?}");
    }

    #[test]
    fn modest_global_underexposure_receives_a_useful_correction() {
        // A visibly dark but ordinary detailed midtone scene was previously
        // falling just below the 0.075 EV candidate threshold.
        let img=Rgb32f::from_fn(192,128,|x,y| {
            let v=0.091 + ((x*13+y*17)%19) as f32*0.0004;
            [v,v*0.98,v*0.96]
        });
        let a=auto(&img);
        assert!(a.exposure>=0.10,"measurable exposure defect should not be silently ignored: {a:?}");
        assert_eq!(a.curve_darks,0.0,"do not substitute automatic dark curves for exposure: {a:?}");
    }

    #[test]
    fn moon_in_black_sky_is_not_a_firelight_scene() {
        let img=Rgb32f::from_fn(192,128,|x,y| {
            if x>70 && x<126 && y>28 && y<83 {
                [0.82,0.82,0.78]
            } else {
                [0.003,0.003,0.003]
            }
        });
        let a=auto(&img);
        assert_eq!(a.exposure,0.0,"night sky must not be exposed to middle grey: {a:?}");
        assert_eq!(a.curve_darks,0.0,"black sky must not receive the +42 fire lift: {a:?}");
    }
}

// Restoration checks use synthetic scene-linear Rec.2020 fixtures;
// private user photographs remain outside the GitHub repository.
#[cfg(test)]
mod studio_v067_restoration_regressions {
    use super::*;

    #[test]
    fn firelight_preserves_ambient_warmth_in_auto_wb() {
        let img=Rgb32f::from_fn(192,144,|x,y|{
            if x>68 && x<122 && y>28 && y<91 {
                [1.18,0.73,0.14] // practical flame
            } else if y>102 {
                [0.203,0.178,0.163] // warm-lit neutral stone, not a calibration card
            } else {
                [0.007,0.005,0.003]
            }
        });
        let info=SourceInfo::default();
        let wb=auto_wb_report(&img,&info);
        assert_eq!(wb.temp,info.as_shot_temp,"warm scene cooled: {wb:?}");
        assert_eq!(wb.tint,info.as_shot_tint,"warm scene tinted: {wb:?}");
        assert!(wb.camera_balance_retained);
        assert_eq!(wb.decision,"warm_light_preserved",
            "Unexpected firelight decision: {wb:?}");
    }

    #[test]
    fn orange_lamplight_in_dark_room_remains_warm() {
        let img=Rgb32f::from_fn(192,144,|x,y|{
            if y<78 {[0.008,0.007,0.005]}
            else if x>25 && x<171 && y<135 {
                [0.203,0.178,0.163] // amber-lit walls with neutral-looking pixels
            } else {[0.02,0.012,0.008]}
        });
        let info=SourceInfo::default();
        let wb=auto_wb_report(&img,&info);
        assert_eq!((wb.temp,wb.tint),
            (info.as_shot_temp,info.as_shot_tint),
            "Warm atmospheric indoor photograph should retain as-shot WB: {wb:?}");
    }

    #[test]
    fn ordinary_neutral_cast_is_still_correctable() {
        let img=Rgb32f::from_fn(192,144,|x,y|{
            let v=if (x/32+y/24)%2==0 {0.19}else{0.31};
            [v*1.08,v,v*0.93]
        });
        let report=auto_wb_report(&img,&SourceInfo::default());
        assert_ne!(report.decision,"warm_light_preserved",
            "Normal scene must not be classified as firelight");
        assert!(report.confidence>0.14,"Lost neutral analysis: {report:?}");
    }

    #[test]
    fn blue_sky_dark_detailled_foreground_receives_local_recovery() {
        let img=Rgb32f::from_fn(256,192,|x,y|{
            let texture=((x*13+y*19)%29) as f32*0.0008;
            if y<70 {[0.60,0.78,0.97]}
            else if y>120 {[0.044+texture,0.060+texture,0.045+texture]}
            else {[0.022,0.032,0.020]}
        });
        let a=smart_auto_tone(&img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(a.shadows>0.0 || a.curve_darks>0.0,
            "Recoverable foreground ignored: {a:?}");
        assert!(a.exposure<0.25,"Global exposure risks clipping sky: {a:?}");
    }

    #[test]
    fn intentional_warm_sunset_still_has_no_forced_curve_lift() {
        let img=Rgb32f::from_fn(256,192,|x,y|{
            if y<70 {[0.80,0.34,0.12]}
            else if y<122 {[0.14,0.085,0.050]}
            else {[0.022,0.016,0.013]}
        });
        let a=smart_auto_tone(&img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(a.curve_darks<12.0,"Sunset unexpectedly treated as backlit land: {a:?}");
    }
}

#[cfg(test)]
mod studio_v068_refinement_regressions {
    use super::*;
    fn run(i:&Rgb32f)->AutoTone {
        smart_auto_tone(i,&SourceInfo::default(),&DevelopSettings::default())
    }
    #[test]
    fn balanced_textured_photo_gets_some_gentle_refinement() {
        let image=Rgb32f::from_fn(256,192,|x,y|{
            let grain=((x*17+y*11)%19) as f32*0.0013;
            if y<66 {[0.37+grain,0.45+grain,0.52+grain]}
            else if y<130 {[0.21+grain,0.31+grain,0.16+grain]}
            else {[0.105+grain,0.135+grain,0.095+grain]}
        });
        let a=run(&image);
        assert!(a.contrast>=2.0 || a.exposure.abs()>0.0
            || a.vibrance>0.0 || a.curve_lights>0.0,
            "A balanced but detailed photograph should still get a small polish: {a:?}");
        assert!(a.exposure.abs()<=0.10,"Polish must not become global re-exposure: {a:?}");
        assert!(a.contrast.abs()<=8.0,"Polish should be restrained: {a:?}");
    }

    #[test]
    fn mildly_dim_normal_photo_does_not_require_major_defect() {
        let image=Rgb32f::from_fn(256,192,|x,y|{
            let v=0.120+((x/12+y/11)%7) as f32*0.009;
            [v*1.04,v,v*0.93]
        });
        let a=run(&image);
        assert!(a.exposure>0.0 || a.contrast>0.0 || a.curve_lights>0.0,
            "Normal slightly dim photo should receive a gentle correction: {a:?}");
        assert!(a.exposure<0.20,"Subtle refinement should not wash out a scene: {a:?}");
    }

    #[test]
    fn neutral_flat_subject_keeps_colour_neutral() {
        let i=Rgb32f::filled(128,128,[0.18,0.18,0.18]);
        let a=run(&i);
        assert_eq!(a.vibrance,0.0);
        assert_eq!(a.saturation,0.0);
    }

    #[test]
    fn finishing_is_repeatable_without_changing_develop_settings() {
        let img=Rgb32f::from_fn(128,128,|x,y|{
            let v=0.12+((x*7+y*11)%20) as f32*0.007;
            [v,v*0.92,v*0.87]
        });
        let s=DevelopSettings::default();
        let a=smart_auto_tone(&img,&SourceInfo::default(),&s);
        let b=smart_auto_tone(&img,&SourceInfo::default(),&s);
        assert_eq!(a,b);
        assert_eq!(s,DevelopSettings::default());
    }
}

#[cfg(test)]
mod studio_v069_auto_audit_regressions {
    use super::*;
    #[test]
    fn report_final_matches_one_tap_auto() {
        let img=Rgb32f::from_fn(128,96,|x,y|{
            let v=0.075+((x*7+y*13)%41) as f32*0.002;
            [v,v*0.98,v*0.94]
        });
        let info=SourceInfo::default();
        let mut settings=DevelopSettings::default();
        settings.light.exposure=0.15;
        settings.light.contrast=9.0;
        let audit=smart_auto_tone_report(&img,&info,&settings);
        let applied=smart_auto_tone(&img,&info,&settings);
        assert_eq!(audit.final_tone,applied);
        assert_eq!(audit.comparison_exposure_ev,0.15);
        assert!(audit.evidence.analysed_pixels>300);
    }

    #[test]
    fn report_records_proposal_and_vetoes_separately() {
        let img=Rgb32f::from_fn(192,128,|x,_|
            if x<96 {[0.92,0.92,0.92]} else {[0.30,0.30,0.30]}
        );
        let a=AutoTone {exposure:0.9,curve_lights:20.0,..Default::default()};
        let outcome=preview_quality_guard_report(
            &img,&SourceInfo::default(),&DevelopSettings::default(),a
        );
        assert_eq!(outcome.tone.exposure,0.0);
        assert!(outcome.reasons.contains(&"added_highlight_clipping"));
        assert!(outcome.white_clip_change>0.012);
    }

    #[test]
    fn unchanged_candidate_uses_full_existing_settings() {
        let img=Rgb32f::filled(96,96,[0.18,0.18,0.18]);
        let mut s=DevelopSettings::default();
        s.light.exposure=0.8;
        s.light.contrast=11.0;
        let report=smart_auto_tone_report(&img,&SourceInfo::default(),&s);
        assert_eq!(report.comparison_exposure_ev,0.8);
        assert_eq!(report.final_tone,
            smart_auto_tone(&img,&SourceInfo::default(),&s));
    }
}

#[cfg(test)]
mod studio_v0610_wb_provenance_tests {
    use super::*;
    fn jpeg()->SourceInfo {SourceInfo::default()}

    #[test]
    fn cool_bright_reflections_get_lower_wb_confidence() {
        let pond=Rgb32f::from_fn(256,192,|x,y|{
            if x>93 && x<166 && y>68 && y<120 {
                [0.16,0.12,0.08] // brown duck on the water
            } else {
                let ripple=((x*13+y*17)%21) as f32*0.001;
                [0.61+ripple,0.625+ripple,0.646+ripple]
            }
        });
        let r=auto_wb_report(&pond,&jpeg());
        assert!(r.high_trust_neutral_fraction>0.15,
            "Expected bright reflective neutral-like area: {r:?}");
        assert!(r.bright_cool_dominance,"Cool pond reflection risk missed: {r:?}");
        assert!(r.confidence<0.40,"Reflected sky must not dominate JPEG WB: {r:?}");
    }

    #[test]
    fn near_neutral_label_not_outvoted_by_warmish_cloth() {
        let img=Rgb32f::from_fn(256,192,|x,y|{
            if (x>80 && x<116 && y>35 && y<135) ||
                (x>149 && x<182 && y>54 && y<158) {
                [0.38,0.375,0.37] // bright, nearly neutral labels
            }else{
                [0.21,0.185,0.17] // slightly warm clothing
            }
        });
        let r=auto_wb_report(&img,&jpeg());
        assert!(r.high_trust_neutral_fraction>0.04,"Lost label evidence: {r:?}");
        assert!(r.high_trust_neutral_tiles>=3,"Labels did not span cells: {r:?}");
        assert!(r.neutral_estimator_disagreement>0.01,
            "Need to observe broad vs strong neutral disagreement: {r:?}");
        assert!(!r.bright_cool_dominance);
    }

    #[test]
    fn cast_neutral_patch_can_still_be_corrected() {
        let image=Rgb32f::from_fn(256,192,|x,y|{
            let v=0.18+((x/20+y/19)%7) as f32*0.025;
            [v*1.08,v,v*0.94]
        });
        let r=auto_wb_report(&image,&jpeg());
        assert!(r.confidence>=0.14,"Moderate colour cast must remain analysable: {r:?}");
        assert_ne!(r.decision,"warm_light_preserved");
    }
}

#[cfg(test)]
mod studio_v0611_scene_auto_integration {
    use super::*;
    #[test]
    fn dark_subject_in_bright_reflections_gets_shadow_proposal() {
        let img=Rgb32f::from_fn(270,270,|x,y|{
            if (85..185).contains(&x) && (102..173).contains(&y) {
                let f=((x*7+y*13)%15) as f32/130.0;
                [0.08+f,0.07+f,0.055+f]
            } else {[0.65,0.67,0.69]}
        });
        let (candidate,special)=smart_auto_candidate(
            &img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(!special);
        assert!(candidate.shadows>=9.0,
            "Darker textured subject is invisible to existing Auto: {candidate:?}");
        assert!(candidate.exposure<=0.12,
            "Reflective highlights require preserving overall exposure: {candidate:?}");
        let audit=smart_auto_tone_report(
            &img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(audit.evidence.zonal.bright_surround_dark_detail);
        assert_eq!(audit.proposed,candidate);
    }

    #[test]
    fn texture_and_white_reference_allow_restrained_dark_fabric_development() {
        let img=Rgb32f::from_fn(270,270,|x,y|{
            if (120..150).contains(&x)&&(70..210).contains(&y) {
                [0.82,0.82,0.82]
            } else {
                let v=0.055+((x*11+y*13)%19) as f32*0.004;
                [v,v,v]
            }
        });
        let (candidate,_) =smart_auto_candidate(
            &img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(candidate.shadows>=5.0,
            "Dark textile detail should be considered: {candidate:?}");
        assert!(candidate.exposure<=0.18,
            "Do not normalize black clothing to middle-grey: {candidate:?}");
    }

    #[test]
    fn flat_low_key_night_scene_is_not_forced_into_daylight() {
        let img=Rgb32f::from_fn(270,270,|x,y|{
            if x>124 && x<145 && y>84 && y<139 {[0.90,0.58,0.15]}
            else {[0.005,0.005,0.005]}
        });
        let (candidate,special)=smart_auto_candidate(
            &img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(!special || candidate.exposure==0.0);
        assert_eq!(candidate.exposure,0.0);
        assert_eq!(candidate.curve_lights,0.0);
    }
}

#[cfg(test)]
mod studio_v0612d_fully_unrestricted_auto {
    use super::*;
    #[test]
    fn all_guards_off_reports_exact_applied_proposal() {
        let photo=Rgb32f::from_fn(192,128,|x,y|{
            let v=0.020+((x*19+y*11)%31) as f32*0.0005;
            [v*1.05,v,v*0.9]
        });
        let s=DevelopSettings::default();
        let report=smart_auto_tone_report_all_guards_off(
            &photo,&SourceInfo::default(),&s);
        assert_eq!(report.proposed,report.final_tone);
        assert_eq!(report.guard_reasons,vec!["all_photographic_auto_guards_disabled_test_only"]);
        assert!(report.final_tone.exposure>0.1,
            "dark photo still vetoed: {:?}",report.final_tone);
    }

    #[test]
    fn low_key_scene_no_longer_blocks_exposure() {
        let photo=Rgb32f::from_fn(192,128,|x,y|{
            if x>70 && x<122 && y>25 && y<105 {[0.10,0.065,0.045]}
            else {[0.003,0.003,0.003]}
        });
        let s=DevelopSettings::default();
        let report=smart_auto_tone_report_all_guards_off(
            &photo,&SourceInfo::default(),&s);
        assert!(report.final_tone.exposure>0.0,
            "mood protection remains: {:?}",report.final_tone);
    }
}

#[cfg(test)]
mod studio_v0612c_unguarded_diagnostics {
    use super::*;
    #[test]
    fn unguarded_report_is_exact_proposal_with_explicit_bypass_reason() {
        let img=Rgb32f::from_fn(270,270,|x,y|{
            if (85..185).contains(&x) && (102..173).contains(&y) {
                let d=((x*7+y*13)%15) as f32/130.0;
                [0.08+d,0.07+d,0.055+d]
            }else{[0.65,0.67,0.69]}
        });
        let settings=DevelopSettings::default();
        let (candidate,_)=smart_auto_candidate(&img,&SourceInfo::default(),&settings);
        let audit=smart_auto_tone_report_unguarded(&img,&SourceInfo::default(),&settings);
        assert_eq!(audit.proposed,candidate);
        assert_eq!(audit.final_tone,candidate);
        assert_eq!(audit.guard_reasons,vec!["experimental_render_quality_guards_bypassed"]);
    }

    #[test]
    fn baseline_guarded_report_remains_available_for_comparison() {
        let img=Rgb32f::filled(96,96,[0.20,0.20,0.20]);
        let info=SourceInfo::default();
        let settings=DevelopSettings::default();
        let guarded=smart_auto_tone_report(&img,&info,&settings);
        let unguarded=smart_auto_tone_report_unguarded(&img,&info,&settings);
        assert_eq!(guarded.final_tone,smart_auto_tone(&img,&info,&settings));
        assert_eq!(guarded.proposed,unguarded.proposed);
        assert_eq!(unguarded.final_tone,unguarded.proposed);
    }
}

#[cfg(test)]
mod studio_v0612_assertive_test {
    use super::*;

    #[test]
    fn clipping_swapped_between_regions_is_detected_despite_unchanged_total() {
        // Fifty clipped pixels move to a different 50 pixels. A difference
        // of global clip totals would incorrectly report zero newly clipped.
        let mut before=vec![[220,220,220,255];100];
        let mut after=before.clone();
        for i in 0..50 {
            before[i]=[255,255,255,255];
            after[i]=[220,220,220,255];
        }
        for i in 50..100 { after[i]=[255,255,255,255]; }
        let actual=newly_clipped_rgb_fraction(&before,&after,true);
        assert!((actual-0.5).abs()<1e-9,"lost newly clipped highlights: {actual}");
        assert_eq!(newly_clipped_rgb_fraction(&before,&before,true),0.0);
    }

    #[test]
    fn new_black_clipping_cannot_cancel_against_recovered_blacks() {
        let mut before=vec![[12,12,12,255];100];
        let mut after=before.clone();
        for i in 0..50 {
            before[i]=[0,0,0,255];
            after[i]=[12,12,12,255];
        }
        for i in 50..100 { after[i]=[0,0,0,255]; }
        let actual=newly_clipped_rgb_fraction(&before,&after,false);
        assert!((actual-0.5).abs()<1e-9,"lost newly clipped shadows: {actual}");
        assert_eq!(newly_clipped_rgb_fraction(&before,&before,false),0.0);
    }
    #[test]
    fn strong_underexposure_with_real_detail_now_proposes_meaningful_exposure() {
        let img=Rgb32f::from_fn(256,192,|x,y|{
            let t=((x*17+y*29)%25) as f32/25.0;
            let v=0.060+t*0.022;
            [v*1.04,v,v*0.92]
        });
        let (a,_) = smart_auto_candidate(
            &img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(a.exposure>=0.24,
            "Test must challenge historical exposure cap / defect veto: {a:?}");
    }
    #[test]
    fn bright_water_with_dark_subject_can_propose_joint_adjustment() {
        let img=Rgb32f::from_fn(270,270,|x,y|{
            if (85..185).contains(&x) && (102..173).contains(&y) {
                let d=((x*7+y*13)%15) as f32/130.0;
                [0.08+d,0.07+d,0.055+d]
            }else{[0.65,0.67,0.69]}
        });
        let (a,_) =smart_auto_candidate(
            &img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(a.shadows>=18.0,"Dark feathers not addressed: {a:?}");
        assert!(a.exposure<=0.12,"Water should not be globally brightened: {a:?}");
    }
    #[test]
    fn all_tone_controls_are_included_in_experimental_render_guard() {
        let img=Rgb32f::from_fn(128,128,|x,y|{
            let d=((x*3+y*5)%13) as f32/100.0;
            [0.28+d,0.30+d,0.32+d]
        });
        let proposal=AutoTone {whites:-10.0,..AutoTone::default()};
        let report=preview_quality_guard_report(
            &img,&SourceInfo::default(),&DevelopSettings::default(),proposal);
        assert_eq!(report.tone.whites,-10.0);
    }
}
