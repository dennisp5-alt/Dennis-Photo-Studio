//! Local photographic QA tool. Source images stay on the evaluator's computer.
//! Diagnostic import treats camera JPEGs as sRGB; the app's ICC-aware import is
//! independently covered by its codec tests.
use std::{env,error::Error,fs,fs::File,io::Write};
use lightcraft_develop::DevelopSettings;
use lightcraft_raster::{Rgb32f,Rgba8};
use lightcraft_pipeline::{SourceInfo,RenderRequest};

fn decode_srgb(x: u8) -> f32 {
    let v=x as f32/255.0;
    if v <= 0.04045 {v/12.92} else {((v+0.055)/1.055).powf(2.4)}
}
fn save_ppm(path:&str, rgba:&Rgba8) -> std::io::Result<()> {
    let mut f=File::create(path)?;
    write!(f,"P6\n{} {}\n255\n",rgba.width,rgba.height)?;
    for c in &rgba.data {f.write_all(&c[..3])?;}
    Ok(())
}
fn main() -> Result<(), Box<dyn Error>> {
    let a:Vec<String>=env::args().collect();
    if a.len()!=3 {return Err("usage: photo_eval source.jpg output_prefix".into());}
    let rgb=image::open(&a[1])?.thumbnail(1024,1024).to_rgb8();
    let (w,h)=rgb.dimensions();
    let src=Rgb32f::from_fn(w as usize,h as usize,|x,y|{
        let p=rgb.get_pixel(x as u32,y as u32).0;
        let r=decode_srgb(p[0]);let g=decode_srgb(p[1]);let b=decode_srgb(p[2]);
        [0.627404*r+0.329283*g+0.043313*b,
         0.069097*r+0.919540*g+0.011362*b,
         0.016391*r+0.088013*g+0.895595*b]
    });
    let info=SourceInfo::default();
    let base=DevelopSettings::default();
    let audit=lightcraft_pipeline::auto::smart_auto_tone_report_balanced(&src,&info,&base);
    let t=audit.final_tone;
    let scene=lightcraft_pipeline::scene_auto::evaluate(&src,&info,&base);
    let mut adjusted=base.clone();
    adjusted.light.exposure=t.exposure;
    adjusted.light.contrast=t.contrast;
    adjusted.light.highlights=t.highlights;
    adjusted.light.shadows=t.shadows;
    adjusted.light.whites=t.whites;
    adjusted.light.blacks=t.blacks;
    adjusted.color.vibrance=t.vibrance;
    adjusted.color.saturation=t.saturation;
    adjusted.curve.shadows=t.curve_shadows;
    adjusted.curve.darks=t.curve_darks;
    adjusted.curve.lights=t.curve_lights;
    adjusted.curve.highlights=t.curve_highlights;
    if scene.dehaze>0.0 { adjusted.effects.dehaze=scene.dehaze; }
    let req=RenderRequest::fit(1024,1024);
    let before=lightcraft_pipeline::render(&src,&info,&base,&req).image;
    let after=lightcraft_pipeline::render(&src,&info,&adjusted,&req).image;
    save_ppm(&format!("{}_before.ppm",a[2]),&before)?;
    save_ppm(&format!("{}_after.ppm",a[2]),&after)?;
    fs::write(format!("{}_report.json",a[2]),serde_json::to_vec_pretty(&audit)?)?;
    fs::write(format!("{}_scene.json",a[2]),serde_json::to_vec_pretty(&scene)?)?;
    Ok(())
}
