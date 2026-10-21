//! Scene evidence for Auto. Inspired by the multi-zone approach in the
//! user's AutoFix True Colour Lab v0.7.2. This is NOT semantic segmentation:
//! pixels cannot establish an object, a face, a sky, or intent on their own.
//! It provides independent, spatially grounded *photometric evidence* to
//! complement the full-frame histogram before choosing editor parameters.

use lightcraft_color::luminance_2020;
use lightcraft_raster::Rgb32f;
use serde::Serialize;

#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize)]
pub struct ZoneEvidence {
    pub median_luma:f64,
    pub p10_luma:f64,
    pub p90_luma:f64,
    pub recoverable_fraction:f64,
    pub dark_detail_fraction:f64,
    pub bright_fraction:f64,
    pub highlight_risk_fraction:f64,
    pub samples:usize,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize)]
pub struct ZonalSceneEvidence {
    /// Row-major 3x3 zones (top-left to bottom-right).
    pub zones:[ZoneEvidence;9],
    pub luminous_zones:usize,
    pub shadow_detail_zones:usize,
    pub tonal_separation:f64,
    pub bright_surround_dark_detail:bool,
    pub dark_texture_with_bright_reference:bool,
    pub proposed_shadow_recovery:f64,
    pub proposed_darks_curve:f64,
}

fn q(v:&mut [f32],percentile:f64)->f64 {
    if v.is_empty(){return 0.0;}
    let at=((v.len()-1) as f64*percentile.clamp(0.0,1.0)).floor() as usize;
    let (_,value,_) = v.select_nth_unstable_by(at,|a,b|a.total_cmp(b));
    *value as f64
}

/// Input must be the **linear Rec.2020** analysis image, pre-developed for
/// the active WB and with no Auto exposure/tone applied. The 3x3 zones are
/// constant-memory except for luminance samples and do not export pixels.
pub fn analyse_linear(img:&Rgb32f)->ZonalSceneEvidence {
    if img.width<27 || img.height<27 || img.data.is_empty() {
        return ZonalSceneEvidence::default();
    }
    let width=img.width;
    let height=img.height;
    let mut values:[Vec<f32>;9]=std::array::from_fn(|_|Vec::new());
    let mut mid=[0usize;9];
    let mut dark_detail=[0usize;9];
    let mut bright=[0usize;9];
    let mut risky=[0usize;9];
    let lum:Vec<f32>=img.data.iter()
        .map(|c|luminance_2020(*c).max(0.0)).collect();
    for y in 0..height {
        for x in 0..width {
            let index=y*width+x;
            let zone=(y*3/height).min(2)*3+(x*3/width).min(2);
            let l=lum[index];
            values[zone].push(l);
            if (0.035..0.28).contains(&l) {mid[zone]+=1;}
            if l>0.48 {bright[zone]+=1;}
            if l>0.96 {risky[zone]+=1;}
            // Unlike a low luminance count, *textured* recoverable dark
            // values can indicate detail worth revealing. Flat black
            // backgrounds and uniform night sky have little such evidence.
            if x>0 && y>0 && (0.035..0.24).contains(&l) {
                let a=lum[index-1];
                let b=lum[index-width];
                let edge=((l-a).abs()+(l-b).abs())
                    /(0.035+l+a+b);
                if edge>0.058 {dark_detail[zone]+=1;}
            }
        }
    }
    let mut zones=[ZoneEvidence::default();9];
    for i in 0..9 {
        let n=values[i].len();
        if n==0{continue;}
        let p10=q(&mut values[i],0.10);
        let p90=q(&mut values[i],0.90);
        let median=q(&mut values[i],0.50);
        zones[i]=ZoneEvidence {
            median_luma:median,
            p10_luma:p10,
            p90_luma:p90,
            recoverable_fraction:mid[i] as f64/n as f64,
            dark_detail_fraction:dark_detail[i] as f64/n as f64,
            bright_fraction:bright[i] as f64/n as f64,
            highlight_risk_fraction:risky[i] as f64/n as f64,
            samples:n,
        };
    }
    let luminous_zones=zones.iter()
        .filter(|z|z.bright_fraction>0.38 && z.median_luma>0.34).count();
    let shadow_detail_zones=zones.iter()
        .filter(|z|z.dark_detail_fraction>0.007
            && z.recoverable_fraction>0.095).count();
    let high=zones.iter().map(|z|z.median_luma).fold(0.0f64,f64::max);
    let low=zones.iter().map(|z|z.median_luma).fold(f64::MAX,f64::min);
    let tonal_separation=(high-low).max(0.0);
    let detail_peak=zones.iter()
        .map(|z|z.dark_detail_fraction).fold(0.0f64,f64::max);
    let bright_surround_dark_detail=luminous_zones>=3
        && shadow_detail_zones>=1 && tonal_separation>0.20
        && detail_peak>0.014;
    // Dim product or portrait with a *small real bright reference*: can
    // have useful dark texture even if global histogram says 'low key'.
    // This signal alone does NOT authorize brightening the whole frame.
    let dark_texture_with_bright_reference=luminous_zones<=2
        && shadow_detail_zones>=2
        && zones.iter().any(|z|z.p90_luma>0.56)
        && tonal_separation>0.10;
    let strength=if bright_surround_dark_detail {
        (9.0+65.0*(detail_peak-0.014).max(0.0)
          +9.0*tonal_separation.min(0.70)).clamp(9.0,26.0)
    } else if dark_texture_with_bright_reference {
        (5.0+38.0*(detail_peak-0.014).max(0.0)).clamp(5.0,12.0)
    }else{0.0};
    let darks=if bright_surround_dark_detail {
        (3.0+strength*0.35).clamp(3.0,12.0)
    }else if dark_texture_with_bright_reference {
        (strength*0.5).clamp(2.0,6.0)
    }else{0.0};
    ZonalSceneEvidence {
        zones,luminous_zones,shadow_detail_zones,tonal_separation,
        bright_surround_dark_detail,dark_texture_with_bright_reference,
        proposed_shadow_recovery:strength.round(),
        proposed_darks_curve:darks.round(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bright_surrounding_water_and_dark_textured_subject_separate() {
        let img=Rgb32f::from_fn(270,270,|x,y|{
            let water=if (x/17+y/19)%3==0 {0.74}else{0.64};
            if (85..185).contains(&x) && (102..173).contains(&y) {
                let feather=((x*7+y*13)%15) as f32/130.0;
                [0.08+feather,0.07+feather,0.055+feather]
            } else {[water,water*1.02,water*1.04]}
        });
        let z=analyse_linear(&img);
        assert!(z.bright_surround_dark_detail,"dark subject missed: {z:?}");
        assert!(z.proposed_shadow_recovery>=9.0);
        assert_eq!(z.luminous_zones,9);
    }

    #[test]
    fn flat_black_field_and_small_light_do_not_trigger_subject_lift() {
        let img=Rgb32f::from_fn(270,270,|x,y|{
            if x>124 && x<145 && y>84 && y<139 {[0.90,0.58,0.15]}
            else {[0.005,0.005,0.005]}
        });
        let z=analyse_linear(&img);
        assert_eq!(z.shadow_detail_zones,0);
        assert_eq!(z.proposed_shadow_recovery,0.0);
    }

    #[test]
    fn evenly_balanced_photo_without_dark_detail_requires_no_lift() {
        let img=Rgb32f::from_fn(270,270,|x,y|{
            let v=0.35+((x/38+y/38)%3) as f32*0.03;
            [v,v,v]
        });
        let z=analyse_linear(&img);
        assert_eq!(z.proposed_shadow_recovery,0.0);
    }

    #[test]
    fn truly_dark_but_textured_subject_with_bright_reference_is_distinct() {
        let img=Rgb32f::from_fn(270,270,|x,y|{
            if (120..150).contains(&x)&&(70..210).contains(&y) {
                [0.82,0.82,0.82]
            } else {
                let v=0.055+((x*11+y*13)%19) as f32*0.004;
                [v,v,v]
            }
        });
        let z=analyse_linear(&img);
        assert!(z.dark_texture_with_bright_reference,"textured detail missed: {z:?}");
        assert!(z.proposed_shadow_recovery>0.0);
        assert!(!z.bright_surround_dark_detail);
    }
}
