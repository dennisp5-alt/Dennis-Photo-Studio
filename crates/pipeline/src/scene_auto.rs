//! Conservative photometric scene awareness for optional atmospheric correction.
//! No object labels or trained model: a proposed haze edit is accepted only
//! when regional evidence agrees and the actual rendered output is safe.
use lightcraft_color::luminance_2020;
use lightcraft_develop::DevelopSettings;
use lightcraft_raster::Rgb32f;
use serde::Serialize;
use crate::{RenderRequest, SourceInfo};

#[derive(Clone, Copy, Debug, Default, Serialize)]
pub struct SceneReport {
    pub scene: &'static str,
    pub confidence: f64,
    pub dehaze: f64,
    pub evidence: &'static str,
}
#[derive(Default, Clone, Copy)]
struct Region {
    count: usize,
    airy: usize,
    warm_skin: usize,
    warm_emitter: usize,
    dark: usize,
    clipped: usize,
    texture: usize,
    useful: usize,
}
impl Region {
    fn sky_like(self) -> f64 {self.airy as f64/self.count.max(1) as f64}
    fn textured(self) -> f64 {self.texture as f64/self.useful.max(1) as f64}
}
/// A narrow atmospheric candidate, not a claim to distinguish all forms of
/// weather from artistic mist. The sky alone never authorises Dehaze.
fn candidate(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings) -> SceneReport {
    let mut im=lightcraft_raster::resample::fit(src,320,320,
        lightcraft_raster::resample::Filter::Box);
    if im.width<64 || im.height<64 || info.raw {
        return SceneReport {scene:"unclassified",evidence:"insufficient_evidence",..Default::default()};
    }
    let base=DevelopSettings{wb:s.wb,..DevelopSettings::default()};
    crate::local::scene_linear_pre(&mut im,info,&base);
    let w=im.width;let h=im.height;
    let mut regions=[Region::default();3];
    let mut all=Region::default();
    let light:Vec<f32>=im.data.iter().map(|p|luminance_2020(*p)).collect();
    for y in 2..h-2 {
        for x in 2..w-2 {
            let i=y*w+x;let c=im.data[i];
            let v=light[i];
            if !v.is_finite() {continue;}
            let r=&mut regions[(y*3/h).min(2)];
            let hi=c[0].max(c[1]).max(c[2]).max(1e-6);
            let lo=c[0].min(c[1]).min(c[2]);
            let chroma=(hi-lo)/hi;
            // An airy, lit, somewhat neutral region; white petals alone
            // cannot trigger atmospheric processing without spatial support.
            let airy=(0.17..0.78).contains(&v) && chroma<0.48
                && lo>0.085;
            let warm=(0.045..0.75).contains(&v)
                && c[0]>c[1]*1.10 && c[1]>c[2]*1.035
                && c[0]<c[2]*2.2 && (0.11..0.55).contains(&chroma);
            let emitter=v>0.42 && c[0]>c[1]*1.40 && c[1]>c[2]*1.48;
            let gradient=((light[i-1]-light[i+1]).abs()
                +(light[i-w]-light[i+w]).abs())
                /(0.045+2.0*v);
            let useful=(0.045..0.70).contains(&v);
            for z in [r,&mut all] {
                z.count+=1;
                z.airy+=usize::from(airy);
                z.warm_skin+=usize::from(warm);
                z.warm_emitter+=usize::from(emitter);
                z.dark+=usize::from(v<0.028);
                z.clipped+=usize::from(v>0.93);
                z.useful+=usize::from(useful);
                z.texture+=usize::from(useful && gradient>0.042);
            }
        }
    }
    let [top,mid,bottom]=regions;
    let n=all.count.max(1) as f64;
    let warm_fraction=all.warm_skin as f64/n;
    let emitter_fraction=all.warm_emitter as f64/n;
    let dark_fraction=all.dark as f64/n;
    let clipped_fraction=all.clipped as f64/n;
    // In particular: don't call any bright-background portrait, flower,
    // moon/fire or flat night a hazy landscape.
    if top.sky_like()<0.34 || mid.sky_like()<0.16
        || bottom.textured()<0.08 || bottom.useful<350
        || warm_fraction>0.12 || emitter_fraction>0.01
        || dark_fraction>0.32 || clipped_fraction>0.045 {
        return SceneReport {scene:"protected_or_unconfirmed",evidence:"scene_evidence_insufficient",..Default::default()};
    }
    let top_score=((top.sky_like()-0.34)/0.32).clamp(0.0,1.0);
    let mid_score=((mid.sky_like()-0.16)/0.36).clamp(0.0,1.0);
    let ground_score=((bottom.textured()-0.08)/0.22).clamp(0.0,1.0);
    let confidence=0.40*top_score+0.36*mid_score+0.24*ground_score;
    if confidence<0.68 {
        return SceneReport {scene:"unconfirmed",confidence,evidence:"low_atmospheric_confidence",dehaze:0.0};
    }
    SceneReport {scene:"possible_distant_haze",confidence,
        dehaze: (5.0+confidence*8.0).min(12.0).round(),
        evidence:"lit_upper_and_middle_regions_with_textured_foreground"}
}
/// Non-destructive regression veto after rendering the proposed atmospheric
/// adjustment in the same Rec.2020 -> sRGB pipeline as normal previews.
/// Preserve sky colour, existing highlights and blacks. Rejected corrections
/// are zero; we never force a dehaze just because the scene is low contrast.
pub fn evaluate(src:&Rgb32f,info:&SourceInfo,s:&DevelopSettings)->SceneReport {
    if s.effects.dehaze.abs()>0.001 {
        return SceneReport {scene:"manual_dehaze_preserved",evidence:"manual_setting",..Default::default()};
    }
    let mut report=candidate(src,info,s);
    if report.dehaze<=0.0 {return report;}
    let proxy=lightcraft_raster::resample::fit(src,144,144,
        lightcraft_raster::resample::Filter::Box);
    let req=RenderRequest::fit(144,144);
    let baseline=crate::render(&proxy,info,s,&req).image;
    if baseline.data.is_empty() {report.dehaze=0.0;return report;}
    for strength in [report.dehaze,6.0,3.0] {
        let mut after_settings=s.clone();
        after_settings.effects.dehaze=strength;
        let after=crate::render(&proxy,info,&after_settings,&req).image;
        if after.data.len()!=baseline.data.len() {continue;}
        let mut new_white=0usize;let mut new_black=0usize;
        let mut changed_sky=0.0;let mut sky_n=0usize;
        let mut extreme_chroma=0usize;
        for (i,(b,a)) in baseline.data.iter().zip(&after.data).enumerate() {
            for k in 0..3 {
                new_white+=usize::from(b[k]<253 && a[k]>=254);
                new_black+=usize::from(b[k]>2 && a[k]<=1);
            }
            if i/baseline.width < baseline.height/3 {
                changed_sky+=(b[0] as i32-a[0] as i32).unsigned_abs() as f64
                    +(b[1] as i32-a[1] as i32).unsigned_abs() as f64
                    +(b[2] as i32-a[2] as i32).unsigned_abs() as f64;
                sky_n+=3;
            }
            let spread=|v:&[u8;4]| {
                let mx=v[0].max(v[1]).max(v[2]).max(1);
                (mx-v[0].min(v[1]).min(v[2])) as f64 / mx as f64
            };
            extreme_chroma+=usize::from(spread(a)>0.93 && spread(b)<=0.85);
        }
        let total=(baseline.data.len()*3).max(1) as f64;
        let sky_delta=changed_sky/sky_n.max(1) as f64;
        if new_white as f64/total<=0.004
            && new_black as f64/total<=0.004
            && sky_delta<=11.0
            && extreme_chroma as f64/baseline.data.len() as f64<=0.008 {
            report.dehaze=strength;
            return report;
        }
    }
    report.dehaze=0.0;
    report.scene="rejected_by_render_guard";
    report.evidence="new_clipping_or_sky_colour_shift";
    report
}

#[cfg(test)]
mod tests {
    use super::*;
    fn src(f: impl Fn(usize,usize)->[f32;3])->Rgb32f{
        Rgb32f::from_fn(320,240,f)
    }
    fn check(i:&Rgb32f)->SceneReport{
        evaluate(i,&SourceInfo::default(),&DevelopSettings::default())
    }
    #[test] fn protected_black_night_does_not_dehaze(){
        let i=src(|x,y|if (150..173).contains(&x)&&(90..133).contains(&y)
            {[0.9,0.46,0.07]}else{[0.004,0.006,0.004]});
        assert_eq!(check(&i).dehaze,0.0);
    }
    #[test] fn vivid_flower_is_not_haze(){
        let i=src(|x,y|if x>80&&x<260&&y>30&&y<220
            {[0.72,0.024,0.23]}else{[0.04,0.24,0.05]});
        assert_eq!(check(&i).dehaze,0.0);
    }
    #[test] fn manual_dehaze_is_preserved(){
        let i=src(|_,_|[0.3,0.32,0.35]);
        let mut s=DevelopSettings::default();s.effects.dehaze=18.0;
        let r=evaluate(&i,&SourceInfo::default(),&s);
        assert_eq!(r.dehaze,0.0);
        assert_eq!(r.scene,"manual_dehaze_preserved");
    }
    #[test] fn neutral_flat_studio_image_is_not_haze(){
        let i=src(|_,_|[0.33,0.33,0.33]);
        assert_eq!(check(&i).dehaze,0.0);
    }
    #[test] fn manual_white_balance_is_not_changed(){
        let i=src(|_,_|[0.27,0.33,0.35]);
        let mut s=DevelopSettings::default();s.effects.dehaze=5.0;
        let old=s.wb.clone();
        let _=evaluate(&i,&SourceInfo::default(),&s);
        assert_eq!(s.wb,old);
    }
}
