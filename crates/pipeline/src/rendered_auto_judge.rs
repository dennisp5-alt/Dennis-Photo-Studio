//! Offline rendered-candidate evaluator for the experimental Auto trial.
//!
//! This is an explainable *heuristic*, not a trained model or face-recognition
//! system. It judges candidate renders in display sRGB and cannot infer
//! ground-truth scene illuminants, face identity or recover clipped capture data.
//! Suggestions stay non-destructive; the existing downstream quality guard,
//! RAW controls, masking and neutral-reference WB remain unchanged.

use crate::{SourceInfo, auto::AutoTone};
use lightcraft_develop::DevelopSettings;
use lightcraft_raster::{Rgb32f, Rgba8};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Scene {
    PortraitLike, VividLowKey, SunnyMixed, General,
}
#[derive(Clone, Copy, Debug, Default)]
struct Measures {
    mean: f64,
    median: f64,
    bright_fraction: f64,
    dark_fraction: f64,
    p95: f64,
    edge_energy: f64,
    skin_fraction: f64,
    skin_mean: f64,
    vivid_fraction: f64,
    blue_sky_fraction: f64,
}
fn luma(p: &[u8;4])->f64 {
    (0.2126*p[0] as f64+0.7152*p[1] as f64+0.0722*p[2] as f64)/255.0
}
fn central_skin(p:&[u8;4], x:usize,y:usize,w:usize,h:usize)->bool {
    // Only chromatic *appearance* inside the central portrait area.
    // A rust wall might produce the same evidence: this is not face detection.
    if x*5 < w || x*5 > 4*w || y*8 < h || y*8 > 7*h {return false;}
    let (r,g,b)=(p[0] as f64,p[1] as f64,p[2] as f64);
    r>65.0 && g>45.0 && b>35.0 && r>g*1.06 &&
        g>b*1.015 && r<g*1.80 && g<b*1.80 &&
        (r-g)<75.0 && (g-b)<67.0
}
fn measurements(img:&Rgba8)->Measures {
    if img.is_empty() || img.width==0 || img.height==0 {return Measures::default();}
    let (mut sum,mut dark,mut bright,mut vivid,mut blue,mut skin,mut skin_sum,mut edges)=
        (0.0,0usize,0usize,0usize,0usize,0usize,0.0,0.0);
    let mut hist=[0usize;256];
    let w=img.width;
    let h=img.height;
    for (i,p) in img.data.iter().enumerate() {
        let y=i/w;
        let x=i%w;
        let lum=luma(p);
        sum+=lum;
        hist[((lum*255.0).round() as usize).min(255)]+=1;
        if lum<0.13 {dark+=1;}
        if p[0]>=251 || p[1]>=251 || p[2]>=251 {bright+=1;}
        let top=p[0].max(p[1]).max(p[2]) as f64;
        let bottom=p[0].min(p[1]).min(p[2]) as f64;
        if top>140.0 && (top-bottom)>0.43*top {vivid+=1;}
        if y*2<h && p[2] as f64>p[0] as f64*1.14 &&
            p[1] as f64>p[0] as f64*1.04 && p[2]>135 {blue+=1;}
        if central_skin(p,x,y,w,h) {skin+=1;skin_sum+=lum;}
        if x>0 {edges+=(lum-luma(&img.data[i-1])).abs();}
    }
    let quantile=|fraction:f64| {
        let mut seen=0usize;
        let goal=((img.len() as f64*fraction).ceil() as usize).max(1);
        for (i,count) in hist.iter().enumerate() {
            seen+=*count;
            if seen>=goal {return i as f64/255.0;}
        }
        1.0
    };
    let total=img.len() as f64;
    Measures {
        mean:sum/total, median:quantile(0.50),p95:quantile(0.95),
        bright_fraction:bright as f64/total,
        dark_fraction:dark as f64/total,
        skin_fraction:skin as f64/total,
        skin_mean:skin_sum/(skin.max(1) as f64),
        vivid_fraction:vivid as f64/total,
        blue_sky_fraction:blue as f64/total,
        edge_energy:edges/total,
    }
}
fn scene(m:Measures)->Scene {
    if m.skin_fraction>0.070 && m.skin_mean>0.25 && m.skin_mean<0.89 {
        Scene::PortraitLike
    }else if m.dark_fraction>0.39 && m.vivid_fraction>0.065 {
        Scene::VividLowKey
    }else if m.blue_sky_fraction>0.08 && m.p95>0.78 && m.dark_fraction<0.42 {
        Scene::SunnyMixed
    }else {Scene::General}
}
fn tone_of(s:&DevelopSettings)->AutoTone {
    AutoTone {
        exposure:s.light.exposure,contrast:s.light.contrast,
        highlights:s.light.highlights,shadows:s.light.shadows,
        whites:s.light.whites,blacks:s.light.blacks,
        vibrance:s.color.vibrance,saturation:s.color.saturation,
        curve_shadows:s.curve.shadows,curve_darks:s.curve.darks,
        curve_lights:s.curve.lights,curve_highlights:s.curve.highlights,
    }
}
fn scaled(from:AutoTone,to:AutoTone,t:f64)->AutoTone {
    let mix=|a:f64,b:f64|a+(b-a)*t;
    AutoTone {
        exposure:mix(from.exposure,to.exposure),
        contrast:mix(from.contrast,to.contrast),
        highlights:mix(from.highlights,to.highlights),
        shadows:mix(from.shadows,to.shadows),
        whites:mix(from.whites,to.whites),
        blacks:mix(from.blacks,to.blacks),
        vibrance:mix(from.vibrance,to.vibrance),
        saturation:mix(from.saturation,to.saturation),
        curve_shadows:mix(from.curve_shadows,to.curve_shadows),
        curve_darks:mix(from.curve_darks,to.curve_darks),
        curve_lights:mix(from.curve_lights,to.curve_lights),
        curve_highlights:mix(from.curve_highlights,to.curve_highlights),
    }
}
fn settings_for(original:&DevelopSettings,tone:AutoTone)->DevelopSettings {
    let mut s=original.clone();
    s.light.exposure=tone.exposure;
    s.light.contrast=tone.contrast;
    s.light.highlights=tone.highlights;
    s.light.shadows=tone.shadows;
    s.light.whites=tone.whites;
    s.light.blacks=tone.blacks;
    s.color.vibrance=tone.vibrance;
    s.color.saturation=tone.saturation;
    s.curve.shadows=tone.curve_shadows;
    s.curve.darks=tone.curve_darks;
    s.curve.lights=tone.curve_lights;
    s.curve.highlights=tone.curve_highlights;
    s
}
fn newly_clipped_fraction(before:&Rgba8,after:&Rgba8)->f64 {
    if before.len()!=after.len() || before.is_empty(){return 0.0;}
    let mut fresh=0usize;
    for (b,a) in before.data.iter().zip(&after.data) {
        for channel in 0..3 {
            if b[channel]<251 && a[channel]>=253 {fresh+=1;}
        }
    }
    fresh as f64/(before.len()*3) as f64
}
fn evaluate(before:&Rgba8,after:&Rgba8,reference:Measures,kind:Scene)->f64 {
    if before.is_empty() || before.len()!=after.len() {return f64::NEG_INFINITY;}
    let now=measurements(after);
    let shift=now.mean-reference.mean;
    let midtone=now.median-reference.median;
    let new_clip=newly_clipped_fraction(before,after);
    // Safety dominates any aesthetic preference; already-clipped camera
    // highlights are excluded. New white clipping earns a strong penalty.
    let mut score=-(new_clip*600.0);
    score-=(now.bright_fraction-reference.bright_fraction).max(0.0)*95.0;
    // Avoid flat contrast, crushed blacks, or excessive large-scale changes.
    let edge_ratio=now.edge_energy/reference.edge_energy.max(1e-3);
    score-=(1.0-edge_ratio).max(0.0)*4.5;
    score-=(reference.dark_fraction-now.dark_fraction).max(0.0)*2.2;
    score-=shift.abs()*1.8;
    score-=midtone.abs()*2.0;
    match kind {
        Scene::PortraitLike=>{
            // Compare only patches that originally resembled central skin,
            // keeping false-positive colour samples from becoming "faces".
            let mut n=0usize;
            let (mut change,mut colour_drift,mut washed)= (0.0,0.0,0usize);
            for (i,(b,a)) in before.data.iter().zip(&after.data).enumerate() {
                let (x,y)=(i%before.width,i/before.width);
                if !central_skin(b,x,y,before.width,before.height){continue;}
                n+=1;
                change+=(luma(a)-luma(b)).abs();
                let chr=|p:&[u8;4]|{
                    ((p[0] as f64-p[1] as f64)/255.0,
                     (p[1] as f64-p[2] as f64)/255.0)
                };
                let (r0,b0)=chr(b);
                let (r1,b1)=chr(a);
                colour_drift+=(r1-r0).abs()+(b1-b0).abs();
                if a[0]>=252 || a[1]>=252 || a[2]>=252 {washed+=1;}
            }
            if n>0 {
                score-=change/n as f64*30.0;
                score-=colour_drift/n as f64*19.0;
                score-=washed as f64/n as f64*16.0;
            }
            // Portraits often contain dark hair/clothes; a small lift can
            // improve separation without forcing an artificial face tone.
            let desired=if reference.dark_fraction>0.30 {0.018}else{0.0};
            score-=(shift-desired).abs()*5.0;
        },
        Scene::VividLowKey=>{
            // Isolated coloured subject against dark foliage: protect the
            // subject and allow a modest recovery of shadows without turning
            // the intended background charcoal.
            score-=(shift-0.030).abs()*13.0;
            let shadow_reveal=(reference.dark_fraction-now.dark_fraction).clamp(0.0,0.13);
            score+=shadow_reveal*4.0;
            score-=(now.dark_fraction-reference.dark_fraction).max(0.0)*4.0;
        },
        Scene::SunnyMixed=>{
            // The sky can be brilliantly blue, not necessarily blown white.
            // Avoid losing the convincing daylight brightness seen in v0.6.23.
            score-=(shift+0.008).abs()*13.0;
            score-=(now.p95-reference.p95).min(0.0).abs()*8.0;
        },
        Scene::General=>{
            if reference.mean<0.34 {
                score-=(shift-0.035).abs()*9.0;
            }else if reference.mean>0.65 {
                score-=(shift+0.015).abs()*8.0;
            }else {
                score-=shift.abs()*5.0;
            }
        },
    }
    score
}
/// Compare up to four *actually rendered* alternatives, using a fixed small
/// preview. Do not depend on UI slider equivalence or Adobe's closed engine.
/// Retain the seed candidate on render failure or low-confidence differences.
pub fn choose(src:&Rgb32f,info:&SourceInfo,s:&DevelopSettings,seed:AutoTone)->AutoTone {
    if src.width<16 || src.height<16 {return seed;}
    let proxy=lightcraft_raster::resample::fit(
        src,128,128,lightcraft_raster::resample::Filter::Box);
    let req=crate::RenderRequest::fit(128,128);
    let original=crate::render(&proxy,info,s,&req).image;
    if original.is_empty(){return seed;}
    let evidence=measurements(&original);
    let category=scene(evidence);
    // Compute both frame and 3x3 regional histograms once on the original
    // rendered sRGB preview. White balance is intentionally not changed.
    let histogram=crate::histogram_auto::analyse(&original);
    let current=tone_of(s);
    let mut gentle=scaled(current,seed,0.65);
    // This maintains original camera colour and WB when the scene needs no
    // decisive change. Saturation proposals remain opt-in via the existing
    // photographic rules rather than a blanket vibrance increase.
    gentle.exposure=(gentle.exposure*100.0).round()/100.0;
    let mut subject=seed;
    match category {
        Scene::PortraitLike=>{
            subject.exposure=(seed.exposure*0.65).clamp(-0.30,0.35);
            subject.highlights=(seed.highlights-7.0).clamp(-50.0,0.0);
            subject.shadows=(seed.shadows+8.0).clamp(0.0,35.0);
            subject.blacks=(seed.blacks-3.0).clamp(-12.0,5.0);
        },
        Scene::VividLowKey=>{
            subject.exposure=(seed.exposure+0.14).clamp(-0.12,0.75);
            subject.highlights=(seed.highlights-9.0).clamp(-56.0,0.0);
            subject.shadows=(seed.shadows+9.0).clamp(0.0,44.0);
            subject.blacks=(seed.blacks-4.0).clamp(-15.0,3.0);
        },
        Scene::SunnyMixed=>{
            subject.exposure=(seed.exposure+0.08).clamp(-0.07,0.16);
            subject.highlights=(seed.highlights+7.0).min(0.0);
            subject.shadows=(seed.shadows+7.0).clamp(0.0,32.0);
        },
        Scene::General=>{
            subject.exposure=(seed.exposure+if evidence.mean<0.36{0.12}else{-0.03})
                .clamp(-0.35,0.50);
            subject.highlights=(seed.highlights-5.0).clamp(-48.0,5.0);
            subject.shadows=(seed.shadows+5.0).clamp(-5.0,38.0);
        },
    }
    let portrait=category==Scene::PortraitLike;
    let low_key=category==Scene::VividLowKey;
    let sunny=category==Scene::SunnyMixed;
    let histogram_tone=histogram.as_ref().map(|ev|
        crate::histogram_auto::propose(ev,seed,portrait,low_key,sunny)
    ).unwrap_or(seed);
    // Preserve all four v0.6.25 proposals. Add a histogram-led suggestion
    // and a softer blend; actual rendered output still decides the winner.
    let options=[seed,gentle,subject,scaled(gentle,subject,0.5),
                 histogram_tone,scaled(gentle,histogram_tone,0.7)];
    let mut best=seed;
    let mut best_score=f64::NEG_INFINITY;
    for candidate in options {
        let rendered=crate::render(&proxy,info,&settings_for(s,candidate),&req).image;
        let mut score=evaluate(&original,&rendered,evidence,category);
        if let (Some(original_hist),Some(candidate_hist))=
            (histogram.as_ref(),crate::histogram_auto::analyse(&rendered).as_ref()) {
            score+=crate::histogram_auto::score(
                original_hist,candidate_hist,portrait,low_key,sunny
            )*1.4;
        }
        if score>best_score {
            best_score=score;
            best=candidate;
        }
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn recognises_central_skin_like_tones_without_claiming_a_face() {
        let portrait=Rgba8::from_fn(64,64,|x,y|{
            if x>15 && x<49 && y>12 && y<50 {[194,154,136,255]}
            else {[18,24,34,255]}
        });
        assert_eq!(scene(measurements(&portrait)),Scene::PortraitLike);
    }
    #[test]
    fn penalises_new_clipped_skin_channels() {
        let original=Rgba8::filled(64,64,[194,154,136,255]);
        let adjusted=Rgba8::filled(64,64,[255,235,229,255]);
        let neutral=measurements(&original);
        assert!(evaluate(&original,&adjusted,neutral,Scene::PortraitLike)<
                evaluate(&original,&original,neutral,Scene::PortraitLike));
    }
    #[test]
    fn strong_daylight_dimming_is_not_rewarded() {
        let original=Rgba8::from_fn(80,64,|_,y|{
            if y<30 {[164,236,250,255]} else {[88,128,79,255]}
        });
        let dim=Rgba8::from_fn(80,64,|_,y|{
            if y<30 {[38,125,184,255]} else {[61,93,51,255]}
        });
        let basis=measurements(&original);
        assert_eq!(scene(basis),Scene::SunnyMixed);
        assert!(evaluate(&original,&dim,basis,Scene::SunnyMixed)<
                evaluate(&original,&original,basis,Scene::SunnyMixed));
    }
}
