//! LumaWorks Smart Auto validation: one approved editable setting set for
//! Preview, direct Auto, and full-resolution render. No trained replacement
//! model and no changes to the colour-managed Rust rendering pipeline.
use lightcraft_develop::{DevelopSettings, WbMode};
use lightcraft_raster::Rgb32f;
use crate::{RenderRequest, SourceInfo};
use crate::auto::{AutoTone, AutoToneAuditReport, AutoWbReport};
use crate::scene_auto::SceneReport;
use serde::Serialize;

#[derive(Clone, Copy, Debug, Serialize)]
pub struct SafetyResult {
    pub accepted_strength: f64,
    pub new_white_clip_fraction: f64,
    pub new_black_clip_fraction: f64,
    pub mean_dark_lift: f64,
    pub contrast_ratio: f64,
    pub checked_combined_wb: bool,
    pub evaluated_candidates: usize,
    pub reason: &'static str,
}
#[derive(Clone, Debug, Serialize)]
pub struct SmartAutoPlan {
    pub settings: DevelopSettings,
    pub tone: AutoTone,
    pub tone_audit: AutoToneAuditReport,
    pub white_balance: Option<AutoWbReport>,
    pub manual_wb_preserved: bool,
    pub scene: SceneReport,
    pub safety: SafetyResult,
    pub no_change: bool,
}

pub fn wb_is_user_choice(mode:WbMode)->bool {
    // "As Shot" and "Auto" explicitly permit Smart Auto to consider WB.
    // A named photographic illuminant, or Custom, is intentional.
    !matches!(mode, WbMode::AsShot | WbMode::Auto)
}
fn apply_tone(d:&mut DevelopSettings,t:AutoTone){
    d.light.exposure=t.exposure;
    d.light.contrast=t.contrast;
    d.light.highlights=t.highlights;
    d.light.shadows=t.shadows;
    d.light.whites=t.whites;
    d.light.blacks=t.blacks;
    d.color.vibrance=t.vibrance;
    d.color.saturation=t.saturation;
    d.curve.shadows=t.curve_shadows;
    d.curve.darks=t.curve_darks;
    d.curve.lights=t.curve_lights;
    d.curve.highlights=t.curve_highlights;
}
pub fn tone_of(d:&DevelopSettings)->AutoTone{
    AutoTone{
        exposure:d.light.exposure,
        contrast:d.light.contrast,
        highlights:d.light.highlights,
        shadows:d.light.shadows,
        whites:d.light.whites,
        blacks:d.light.blacks,
        vibrance:d.color.vibrance,
        saturation:d.color.saturation,
        curve_shadows:d.curve.shadows,
        curve_darks:d.curve.darks,
        curve_lights:d.curve.lights,
        curve_highlights:d.curve.highlights,
    }
}
fn scaled(base:AutoTone,goal:AutoTone,k:f64)->AutoTone{
    let mix=|a:f64,b:f64|a+(b-a)*k;
    AutoTone{
        exposure:(mix(base.exposure,goal.exposure)*100.0).round()/100.0,
        contrast:mix(base.contrast,goal.contrast).round(),
        highlights:mix(base.highlights,goal.highlights).round(),
        shadows:mix(base.shadows,goal.shadows).round(),
        whites:mix(base.whites,goal.whites).round(),
        blacks:mix(base.blacks,goal.blacks).round(),
        vibrance:mix(base.vibrance,goal.vibrance).round(),
        saturation:mix(base.saturation,goal.saturation).round(),
        curve_shadows:mix(base.curve_shadows,goal.curve_shadows).round(),
        curve_darks:mix(base.curve_darks,goal.curve_darks).round(),
        curve_lights:mix(base.curve_lights,goal.curve_lights).round(),
        curve_highlights:mix(base.curve_highlights,goal.curve_highlights).round(),
    }
}
#[derive(Clone, Copy)]
struct RenderMetrics { white:f64,black:f64,dark:f64,gradient:f64 }
fn compare(base:&[[u8;4]],after:&[[u8;4]])->RenderMetrics{
    if base.is_empty() || base.len()!=after.len() {
        return RenderMetrics{white:1.0,black:1.0,dark:100.0,gradient:0.0};
    }
    let mut white=0usize;let mut black=0usize;
    let mut dark_n=0usize;let mut lift=0.0;
    let mut edge_base=0.0;let mut edge_after=0.0;
    let luminance=|p:&[u8;4]| -> f64 {
        (54.0*p[0] as f64+183.0*p[1] as f64+19.0*p[2] as f64)/256.0
    };
    for (i,(b,a)) in base.iter().zip(after).enumerate(){
        for c in 0..3 {
            if b[c]<254 && a[c]>=254 {white+=1;}
            if b[c]>1 && a[c]<=1 {black+=1;}
        }
        let y0=luminance(b);let y1=luminance(a);
        if y0>4.0 && y0<67.0 {
            dark_n+=1;lift+=(y1-y0).max(0.0);
        }
        if i>0 {
            edge_base+=(y0-luminance(&base[i-1])).abs();
            edge_after+=(y1-luminance(&after[i-1])).abs();
        }
    }
    RenderMetrics{
        white:white as f64/(base.len()*3) as f64,
        black:black as f64/(base.len()*3) as f64,
        dark:if dark_n>200 {lift/dark_n as f64} else {0.0},
        gradient:if edge_base>1000.0 {edge_after/edge_base} else {1.0},
    }
}
fn safe(m:RenderMetrics)->bool {
    m.white<=0.028 && m.black<=0.028 &&
        m.dark<=28.0 && m.gradient>=0.61
}
fn safety_report(k:f64,m:RenderMetrics,wb:bool,n:usize,reason:&'static str)->SafetyResult{
    SafetyResult{
        accepted_strength:k,new_white_clip_fraction:m.white,
        new_black_clip_fraction:m.black,mean_dark_lift:m.dark,
        contrast_ratio:m.gradient,checked_combined_wb:wb,
        evaluated_candidates:n,reason,
    }
}
pub fn plan(src:&Rgb32f,info:&SourceInfo,current:&DevelopSettings)->SmartAutoPlan {
    let mut audit=crate::auto::smart_auto_tone_report_balanced(src,info,current);
    // The old binary guard's "0" results are deliberately not applied.
    // Use its pre-guard photographic candidate, then find the strongest
    // *render-verified* safe result without disabling other controls.
    let goal=audit.proposed;
    let base_tone=tone_of(current);
    let manual=wb_is_user_choice(current.wb.mode);
    let wb=if manual {None}
        else {Some(crate::auto::auto_wb_report_balanced(src,info))};
    let wb_to_try=wb.filter(|r|r.decision=="corrected" &&
        (r.temp-current.wb.temp).abs()>2.0 || (r.tint-current.wb.tint).abs()>1.0);
    let proxy=lightcraft_raster::resample::fit(
        src,192,192,lightcraft_raster::resample::Filter::Box);
    let req=RenderRequest::fit(192,192);
    let baseline=crate::render(&proxy,info,current,&req).image;
    let mut chosen=current.clone();
    let mut accepted=safety_report(0.0,RenderMetrics{white:0.0,black:0.0,dark:0.0,gradient:1.0},false,0,"unchanged");
    let mut evaluated=0usize;
    let strengths=[1.0,0.85,0.70,0.55,0.40,0.25,0.12];
    // Prefer a trustworthy new WB, but reject it if its combined tone/colour
    // clips; then retry with the preserved WB. Presets never enter this path.
    'search: for use_wb in [true,false] {
        if use_wb && wb_to_try.is_none(){continue;}
        for strength in strengths {
            let mut candidate=current.clone();
            apply_tone(&mut candidate,scaled(base_tone,goal,strength));
            if use_wb {
                if let Some(r)=wb_to_try {
                    candidate.wb.mode=WbMode::Auto;
                    candidate.wb.temp=r.temp;
                    candidate.wb.tint=r.tint;
                }
            }
            if candidate==*current {continue;}
            evaluated+=1;
            let rendered=crate::render(&proxy,info,&candidate,&req).image;
            let m=compare(&baseline.data,&rendered.data);
            if safe(m) {
                chosen=candidate;
                accepted=safety_report(strength,m,use_wb,evaluated,
                    if strength>=0.99 {"full_render_verified"}
                    else {"progressively_reduced_render_verified"});
                break 'search;
            }
        }
    }
    if chosen==*current && wb_to_try.is_some(){
        // Permit an independently safe WB correction if all mixed edits fail.
        let mut candidate=current.clone();
        if let Some(r)=wb_to_try{
            candidate.wb.mode=WbMode::Auto;
            candidate.wb.temp=r.temp;
            candidate.wb.tint=r.tint;
        }
        evaluated+=1;
        let rendered=crate::render(&proxy,info,&candidate,&req).image;
        let m=compare(&baseline.data,&rendered.data);
        if safe(m){
            chosen=candidate;
            accepted=safety_report(0.0,m,true,evaluated,"wb_only_render_verified");
        }
    }
    // Scene-aware Dehaze must also be judged *after* proposed tone and WB.
    let scene=crate::scene_auto::evaluate(src,info,&chosen);
    if current.effects.dehaze.abs()<0.001 && scene.dehaze>0.0 {
        let mut with_haze=chosen.clone();
        with_haze.effects.dehaze=scene.dehaze;
        let rendered=crate::render(&proxy,info,&with_haze,&req).image;
        evaluated+=1;
        let m=compare(&baseline.data,&rendered.data);
        if safe(m) {
            chosen=with_haze;
            accepted=safety_report(accepted.accepted_strength,m,
                accepted.checked_combined_wb,evaluated,"scene_haze_render_verified");
        }
    }
    accepted.evaluated_candidates=evaluated;
    audit.final_tone=tone_of(&chosen);
    audit.guard_reasons=if chosen==*current {vec!["no_safe_edit"]}
        else if accepted.accepted_strength<0.99 {vec!["strength_reduced_by_render_validation"]}
        else {vec![]};
    audit.new_white_clip_fraction=accepted.new_white_clip_fraction;
    audit.new_black_clip_fraction=accepted.new_black_clip_fraction;
    SmartAutoPlan{
        no_change:chosen==*current,
        tone:tone_of(&chosen),
        settings:chosen,
        tone_audit:audit,
        white_balance:wb,
        manual_wb_preserved:manual,
        scene,
        safety:accepted,
    }
}
#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn manual_wb_presets_remain_protected(){
        for mode in [WbMode::Daylight,WbMode::Cloudy,WbMode::Shade,
            WbMode::Tungsten,WbMode::Fluorescent,WbMode::Flash,WbMode::Custom]{
            assert!(wb_is_user_choice(mode));
        }
        assert!(!wb_is_user_choice(WbMode::AsShot));
        assert!(!wb_is_user_choice(WbMode::Auto));
    }
    #[test]
    fn clipping_metrics_use_same_pixel_not_net_counts(){
        let b=vec![[253,120,120,255],[255,120,120,255]];
        let a=vec![[255,120,120,255],[253,120,120,255]];
        let m=compare(&b,&a);
        assert!(m.white>0.0);
    }
    #[test]
    fn gradual_reduction_preserves_nonzero_exposure(){
        let goal=AutoTone{exposure:0.9,contrast:13.0,shadows:30.0,..Default::default()};
        let s=scaled(AutoTone::default(),goal,0.4);
        assert!(s.exposure>0.0 && s.exposure<0.9);
        assert!(s.contrast>0.0 && s.shadows>0.0);
    }
    #[test]
    fn combined_wb_and_tone_does_not_override_daylight(){
        let src=Rgb32f::from_fn(160,120,|x,y|{
            let p=0.035+((x*7+y*11)%75) as f32*0.004;
            [p*0.95,p,p*1.05]
        });
        let mut base=DevelopSettings::default();
        base.wb.mode=WbMode::Daylight;
        base.wb.temp=5500.0;
        let p=plan(&src,&SourceInfo::default(),&base);
        assert_eq!(p.settings.wb,base.wb);
    }
    #[test]
    fn already_approved_settings_are_stable(){
        let src=Rgb32f::from_fn(96,72,|x,y|{
            let p=0.035+((x*7+y*11)%55) as f32*0.004;
            [p,p,p*1.03]
        });
        let base=DevelopSettings::default();
        let first=plan(&src,&SourceInfo::default(),&base);
        let again=plan(&src,&SourceInfo::default(),&first.settings);
        assert_eq!(again.settings,first.settings);
    }
}
