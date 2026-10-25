//! Experimental Photo Studio-inspired Auto decision model for LumaWorks.
//!
//! Ported from the finished Photo Studio v0.6.20 SpatialAuto64/SceneJudgement16/
//! SceneAtmosphere8/SceneIntent4 principles. Pixel rendering remains the existing
//! non-destructive, scene-linear LumaWorks pipeline; this is NOT a byte-identical
//! port of the Java spatial renderer. It never modifies the finished Photo Studio.
//! The 64x64 grid is a *decision input* in this first experiment; its regional
//! weight map is approximated by editable parametric curves, not a pixel mask.

use lightcraft_color::luminance_2020;
use lightcraft_develop::DevelopSettings;
use lightcraft_raster::Rgb32f;
use serde::Serialize;
use crate::{SourceInfo, auto::AutoTone};

const GRID: usize = 64;
const CELLS: usize = GRID * GRID;

#[derive(Clone, Copy, Debug, Default, Serialize)]
pub struct StudioEvidence {
    pub p05: f64,
    pub median: f64,
    pub p95: f64,
    pub bright_fraction: f64,
    pub dark_zones_16: usize,
    pub exposed_zones_16: usize,
    pub dark_blocks_8: usize,
    pub dark_blocks_4: usize,
    pub warm_glow_fraction: f64,
    pub intentional_low_key: bool,
    pub protected_warm_atmosphere: bool,
    pub near_black_protected: bool,
    pub region_shadow_weight: f64,
}

fn quantile(hist: &[usize;256], n:usize, part:f32)->f64 {
    let goal=((n as f32 * part).ceil() as usize).max(1);
    let mut acc=0;
    for (i,k) in hist.iter().enumerate() {
        acc+=*k;
        if acc>=goal {return i as f64/255.0;}
    }
    1.0
}
fn coarse(sums:&[f64;CELLS], counts:&[usize;CELLS], side:usize)->Vec<f64> {
    let step=GRID/side;
    let mut out=Vec::with_capacity(side*side);
    for y in 0..side {
        for x in 0..side {
            let mut value=0.0;
            let mut n=0usize;
            for dy in 0..step {
                for dx in 0..step {
                    let index=(y*step+dy)*GRID+(x*step+dx);
                    value+=sums[index];
                    n+=counts[index];
                }
            }
            out.push(if n>0 {value/n as f64} else {0.0});
        }
    }
    out
}

/// Re-express Photo Studio's photographic *decisions* as LumaWorks editable
/// controls. The transfer is not a numeric copy: Photo Studio operates on
/// already-rendered sRGB and LumaWorks on scene-linear Rec.2020.
pub fn analyse(src:&Rgb32f, info:&SourceInfo, s:&DevelopSettings)
    -> (AutoTone,StudioEvidence) {
    let mut img=lightcraft_raster::resample::fit(
        src,512,512,lightcraft_raster::resample::Filter::Box);
    let base=DevelopSettings {wb:s.wb, ..DevelopSettings::default()};
    crate::local::scene_linear_pre(&mut img,info,&base);
    if img.width==0 || img.height==0 || img.data.is_empty() {
        return (AutoTone::default(),StudioEvidence::default());
    }
    let mut sums=[0.0f64;CELLS];
    let mut counts=[0usize;CELLS];
    let mut hist=[0usize;256];
    let mut bright=0usize;
    let mut warm=0usize;
    for (index,p) in img.data.iter().enumerate() {
        // A display-referred proxy for the Java 8-bit Photo Studio estimator.
        // Clamp only for scene classification; keep original linear data for
        // the actual LumaWorks render and its quality-guard comparison.
        let value=luminance_2020(*p).max(0.0).powf(1.0/2.2).clamp(0.0,1.0);
        let bucket=(value*255.0).round() as usize;
        hist[bucket.min(255)]+=1;
        let y=index/img.width;
        let x=index%img.width;
        let tile=(y*GRID/img.height).min(GRID-1)*GRID+
            (x*GRID/img.width).min(GRID-1);
        sums[tile]+=value as f64;
        counts[tile]+=1;
        let rgb=p.map(|v|v.max(0.0).powf(1.0/2.2).clamp(0.0,1.0));
        let hi=rgb[0].max(rgb[1]).max(rgb[2]);
        if hi>=0.93 && value>0.47 {bright+=1;}
        if rgb[0]>0.55 && rgb[1]>0.21 &&
            rgb[0]>rgb[1]*1.28 && rgb[1]>rgb[2]*1.50 &&
            rgb[1]-rgb[2]>0.11 && value>0.25 { warm+=1; }
    }
    let n=img.data.len();
    let p05=quantile(&hist,n,0.05);
    let median=quantile(&hist,n,0.50);
    let p95=quantile(&hist,n,0.95);
    let p85=quantile(&hist,n,0.85);
    let p99=quantile(&hist,n,0.99);
    let coverage=bright as f64/n as f64;
    let warm_coverage=warm as f64/n as f64;
    let sixteen=coarse(&sums,&counts,16);
    let eight=coarse(&sums,&counts,8);
    let four=coarse(&sums,&counts,4);
    let dark16=sixteen.iter().filter(|&&v|v<0.17).count();
    let exposed16=sixteen.iter().filter(|&&v|v>=0.40).count();
    let mid16=sixteen.iter().filter(|&&v|v>=0.32).count();
    let dark8=eight.iter().filter(|&&v|v<0.16).count();
    let bright8=eight.iter().filter(|&&v|v>0.37).count();
    let dark4=four.iter().filter(|&&v|v<0.145).count();
    let near_black4=four.iter().filter(|&&v|v<0.075).count();
    let illuminated4=four.iter().filter(|&&v|v>0.225).count();
    let brightest4=four.iter().copied().fold(0.0f64,f64::max);
    let mut clustered16=0;
    for y in 1..15 {
        for x in 1..15 {
            let k=y*16+x;
            if sixteen[k]<0.34 {continue;}
            let neighbours=[k-1,k+1,k-16,k+16].iter()
                .filter(|&&v|sixteen[v]>=0.30).count();
            if neighbours>=2 {clustered16+=1;}
        }
    }
    // The v0.6.20 Photo Studio judgements, mapped to a gamma-encoded proxy.
    let lit_region=clustered16>=12 && mid16>=20 && p85>=0.39 && p95>=0.51;
    let dark_scene=p85<0.35 && p95<0.49 &&
        exposed16<20 && mid16<45;
    let low_key=lit_region && dark16>=55 && p05<0.17 &&
        median<0.29 && coverage<0.12;
    let warm_atmosphere=warm_coverage>=0.023 && dark8>=24 &&
        median<68.0/255.0 && p95>=44.0/255.0 && p99>=90.0/255.0;
    let night_lights=!warm_atmosphere && dark8>=53 &&
        median<19.0/255.0 && p95>=42.0/255.0 &&
        p99>=135.0/255.0 && coverage>=0.009 && bright8<=5;
    let protected_atmosphere=warm_atmosphere || night_lights;
    let macro_protection=protected_atmosphere && dark4>=6 &&
        near_black4>=2 && (1..=8).contains(&illuminated4) &&
        brightest4>=0.29 && p95>=73.0/255.0 && p99>=151.0/255.0;
    let important_region=!dark_scene && dark16>=72 &&
        median<82.0/255.0 && p85>=97.0/255.0 &&
        p95>=123.0/255.0 && clustered16>=9;
    let keep_black=p05<27.0/255.0 && dark16>=72 &&
        (protected_atmosphere || macro_protection || low_key || important_region);
    let mut ceiling=if p95>0.84 || coverage>0.10 {0.025}
        else if p95>0.71 || coverage>0.025 {0.085}
        else if p95>0.62 {0.18} else {0.45};
    let mut shadow_scale=1.0f64;
    if low_key {
        ceiling=ceiling.min(0.07);
        shadow_scale=0.55;
    }else if lit_region && median<0.30 {
        ceiling=ceiling.min(0.14);
        shadow_scale=0.78;
    }else if !dark_scene && p85>=0.45 &&
        median<0.30 && dark16>=35 {
        ceiling=ceiling.min(0.20);
        shadow_scale=0.85;
    }
    if protected_atmosphere {
        ceiling=ceiling.min(if warm_atmosphere {0.025} else {0.045});
        shadow_scale=shadow_scale.min(if warm_atmosphere {0.22}else{0.32});
    }
    if macro_protection {
        ceiling=ceiling.min(0.015);
        shadow_scale=shadow_scale.min(0.14);
    }
    if important_region {
        ceiling=ceiling.min(0.09);
        shadow_scale=shadow_scale.min(0.66);
    }
    let exposure=(0.36/median.max(0.09)).log2().clamp(-0.16,ceiling);
    let shadows=(if p05<40.0/255.0 {15.0}
        else if p05<70.0/255.0 {11.0} else {6.0})*shadow_scale;
    let highlights=if p95>0.83 {-13.0} else if p95>0.71 {-8.0} else {0.0};
    let contrast=((0.60-(p95-p05))*11.0).clamp(-4.0,4.0);
    let mut local_shadow=0.0f64;
    for i in 0..CELLS {
        let v=if counts[i]>0 {sums[i]/counts[i] as f64}
            else {median};
        if v>=0.055 && v<0.49 {
            let weight=((0.49-v)/0.39).clamp(0.0,1.0);
            local_shadow+=if keep_black && v<0.17 {
                weight*0.75
            }else{weight};
        }
    }
    local_shadow/=CELLS as f64;
    // Photo Studio's per-pixel 64x64 spatial map is not yet present in
    // LumaWorks. Modest parametric-curve support approximates regional tone.
    let curve_shadows=if protected_atmosphere {0.0}
        else {(local_shadow*15.0*shadow_scale).clamp(0.0,9.0)};
    let evidence=StudioEvidence {
        p05,median,p95,bright_fraction:coverage,
        dark_zones_16:dark16,exposed_zones_16:exposed16,
        dark_blocks_8:dark8,dark_blocks_4:dark4,
        warm_glow_fraction:warm_coverage,
        intentional_low_key:low_key,
        protected_warm_atmosphere:protected_atmosphere,
        near_black_protected:keep_black,
        region_shadow_weight:local_shadow,
    };
    let tone=AutoTone {
        exposure:(exposure*100.0).round()/100.0,
        contrast:contrast.round(),
        highlights:(highlights*1.45).round(),
        shadows:(shadows*1.25).round(),
        whites:0.0, blacks:0.0,
        vibrance:0.0,saturation:0.0,
        curve_shadows:curve_shadows.round(),
        curve_darks:0.0,
        curve_lights:0.0,
        curve_highlights:0.0,
    };
    (tone,evidence)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bright_white_subject_does_not_get_a_global_lift() {
        let mut img=Rgb32f::filled(128,128,[0.06,0.06,0.06]);
        for y in 35..95 { for x in 30..100 {img.data[y*128+x]=[0.98,0.98,0.98];} }
        let (tone,_) = analyse(&img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(tone.exposure<=0.025, "bright subject must be protected: {tone:?}");
    }
    #[test]
    fn genuinely_underexposed_gray_has_recoverable_auto() {
        let img=Rgb32f::filled(128,128,[0.018,0.018,0.018]);
        let (tone,_) = analyse(&img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(tone.exposure>0.0, "underexposure should receive correction: {tone:?}");
    }
    #[test]
    fn sunlight_and_shade_have_bounded_parameters() {
        let mut img=Rgb32f::filled(128,128,[0.075,0.09,0.065]);
        for y in 0..64 {for x in 0..128 {img.data[y*128+x]=[0.75,0.83,0.92];}}
        let (tone,_) = analyse(&img,&SourceInfo::default(),&DevelopSettings::default());
        assert!(tone.highlights<=0.0 && tone.shadows>=0.0);
        assert!((-0.16..=0.45).contains(&tone.exposure));
    }
}
