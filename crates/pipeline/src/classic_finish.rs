//! v0.6.33 Classic Auto: conservative finishing from rendered scene statistics.
//! This is NOT a face-recognition model. A local backlit-subject lift is
//! proposed only when several independent photographic cues agree.
//! Existing user masks, mixer bands and manual WB remain authoritative.
use crate::{RenderRequest,SourceInfo};
use lightcraft_develop::{DevelopSettings,Mask,MaskComponent,MaskOp,MaskShape,LocalAdjustments,Hsl};
use lightcraft_geom::Point;
use lightcraft_raster::Rgb32f;
use serde::Serialize;

const AUTO_MASK_NAME:&str="Classic Auto · Backlit Subject";

#[derive(Clone,Copy,Debug,Default,Serialize)]
pub struct FinishEvidence{
    pub bright_red_fraction:f64,
    pub near_white_fraction:f64,
    pub foliage_fraction:f64,
    pub twilight_grass_fraction:f64,
    pub backlit_subject_confidence:f64,
    pub local_subject_lift:bool,
    pub red_highlight_protection:bool,
    pub white_highlight_protection:bool,
    pub twilight_colour_protection:bool,
}

#[derive(Clone,Copy,Debug,Default)]
struct Sample {
    lum:f64,red:f64,green:f64,blue:f64,
    skin:bool,greenery:bool,yellow_grass:bool,red_hot:bool,white:bool,
}
fn channel(v:u8)->f64 {f64::from(v)/255.0}
fn analyse(p:&[u8;4],smooth:bool)->Sample{
    let(r,g,b)=(channel(p[0]),channel(p[1]),channel(p[2]));
    let y=0.2126*r+0.7152*g+0.0722*b;
    // Saturated petal highlights are dangerous before whole-frame luminance
    // reaches a clipping threshold. Ignore merely warm skin (orange).
    let hot=r>0.90 && r>g*1.42 && r>b*1.55 && g<0.73;
    let skin=smooth && y>0.14 && y<0.74 && r>g*1.08
        && g>b*1.045 && r<g*1.8 && (r-g)<0.34;
    let green=g>r*1.08 && g>b*1.15 && y>0.10;
    let yellow=r>0.27 && g>0.25 && b<g*0.77
        && r>g*0.79 && r<g*1.38 && y>0.16;
    Sample {lum:y,red:r,green:g,blue:b,skin,greenery:green,
        yellow_grass:yellow,red_hot:hot,white:r>0.915&&g>0.915&&b>0.915}
}
/// Evidence gate: no local lift for fog, flowers, bark or low light.
pub fn refine(src:&Rgb32f,info:&SourceInfo,current:&DevelopSettings,
              wanted:&mut DevelopSettings)->FinishEvidence {
    let mut e=FinishEvidence::default();
    if src.width<40 || src.height<40{return e;}
    let preview=lightcraft_raster::resample::fit(src,256,256,lightcraft_raster::resample::Filter::Box);
    let picture=crate::render(&preview,info,current,&RenderRequest::fit(256,256)).image;
    let(w,h)=(picture.width,picture.height);
    if w<40||h<40||picture.data.len()!=w*h{return e;}
    let count=picture.data.len();
    let mut pixels=Vec::with_capacity(count);
    let mut luma=Vec::with_capacity(count);
    for p in &picture.data {
        let r=channel(p[0]);let g=channel(p[1]);let b=channel(p[2]);
        let y=0.2126*r+0.7152*g+0.0722*b;
        luma.push(y);
    }
    let(mut hot,mut whites,mut foliage,mut grass,mut top_blue)=(0usize,0usize,0usize,0usize,0usize);
    let(mut top_y,mut top_n)=(0.0f64,0usize);
    let(mut skin_n,mut skin_y,mut sx,mut sy)=(0usize,0.0f64,0.0f64,0.0f64);
    let(mut skin_smooth,mut centre_n)=(0usize,0usize);
    for(y,p)in picture.data.iter().enumerate(){
        let x=y%w;let yy=y/w;
        let gradient=if x>0&&yy>0 {
            (luma[y]-luma[y-1]).abs()+(luma[y]-luma[y-w]).abs()
        }else{0.0};
        let smooth=gradient<0.085;
        let s=analyse(p,smooth);
        if s.red_hot{hot+=1;}
        if s.white{whites+=1;}
        if s.greenery{foliage+=1;}
        if yy>h*3/5 && s.yellow_grass{grass+=1;}
        if yy<h/3 {
            top_n+=1;top_y+=s.lum;
            if s.blue>s.red*1.09 && s.blue>s.green*1.06{top_blue+=1;}
        }
        let in_centre=x>=w/5 && x<=w*4/5
            && yy>=h/8 && yy<h*3/5;
        if in_centre {
            centre_n+=1;
            if s.skin {
                skin_n+=1;skin_y+=s.lum;sx+=x as f64;sy+=yy as f64;
                if gradient<0.045{skin_smooth+=1;}
            }
        }
        pixels.push(s);
    }
    let n=count as f64;
    e.bright_red_fraction=hot as f64/n;
    e.near_white_fraction=whites as f64/n;
    e.foliage_fraction=foliage as f64/n;
    e.twilight_grass_fraction=grass as f64/(w*(h-h*3/5)).max(1) as f64;
    let top_mean=top_y/top_n.max(1) as f64;

    // Work through Lightroom-compatible existing mixer bands, rather than
    // altering the global saturation setting and therefore skin/sky.
    if e.bright_red_fraction>0.035 {
        let strength=((e.bright_red_fraction-0.035)*42.0+3.0).clamp(3.0,8.0);
        if wanted.mixer.red==Hsl::default() && current.mixer.red==Hsl::default(){
            wanted.mixer.red.sat=-strength;
            wanted.mixer.red.lum=-(strength*0.45).round();
            e.red_highlight_protection=true;
        }
        if wanted.mixer.magenta==Hsl::default() && current.mixer.magenta==Hsl::default(){
            wanted.mixer.magenta.sat=-(strength*0.7);
            e.red_highlight_protection=true;
        }
    }
    // White petals among substantial green foliage are a more reliable
    // highlight reference than broad sky or pale skin alone.
    if e.near_white_fraction>0.032 && e.foliage_fraction>0.08 {
        wanted.light.highlights=wanted.light.highlights.min(-11.0);
        wanted.light.whites=wanted.light.whites.min(-5.0);
        e.white_highlight_protection=true;
    }
    // Yellow-green fields at twilight are not evidence that every coloured
    // object needs more vibrance. Do not touch warm skin or flowers.
    if top_mean>0.23 && top_mean<0.53
        && top_blue as f64/top_n.max(1) as f64>0.15
        && e.twilight_grass_fraction>0.14
        && e.bright_red_fraction<0.025 {
        if current.mixer.yellow==Hsl::default(){
            wanted.mixer.yellow.sat=-5.0;
            e.twilight_colour_protection=true;
        }
        if current.mixer.green==Hsl::default(){
            wanted.mixer.green.sat=-3.0;
            e.twilight_colour_protection=true;
        }
        wanted.color.vibrance=wanted.color.vibrance.min(7.0);
    }

    let fraction=skin_n as f64/centre_n.max(1) as f64;
    let skin_average=skin_y/skin_n.max(1) as f64;
    let subject_dim=top_mean-skin_average;
    let smooth_fraction=skin_smooth as f64/skin_n.max(1) as f64;
    // Strong sky + plausible smooth skin region + backlighting.
    // The model-independent fallback deliberately stays off if uncertain.
    if top_mean>0.58 && top_blue as f64/top_n.max(1) as f64>0.13
        && fraction>0.07 && fraction<0.58
        && subject_dim>0.16 && skin_average>0.14 && skin_average<0.57
        && smooth_fraction>0.42 && e.near_white_fraction<0.16
        && current.masks.iter().all(|m|m.name!=AUTO_MASK_NAME) {
        let cx=(sx/skin_n as f64/w as f64).clamp(0.22,0.78);
        let cy=(sy/skin_n as f64/h as f64).clamp(0.17,0.59);
        // Mask radii use long-edge coordinates, not width/height units.
        let long=w.max(h) as f64;
        let strength=((subject_dim-0.16)*1.7).clamp(0.14,0.31);
        let mask=Mask {
            id:current.masks.iter().map(|m|m.id).max().unwrap_or(0).saturating_add(1),
            name:AUTO_MASK_NAME.into(),
            visible:true,
            components:vec![
                MaskComponent {
                    name:None,op:MaskOp::Add,invert:false,hidden:false,
                    shape:MaskShape::Radial {
                        center:Point::new(cx,cy),
                        rx:(w as f64/long*0.26).max(0.12),
                        ry:(h as f64/long*0.38).max(0.16),
                        angle:0.0,feather:88.0,invert:false
                    },
                },
                MaskComponent {
                    name:None,op:MaskOp::Intersect,invert:false,hidden:false,
                    shape:MaskShape::LuminanceRange{
                        lo:0.22,hi:0.70,lo_feather:0.12,hi_feather:0.10,
                        picked:vec![],ranked:false,ranks:None
                    }
                }
            ],
            adjust:LocalAdjustments{exposure:strength,shadows:12.0,highlights:-3.0,
                ..Default::default()},
            ..Default::default()
        };
        wanted.masks.push(mask);
        e.backlit_subject_confidence=(
            0.35+0.45*((subject_dim-0.16)*2.0).clamp(0.0,1.0)
            +0.20*smooth_fraction
        ).clamp(0.0,1.0);
        e.local_subject_lift=true;
    }
    e
}
#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn isolated_fire_or_black_night_does_not_lift_subject(){
        let src=Rgb32f::from_fn(240,180,|x,y|{
            if x>85 && x<140 && y>70 && y<125{[0.91,0.22,0.016]}
            else{[0.002,0.002,0.003]}
        });
        let d=DevelopSettings::default();let mut w=d.clone();
        let e=refine(&src,&SourceInfo::default(),&d,&mut w);
        assert!(!e.local_subject_lift);
        assert!(w.masks.is_empty());
    }
    #[test]
    fn neutral_bright_studio_has_no_colour_fabrication(){
        let src=Rgb32f::filled(180,180,[0.50,0.50,0.50]);
        let d=DevelopSettings::default();let mut w=d.clone();
        let e=refine(&src,&SourceInfo::default(),&d,&mut w);
        assert!(!e.red_highlight_protection);
        assert!(!e.twilight_colour_protection);
        assert!(!e.local_subject_lift);
        assert_eq!(w.mixer,d.mixer);
    }
    #[test]
    fn existing_manual_red_mixer_is_preserved(){
        let src=Rgb32f::from_fn(192,192,|x,y|{
            if (x/12+y/12)%2==0{[0.87,0.05,0.10]}else{[0.72,0.02,0.08]}
        });
        let mut d=DevelopSettings::default();d.mixer.red.sat=-27.0;
        let mut w=d.clone();
        let _=refine(&src,&SourceInfo::default(),&d,&mut w);
        assert_eq!(w.mixer.red,d.mixer.red);
    }
}
