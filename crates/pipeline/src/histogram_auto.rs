//! Histogram-led Auto evidence for the isolated v0.6.26 trial.
//!
//! Analyse the *rendered* RGB channels and luminance distribution in nine
//! spatial regions. Unlike histogram equalisation, this module never tries to
//! make every photograph grey or evenly distributed: sunlight, saturated
//! flowers, intentional black backgrounds and skin may be correct as shot.
//!
//! This is a deterministic, offline heuristic, NOT a trained AI model.
//! The normal render quality and clipping guard still has final authority.

use crate::auto::AutoTone;
use lightcraft_raster::Rgba8;

#[derive(Clone, Copy, Debug)]
struct Bins {
    luma: [u32;256],
    rgb: [[u32;256];3],
    count: u32,
}
impl Default for Bins {
    fn default()->Self {
        Self { luma:[0;256],rgb:[[0;256];3],count:0 }
    }
}
impl Bins {
    fn add(&mut self,p:&[u8;4]) {
        let y=((0.2126*p[0] as f64+0.7152*p[1] as f64+
            0.0722*p[2] as f64).round() as usize).min(255);
        self.luma[y]+=1;
        for c in 0..3 {self.rgb[c][p[c] as usize]+=1;}
        self.count+=1;
    }
    fn percentile(&self,q:f64)->f64 {
        if self.count==0 {return 0.0;}
        let wanted=((self.count as f64*q.clamp(0.0,1.0)).ceil() as u32).max(1);
        let mut n=0u32;
        for (i,&v) in self.luma.iter().enumerate() {
            n+=v;
            if n>=wanted {return i as f64/255.0;}
        }
        1.0
    }
    fn below(&self,t:u8)->f64 {
        if self.count==0 {return 0.0;}
        self.luma[..=t as usize].iter().sum::<u32>() as f64/self.count as f64
    }
    fn over_channel(&self,t:u8)->f64 {
        if self.count==0{return 0.0;}
        let hits=(0..3).map(|c|self.rgb[c][t as usize..].iter().sum::<u32>())
            .sum::<u32>();
        hits as f64/(self.count as f64*3.0)
    }
}
#[derive(Clone, Copy, Debug)]
pub struct Evidence {
    global:Bins,
    zones:[Bins;9],
    pub dark_fraction:f64,
    pub p05:f64,
    pub p50:f64,
    pub p95:f64,
    pub p995:f64,
    pub rgb_near_clip:f64,
    pub shadow_detail_fraction:f64,
    pub center_median:f64,
    pub surrounding_median:f64,
    pub sky_p95:f64,
    pub lower_median:f64,
    pub saturated_bright_fraction:f64,
    pub neutral_bright_fraction:f64,
}
pub fn analyse(img:&Rgba8)->Option<Evidence> {
    if img.width<8 || img.height<8 || img.is_empty() {return None;}
    let mut global=Bins::default();
    let mut zones=[Bins::default();9];
    let (mut shadow_detail,mut saturated_bright,mut neutral_bright)=
        (0u32,0u32,0u32);
    for (i,p) in img.data.iter().enumerate() {
        let x=i%img.width;
        let y=i/img.width;
        let zone=(y*3/img.height).min(2)*3+(x*3/img.width).min(2);
        global.add(p);
        zones[zone].add(p);
        let l=(0.2126*p[0] as f64+0.7152*p[1] as f64+
            0.0722*p[2] as f64)/255.0;
        if (0.075..0.35).contains(&l) {shadow_detail+=1;}
        let hi=*p[..3].iter().max().unwrap_or(&0) as f64;
        let lo=*p[..3].iter().min().unwrap_or(&0) as f64;
        let chroma=(hi-lo)/hi.max(1.0);
        if hi>190.0 && chroma>0.39 {saturated_bright+=1;}
        if l>0.78 && chroma<0.16 {neutral_bright+=1;}
    }
    let mut sky=Bins::default();
    let mut lower=Bins::default();
    let mut outer=Bins::default();
    // Merge histograms exactly, instead of computing mean of percentiles.
    for i in 0..9 {
        if i<3 {merge(&mut sky,&zones[i]);}
        if i>=6 {merge(&mut lower,&zones[i]);}
        if i!=4 {merge(&mut outer,&zones[i]);}
    }
    let total=global.count as f64;
    Some(Evidence {
        dark_fraction:global.below(32),
        p05:global.percentile(0.05),
        p50:global.percentile(0.50),
        p95:global.percentile(0.95),
        p995:global.percentile(0.995),
        rgb_near_clip:global.over_channel(250),
        shadow_detail_fraction:shadow_detail as f64/total,
        center_median:zones[4].percentile(0.50),
        surrounding_median:outer.percentile(0.50),
        sky_p95:sky.percentile(0.95),
        lower_median:lower.percentile(0.50),
        saturated_bright_fraction:saturated_bright as f64/total,
        neutral_bright_fraction:neutral_bright as f64/total,
        global,zones,
    })
}
fn merge(a:&mut Bins,b:&Bins) {
    a.count+=b.count;
    for i in 0..256 {
        a.luma[i]+=b.luma[i];
        for c in 0..3 {a.rgb[c][i]+=b.rgb[c][i];}
    }
}
/// Proposal driven by *where* the histogram needs attention. It competes with
/// the legacy and v0.6.25 proposals; it never bypasses the renderer guard.
pub fn propose(e:&Evidence,seed:AutoTone,portrait:bool,low_key:bool,sunny:bool)->AutoTone {
    let mut t=seed;
    let already_good=(0.37..=0.68).contains(&e.p50) &&
        e.rgb_near_clip<0.025 && e.shadow_detail_fraction>0.12 &&
        (e.p95-e.p05)>0.44;
    if already_good && !portrait {
        // A sound starting image gets an intentionally gentle candidate.
        t.exposure=(t.exposure*0.55*100.0).round()/100.0;
        t.shadows*=0.68;
        t.highlights*=0.72;
    }else if low_key {
        // Distinguish vivid flower-on-black from a nearly black night scene.
        if e.saturated_bright_fraction>0.07 &&
            e.neutral_bright_fraction<0.035 &&
            e.shadow_detail_fraction>0.09 {
            t.exposure=(t.exposure+0.15).clamp(-0.08,0.66);
            t.highlights=(t.highlights-12.0).clamp(-58.0,0.0);
            t.shadows=(t.shadows+10.0).clamp(0.0,44.0);
            t.blacks=(t.blacks-4.0).clamp(-14.0,3.0);
        }else {
            t.exposure=t.exposure.min(0.09);
            t.shadows=t.shadows.min(12.0);
        }
    }else if portrait {
        // Avoid pushing already-bright faces. Open mid-dark hair/clothing
        // without shifting camera WB or artificially changing skin colour.
        if e.center_median<0.40 && e.p95<0.88 {
            t.exposure=(t.exposure+0.09).clamp(-0.24,0.32);
        }else {
            t.exposure=t.exposure.clamp(-0.25,0.12);
        }
        if e.shadow_detail_fraction>0.13 {
            t.shadows=(t.shadows+8.0).clamp(0.0,38.0);
        }
        if e.rgb_near_clip>0.018 {
            t.highlights=(t.highlights-9.0).clamp(-52.0,0.0);
        }
        t.blacks=(t.blacks-2.0).clamp(-10.0,5.0);
    }else if sunny {
        // A bright blue sky is not proof that exposure is too high.
        t.exposure=t.exposure.clamp(-0.045,0.14);
        if e.lower_median<e.sky_p95*0.58 && e.lower_median<0.50 {
            t.shadows=(t.shadows+8.0).clamp(0.0,35.0);
        }
        if e.neutral_bright_fraction<0.06 {
            t.highlights=t.highlights.max(-18.0);
        }
    }else {
        let under=e.p50<0.32 && e.p95<0.89;
        let over=e.p50>0.75 && e.p05>0.23;
        if under && e.shadow_detail_fraction>0.12 {
            t.exposure=(t.exposure+0.13).clamp(-0.2,0.48);
            t.shadows=(t.shadows+7.0).clamp(0.0,36.0);
        }else if over {
            t.exposure=(t.exposure-0.09).clamp(-0.45,0.20);
        }
        if e.neutral_bright_fraction>0.045 && e.p995>0.96 {
            t.highlights=(t.highlights-10.0).clamp(-48.0,4.0);
        }
    }
    // Slider granularity and coherent limits, not histogram equalisation.
    t.exposure=(t.exposure*100.0).round()/100.0;
    t.shadows=t.shadows.round();
    t.highlights=t.highlights.round();
    t.blacks=t.blacks.round();
    t
}
/// Reward useful histogram changes only when spatial regions support them.
/// Strongly penalise new RGB clipping and sky dimming; retain black backgrounds.
pub fn score(reference:&Evidence,now:&Evidence,
             portrait:bool,low_key:bool,sunny:bool)->f64 {
    let mut score=0.0;
    let new_rgb_clip=(now.rgb_near_clip-reference.rgb_near_clip).max(0.0);
    score-=new_rgb_clip*110.0;
    let bright_loss=(reference.p95-now.p95).max(0.0);
    if sunny {score-=bright_loss*11.0;}
    if low_key {
        // Very dark regions should remain recognisably dark.
        score-=(reference.dark_fraction-now.dark_fraction).max(0.0)*6.0;
        let useful=(now.shadow_detail_fraction-reference.shadow_detail_fraction)
            .clamp(-0.05,0.12);
        score+=useful*9.0;
    }else if portrait {
        score-=(now.center_median-reference.center_median).abs()*11.0;
        if reference.shadow_detail_fraction>0.11 {
            score+=(now.shadow_detail_fraction-reference.shadow_detail_fraction)
                .clamp(-0.05,0.12)*6.0;
        }
    }else {
        if reference.p50<0.32 && reference.shadow_detail_fraction>0.10 {
            score+=(now.p50-reference.p50).clamp(-0.12,0.10)*5.0;
        }
        if sunny && reference.lower_median<0.46 {
            score+=(now.lower_median-reference.lower_median).clamp(-0.08,0.08)*4.0;
        }
    }
    // Very large shifts have to earn their place through meaningful detail.
    score-=(now.p50-reference.p50).abs()*0.70;
    // RGB-channel percentile shape should not collapse to a compressed wash.
    for c in 0..3 {
        let original_spread=channel_span(&reference.global,c);
        let output_spread=channel_span(&now.global,c);
        if original_spread>0.25 {
            score-=(original_spread-output_spread).max(0.0)*1.8;
        }
    }
    // Underrepresented shadow/highlight regions should not be treated as
    // equally important regardless of the image's original composition.
    for z in 0..9 {
        let baseline=&reference.zones[z];
        let changed=&now.zones[z];
        let spread0=baseline.percentile(0.85)-baseline.percentile(0.15);
        let spread1=changed.percentile(0.85)-changed.percentile(0.15);
        if spread0>0.17 {
            score-=(spread0-spread1).max(0.0)*0.4;
        }
    }
    score
}
fn channel_span(b:&Bins,c:usize)->f64 {
    if b.count==0 {return 0.0;}
    let q=|fraction:f64| {
        let goal=((b.count as f64*fraction).ceil() as u32).max(1);
        let mut seen=0u32;
        for (i,&count) in b.rgb[c].iter().enumerate() {
            seen+=count;
            if seen>=goal {return i as f64/255.0;}
        }
        1.0
    };
    q(0.90)-q(0.10)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn black_background_is_not_mistaken_for_uniform_underexposure() {
        let img=Rgba8::from_fn(96,96,|x,y|{
            if x>27 && x<68 && y>20 && y<84 {[248,34,141,255]}
            else if x%8==0 {[46,52,32,255]}
            else {[6,8,8,255]}
        });
        let ev=analyse(&img).unwrap();
        assert!(ev.dark_fraction>0.45);
        let mut seed=AutoTone::default();seed.exposure=0.42;seed.highlights=-28.0;
        let proposed=propose(&ev,seed,false,true,false);
        assert!(proposed.exposure<=0.66);
        assert!(proposed.highlights<=seed.highlights);
        assert!(proposed.blacks<=0.0);
    }
    #[test]
    fn channel_clipping_receives_a_penalty_even_with_same_luma_distribution() {
        let img=Rgba8::filled(64,64,[170,95,95,255]);
        let altered=Rgba8::filled(64,64,[255,75,70,255]);
        let a=analyse(&img).unwrap();let b=analyse(&altered).unwrap();
        assert!(score(&a,&b,false,false,false)<score(&a,&a,false,false,false));
    }
    #[test]
    fn bright_daylight_sky_is_not_automatically_dulled() {
        let original=Rgba8::from_fn(96,96,|_,y|{
            if y<42 {[78,188,250,255]}else{[90,132,63,255]}
        });
        let darker=Rgba8::from_fn(96,96,|_,y|{
            if y<42 {[55,132,170,255]}else{[69,102,46,255]}
        });
        let base=analyse(&original).unwrap();
        let dim=analyse(&darker).unwrap();
        assert!(score(&base,&dim,false,false,true)<score(&base,&base,false,false,true));
    }
    #[test]
    fn portrait_middle_region_is_preferred_not_overbrightened() {
        let original=Rgba8::from_fn(96,96,|x,y|{
            if x>30 && x<68 && y>18 && y<80 {[192,155,132,255]}
            else {[26,37,42,255]}
        });
        let lighter=Rgba8::from_fn(96,96,|x,y|{
            if x>30 && x<68 && y>18 && y<80 {[243,205,189,255]}
            else {[32,43,49,255]}
        });
        let base=analyse(&original).unwrap();
        let changed=analyse(&lighter).unwrap();
        assert!(score(&base,&changed,true,false,false)<
                score(&base,&base,true,false,false));
    }
}
