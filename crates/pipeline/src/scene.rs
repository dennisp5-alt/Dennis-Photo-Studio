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


/// Summary of the 32×32 fine-grid analysis, not a mask or 1,024 edits.
/// Every cell is analysed in the same linear working space as the 3×3 grid.
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize)]
pub struct FineSceneEvidence {
    pub grid_side:usize,
    pub populated_zones:usize,
    pub luminous_zones:usize,
    pub textured_shadow_zones:usize,
    pub empty_dark_zones:usize,
    pub luminous_coverage:f64,
    pub shadow_detail_coverage:f64,
    /// Tiny bright source set against broad darkness: diagnostic only.
    pub concentrated_lights:bool,
    /// Fine dark texture amid extensive brighter scenery, including subjects
    /// diluted in a large 3×3 zone. Authorises modest Shadows/Curve evidence,
    /// never an unconditional exposure reduction.
    pub mixed_light_shadow_detail:bool,
    pub proposed_shadow_recovery:f64,
    pub proposed_darks_curve:f64,
}

const FINE_SIDE:usize=32;
const FINE_ZONES:usize=FINE_SIDE*FINE_SIDE;

#[derive(Clone, Copy, Default)]
struct FineCell {
    count:u32,
    bright:u32,
    recoverable:u32,
    dark_texture:u32,
    empty_black:u32,
}

/// A fixed-size, single-pass grid: O(pixels + 1,024) with no extra pixels,
/// heap-allocated zone arrays, semantic model, or per-zone render. We retain
/// aggregate evidence only; edits remain global and non-destructive.
fn analyse_fine_linear(img:&Rgb32f,lum:&[f32])->FineSceneEvidence {
    let mut cells=[FineCell::default();FINE_ZONES];
    for y in 0..img.height {
        for x in 0..img.width {
            let index=y*img.width+x;
            let cell=(y*FINE_SIDE/img.height).min(FINE_SIDE-1)*FINE_SIDE
                +(x*FINE_SIDE/img.width).min(FINE_SIDE-1);
            let l=lum[index];
            let c=&mut cells[cell];
            c.count+=1;
            if l>0.48 {c.bright+=1;}
            if (0.035..0.28).contains(&l) {c.recoverable+=1;}
            if l<0.012 {c.empty_black+=1;}
            // Real mid-shadow structure, not black sky or near-zero noise.
            if x>0 && y>0 && (0.035..0.24).contains(&l) {
                let a=lum[index-1];
                let b=lum[index-img.width];
                let edge=((l-a).abs()+(l-b).abs())/(0.035+l+a+b);
                if edge>0.058 {c.dark_texture+=1;}
            }
        }
    }
    let mut result=FineSceneEvidence {grid_side:FINE_SIDE,..Default::default()};
    for c in cells {
        if c.count==0 {continue;}
        result.populated_zones+=1;
        let n=c.count as f64;
        // Require meaningful within-tile coverage; a lone bright pixel or
        // noisy edge never becomes an independently informative zone.
        if c.bright as f64/n>0.35 {result.luminous_zones+=1;}
        if c.recoverable as f64/n>0.22
            && c.dark_texture>=2
            && c.dark_texture as f64/n>0.012 {
            result.textured_shadow_zones+=1;
        }
        if c.empty_black as f64/n>0.85 {result.empty_dark_zones+=1;}
    }
    let n=result.populated_zones.max(1) as f64;
    result.luminous_coverage=result.luminous_zones as f64/n;
    result.shadow_detail_coverage=result.textured_shadow_zones as f64/n;
    // The luminous reference must cover multiple independent fine tiles.
    // A small flame/isolated lamp amid otherwise black sky does not license
    // whole-frame exposure; this flag is observational, not a veto.
    result.concentrated_lights=result.luminous_zones>=2
        && result.luminous_coverage<0.08
        && result.empty_dark_zones as f64/n>0.55;
    // At least 15% bright cells, and real *other* cells with shadow texture.
    // This distinguishes small dark subjects lost inside large bright zones
    // from a photo whose global histogram is simply low-key.
    result.mixed_light_shadow_detail=result.luminous_coverage>0.15
        && result.textured_shadow_zones>=3
        && result.shadow_detail_coverage>0.002
        && result.shadow_detail_coverage<0.40;
    if result.mixed_light_shadow_detail {
        result.proposed_shadow_recovery=(7.0+
            32.0*result.shadow_detail_coverage.min(0.20)).clamp(7.0,13.0).round();
        result.proposed_darks_curve=(3.0+
            18.0*result.shadow_detail_coverage.min(0.20)).clamp(3.0,7.0).round();
    }
    result
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize)]
pub struct ZonalSceneEvidence {
    /// Row-major 3x3 zones (top-left to bottom-right).
    pub zones:[ZoneEvidence;9],
    pub fine:FineSceneEvidence,
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
    // Do not confuse a natural top-to-bottom landscape gradient
    // (bright sky, medium ground, dark foreground) with a dark subject
    // surrounded by bright water. Independent bright and dark textured
    // areas must coexist in the image's middle band. The established
    // top-vs-bottom backlit-landscape path handles genuine foreground
    // deficits separately.
    let middle_bright=zones[3..6].iter()
        .filter(|z|z.bright_fraction>0.38 && z.median_luma>0.34).count();
    let middle_dark_detail=zones[3..6].iter()
        .filter(|z|z.dark_detail_fraction>0.007
            && z.recoverable_fraction>0.095).count();
    let bright_surround_dark_detail=luminous_zones>=3
        && shadow_detail_zones>=1 && tonal_separation>0.20
        && detail_peak>0.014 && middle_bright>=2
        && middle_dark_detail>=1;
    // Dim product or portrait with a *small real bright reference*: can
    // have useful dark texture even if global histogram says 'low key'.
    // This signal alone does NOT authorize brightening the whole frame.
    let dark_texture_with_bright_reference=luminous_zones<=2
        && shadow_detail_zones>=2
        && zones.iter().any(|z|z.p90_luma>0.56)
        && detail_peak>0.014;
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
        zones,fine:analyse_fine_linear(img,&lum),
        luminous_zones,shadow_detail_zones,tonal_separation,
        bright_surround_dark_detail,dark_texture_with_bright_reference,
        proposed_shadow_recovery:strength.round(),
        proposed_darks_curve:darks.round(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fine_grid_finds_small_dark_subject_diluted_in_a_three_by_three_tile() {
        let img=Rgb32f::from_fn(256,192,|x,y|{
            if (124..147).contains(&x) && (88..115).contains(&y) {
                let detail=((x*17+y*23)%13) as f32*0.004;
                [0.055+detail,0.067+detail,0.052+detail]
            } else {[0.62,0.65,0.67]}
        });
        let z=analyse_linear(&img);
        assert_eq!(z.fine.grid_side,32);
        assert_eq!(z.fine.populated_zones,1024);
        assert!(z.fine.luminous_zones>100);
        assert!(z.fine.textured_shadow_zones>=3,"fine-grid detail missed: {:?}",z.fine);
        assert!(z.fine.mixed_light_shadow_detail,"small dark subject missed: {:?}",z.fine);
        assert!(z.fine.proposed_shadow_recovery>=7.0);
    }

    #[test]
    fn fine_grid_never_forces_a_black_background_into_a_shadow_subject() {
        let img=Rgb32f::from_fn(256,192,|x,y|{
            if (120..138).contains(&x)&&(68..110).contains(&y) {
                [0.95,0.55,0.10]
            }else{[0.003,0.003,0.003]}
        });
        let z=analyse_linear(&img);
        assert!(z.fine.concentrated_lights,"small light missed: {:?}",z.fine);
        assert!(!z.fine.mixed_light_shadow_detail);
        assert_eq!(z.fine.proposed_shadow_recovery,0.0);
        assert_eq!(z.fine.proposed_darks_curve,0.0);
    }

    #[test]
    fn fine_grid_small_or_empty_images_are_safe() {
        let tiny=Rgb32f::filled(27,27,[0.12,0.12,0.12]);
        let z=analyse_linear(&tiny);
        assert_eq!(z.fine.grid_side,32);
        assert_eq!(z.fine.populated_zones,27*27);
        assert!(!z.fine.mixed_light_shadow_detail);
        assert_eq!(analyse_linear(&Rgb32f::filled(0,0,[0.0,0.0,0.0])).fine.populated_zones,0);
    }

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
        assert!(z.luminous_zones>=7);
    }

    #[test]
    fn bright_sky_over_textured_ground_is_not_dark_subject_surrounded() {
        let img=Rgb32f::from_fn(256,192,|x,y|{
            let detail=((x*29+y*43)%23) as f32/23.0*0.024;
            if y<64 {[0.43+detail,0.55+detail,0.66+detail]}
            else if y<145 {[0.15+detail,0.29+detail,0.15+detail]}
            else {[0.055+detail,0.070+detail,0.052+detail]}
        });
        let z=analyse_linear(&img);
        assert!(!z.bright_surround_dark_detail,
            "Finished landscape mistaken for reflective water: {z:?}");
        assert_eq!(z.proposed_darks_curve,0.0);
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
