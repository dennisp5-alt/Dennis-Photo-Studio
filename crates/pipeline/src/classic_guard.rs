//! v0.6.32: progressive, final-render validation for the isolated Classic Auto.
use crate::{SourceInfo,RenderRequest,auto::{AutoTone,AutoToneAuditReport,AutoWbReport},scene_auto::SceneReport};
use lightcraft_develop::{DevelopSettings,WbMode};
use lightcraft_raster::Rgb32f;
use serde::Serialize;

#[derive(Debug,Clone,Serialize)]
pub struct ClassicValidation {
    pub passed:bool,
    pub strength:f64,
    pub iterations:usize,
    pub white_clipping:f64,
    pub black_clipping:f64,
    pub dark_lift:f64,
    pub reasons:Vec<&'static str>,
}
pub struct ClassicPlan {
    pub settings:DevelopSettings,
    pub tone:AutoToneAuditReport,
    pub white_balance:Option<AutoWbReport>,
    pub scene:SceneReport,
    pub guard:ClassicValidation,
    pub preset_preserved:bool,
}
fn tone(d:&DevelopSettings)->AutoTone {
    AutoTone {
        exposure:d.light.exposure,contrast:d.light.contrast,
        highlights:d.light.highlights,shadows:d.light.shadows,
        whites:d.light.whites,blacks:d.light.blacks,
        vibrance:d.color.vibrance,saturation:d.color.saturation,
        curve_shadows:d.curve.shadows,curve_darks:d.curve.darks,
        curve_lights:d.curve.lights,curve_highlights:d.curve.highlights,
    }
}
fn mix(a:f64,b:f64,s:f64)->f64 {a+(b-a)*s}
fn scaled(base:&DevelopSettings,proposed:&DevelopSettings,t:f64,w:f64,h:f64)->DevelopSettings {
    let mut d=base.clone();
    d.light.exposure=mix(base.light.exposure,proposed.light.exposure,t);
    d.light.contrast=mix(base.light.contrast,proposed.light.contrast,t);
    d.light.highlights=mix(base.light.highlights,proposed.light.highlights,t);
    d.light.shadows=mix(base.light.shadows,proposed.light.shadows,t);
    d.light.whites=mix(base.light.whites,proposed.light.whites,t);
    d.light.blacks=mix(base.light.blacks,proposed.light.blacks,t);
    d.color.vibrance=mix(base.color.vibrance,proposed.color.vibrance,t);
    d.color.saturation=mix(base.color.saturation,proposed.color.saturation,t);
    d.curve.shadows=mix(base.curve.shadows,proposed.curve.shadows,t);
    d.curve.darks=mix(base.curve.darks,proposed.curve.darks,t);
    d.curve.lights=mix(base.curve.lights,proposed.curve.lights,t);
    d.curve.highlights=mix(base.curve.highlights,proposed.curve.highlights,t);
    d.effects.dehaze=mix(base.effects.dehaze,proposed.effects.dehaze,h);
    if base.wb!=proposed.wb && w>0.09 {
        let old=1e6/base.wb.temp.clamp(2000.0,50000.0);
        let new=1e6/proposed.wb.temp.clamp(2000.0,50000.0);
        d.wb.mode=proposed.wb.mode;
        d.wb.temp=1e6/mix(old,new,w);
        d.wb.tint=mix(base.wb.tint,proposed.wb.tint,w);
    }
    d
}
fn y(p:&[u8;4])->f64 {
    (54.0*f64::from(p[0])+183.0*f64::from(p[1])+19.0*f64::from(p[2]))/256.0
}
#[derive(Default)]
struct Score {white:f64,black:f64,dark:f64,neutral:f64,contrast:f64}
fn score(original:&[[u8;4]],candidate:&[[u8;4]],width:usize)->Score {
    let mut s=Score::default();
    let(mut white,mut black,mut dark_n,mut neutral_n)=(0usize,0usize,0usize,0usize);
    let(mut dark_sum,mut drift,mut grad0,mut grad1)=(0.0f64,0.0f64,0.0f64,0.0f64);
    for(i,(a,b)) in original.iter().zip(candidate).enumerate() {
        for ch in 0..3{
            if a[ch]<254 && b[ch]>=254{white+=1;}
            if a[ch]>1 && b[ch]<=1{black+=1;}
        }
        let y0=y(a);let y1=y(b);
        if (5.0..68.0).contains(&y0){dark_n+=1;dark_sum+=(y1-y0).max(0.0);}
        if (42.0..215.0).contains(&y0){
            let low=f64::from(*a[..3].iter().min().unwrap_or(&0));
            let high=f64::from(*a[..3].iter().max().unwrap_or(&0));
            if high-low<15.0 {
                let lo=f64::from(*b[..3].iter().min().unwrap_or(&0));
                let hi=f64::from(*b[..3].iter().max().unwrap_or(&0));
                neutral_n+=1;drift+=(hi-lo-(high-low)).max(0.0);
            }
        }
        if i>width && i%width>0 {
            grad0+=(y0-y(&original[i-1])).abs()+(y0-y(&original[i-width])).abs();
            grad1+=(y1-y(&candidate[i-1])).abs()+(y1-y(&candidate[i-width])).abs();
        }
    }
    s.white=white as f64/(original.len()*3).max(1) as f64;
    s.black=black as f64/(original.len()*3).max(1) as f64;
    s.dark=if dark_n>150{dark_sum/dark_n as f64}else{0.0};
    s.neutral=drift/neutral_n.max(1) as f64;
    s.contrast=if grad0>1000.0{grad1/grad0}else{1.0};
    s
}
/// Each candidate is compared against the CURRENT picture, including WB,
/// dehaze, exposure, curves and colour. Re-render on every reduction.
pub fn validate(src:&Rgb32f,info:&SourceInfo,current:&DevelopSettings,
                wanted:&DevelopSettings)->(DevelopSettings,ClassicValidation){
    let mut a=ClassicValidation{passed:false,strength:0.0,iterations:0,
        white_clipping:0.0,black_clipping:0.0,dark_lift:0.0,reasons:vec![]};
    if current==wanted{a.passed=true;a.strength=1.0;return(current.clone(),a);}
    let proxy=lightcraft_raster::resample::fit(src,224,224,lightcraft_raster::resample::Filter::Box);
    if proxy.data.is_empty(){a.reasons.push("no_pixels");return(current.clone(),a);}
    let request=RenderRequest::fit(224,224);
    let before=crate::render(&proxy,info,current,&request).image;
    if before.data.is_empty(){a.reasons.push("baseline_failed");return(current.clone(),a);}
    let (mut t,mut w,mut h)=(1.0f64,1.0f64,1.0f64);
    for iteration in 0..12 {
        let trial=scaled(current,wanted,t,w,h);
        let after=crate::render(&proxy,info,&trial,&request).image;
        a.iterations=iteration+1;
        if after.data.len()!=before.data.len(){a.reasons.push("render_failed");break;}
        let sc=score(&before.data,&after.data,proxy.width);
        a.white_clipping=sc.white;a.black_clipping=sc.black;a.dark_lift=sc.dark;
        let too_white=sc.white>0.018;
        let too_black=sc.black>0.024;
        let too_lift=sc.dark>34.0; // Tested regardless of positive/negative EV.
        let hue_drift=current.wb!=wanted.wb && sc.neutral>13.0;
        let flat=sc.contrast<0.63;
        if !too_white && !too_black && !too_lift && !hue_drift && !flat {
            a.passed=true;a.strength=t;
            if iteration>0{a.reasons.push("reduced_and_revalidated");}
            return(trial,a);
        }
        if too_white{t*=0.78;h*=0.80;a.reasons.push("white_clip_reduction");}
        if too_black{t*=0.78;h*=0.80;a.reasons.push("black_clip_reduction");}
        if too_lift{t*=0.75;a.reasons.push("dark_lift_reduction");}
        if hue_drift{w*=0.70;a.reasons.push("white_balance_colour_guard");}
        if flat{t*=0.78;a.reasons.push("texture_guard");}
    }
    a.reasons.push("original_retained_no_safe_candidate");
    (current.clone(),a)
}
/// The *same* plan drives one-tap Auto, preview and Apply.
pub fn plan(src:&Rgb32f,info:&SourceInfo,current:&DevelopSettings)->ClassicPlan {
    let mut audit=crate::auto::classic_auto_tone_report(src,info,current);
    let c=audit.proposed;
    let mut wanted=current.clone();
    wanted.light.exposure=c.exposure;wanted.light.contrast=c.contrast;
    wanted.light.highlights=c.highlights;wanted.light.shadows=c.shadows;
    wanted.light.whites=c.whites;wanted.light.blacks=c.blacks;
    wanted.color.vibrance=c.vibrance;wanted.color.saturation=c.saturation;
    wanted.curve.shadows=c.curve_shadows;wanted.curve.darks=c.curve_darks;
    wanted.curve.lights=c.curve_lights;wanted.curve.highlights=c.curve_highlights;
    // A selected Daylight/Cloudy/Shade/Tungsten/etc or Custom mode is intentional.
    let preset_preserved=!matches!(current.wb.mode,WbMode::AsShot|WbMode::Auto);
    let wb=if preset_preserved{None}else{Some(crate::auto::auto_wb_report_balanced(src,info))};
    if let Some(r)=&wb {
        if r.decision=="corrected" && !r.camera_balance_retained {
            wanted.wb.mode=WbMode::Auto;wanted.wb.temp=r.temp;wanted.wb.tint=r.tint;
        }
    }
    let mut scene=crate::scene_auto::evaluate(src,info,&wanted);
    if current.effects.dehaze.abs()<0.001 && scene.dehaze>0.0 {
        wanted.effects.dehaze=scene.dehaze;
    }
    let (mut applied,mut guard)=validate(src,info,current,&wanted);
    // After progressive tone reduction a haze proposal might no longer be
    // supported. Remove it and validate *again* rather than blindly applying.
    // The scene evaluator intentionally treats any nonzero Dehaze as a
    // user's manual setting. Clear the AUTOMATIC proposed value for this
    // verification pass only, or it would always veto its own proposal.
    let mut check_scene_settings=applied.clone();
    if current.effects.dehaze.abs()<0.001 {check_scene_settings.effects.dehaze=0.0;}
    scene=crate::scene_auto::evaluate(src,info,&check_scene_settings);
    if current.effects.dehaze.abs()<0.001 && applied.effects.dehaze>0.0 &&
        scene.dehaze+0.01<applied.effects.dehaze {
        let mut no_haze=applied.clone();no_haze.effects.dehaze=0.0;
        let (checked,second)=validate(src,info,current,&no_haze);
        applied=checked;guard=second;
        guard.reasons.push("dehaze_revalidated_after_tone_change");
    }
    audit.final_tone=tone(&applied);
    audit.guard_reasons=guard.reasons.clone();
    audit.new_white_clip_fraction=guard.white_clipping;
    audit.new_black_clip_fraction=guard.black_clipping;
    ClassicPlan{settings:applied,tone:audit,white_balance:wb,scene,guard,preset_preserved}
}

#[cfg(test)]
mod tests{
    use super::*;
    fn img()->Rgb32f {
        Rgb32f::from_fn(96,96,|x,y| {
            let a=0.055+((x*3+y*7)%60) as f32*0.003;
            [a,a*0.94,a*0.89]
        })
    }
    #[test]
    fn does_not_modify_when_identical() {
        let d=DevelopSettings::default();
        let (r,s)=validate(&img(),&SourceInfo::default(),&d,&d);
        assert_eq!(r,d);assert!(s.passed);
    }
    #[test]
    fn keeps_daylight_and_tungsten_presets() {
        for mode in [WbMode::Daylight,WbMode::Tungsten,WbMode::Cloudy] {
            let mut d=DevelopSettings::default();d.wb.mode=mode;
            let p=plan(&img(),&SourceInfo::default(),&d);
            assert_eq!(p.settings.wb,d.wb);
            assert!(p.preset_preserved);
        }
    }
    #[test]
    fn final_combined_plan_is_deterministic() {
        let d=DevelopSettings::default();let image=img();
        let a=plan(&image,&SourceInfo::default(),&d);
        let b=plan(&image,&SourceInfo::default(),&d);
        assert_eq!(a.settings,b.settings);
        assert_eq!(a.tone.final_tone,b.tone.final_tone);
    }
    #[test]
    fn intense_tone_is_checked_not_blindly_accepted() {
        let d=DevelopSettings::default();let mut w=d.clone();
        w.light.exposure=4.0;w.color.saturation=100.0;
        let (_r,a)=validate(&img(),&SourceInfo::default(),&d,&w);
        assert!(a.iterations>0);
    }
}
