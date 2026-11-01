//! Per-mask Auto on test photographs: for each `<name>.png` with a saliency matte
//! `<name>.seg.json` ({"side": n, "logits": [..]}) beside it, Auto on the Subject mask
//! and, separately, on the Background mask (its complement).
//!
//! Output per photo: original | Subject Auto | Background Auto, and the values chosen.
//!
//! usage: mask_auto_compare <input dir> <output dir>
use std::{env, error::Error, fs, path::Path};
use lightcraft_develop::{DevelopSettings, Mask, MaskComponent, MaskOp, MaskShape, SegMask};
use lightcraft_pipeline::mask_auto::mask_auto;
use lightcraft_pipeline::{RenderRequest, SourceInfo};
use lightcraft_raster::{Rgb32f, Rgba8};
use serde_json::json;

const EDGE: usize = 520;

fn decode_srgb(x: u8) -> f32 {
    let v = x as f32 / 255.0;
    if v <= 0.04045 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) }
}

fn load(path: &Path) -> Result<Rgb32f, Box<dyn Error>> {
    let rgb = image::open(path)?.thumbnail(1024, 1024).to_rgb8();
    let (w, h) = rgb.dimensions();
    Ok(Rgb32f::from_fn(w as usize, h as usize, |x, y| {
        let p = rgb.get_pixel(x as u32, y as u32).0;
        let (r, g, b) = (decode_srgb(p[0]), decode_srgb(p[1]), decode_srgb(p[2]));
        [0.627404 * r + 0.329283 * g + 0.043313 * b,
         0.069097 * r + 0.919540 * g + 0.011362 * b,
         0.016391 * r + 0.088013 * g + 0.895595 * b]
    }))
}

fn side_by_side(panels: &[Rgba8]) -> image::RgbImage {
    let gap = 6u32;
    let h = panels.iter().map(|p| p.height as u32).max().unwrap_or(1);
    let w: u32 = panels.iter().map(|p| p.width as u32).sum::<u32>() + gap * (panels.len() as u32 - 1);
    let mut out = image::RgbImage::from_pixel(w, h, image::Rgb([24, 24, 24]));
    let mut x0 = 0u32;
    for p in panels {
        for y in 0..p.height {
            for x in 0..p.width {
                let c = p.data[y * p.width + x];
                out.put_pixel(x0 + x as u32, y as u32, image::Rgb([c[0], c[1], c[2]]));
            }
        }
        x0 += p.width as u32 + gap;
    }
    out
}

fn with_mask(name: &str, seg: &SegMask) -> DevelopSettings {
    let mut s = DevelopSettings::default();
    s.masks.push(Mask {
        id: 1,
        name: name.into(),
        components: vec![MaskComponent {
            hidden: false,
            name: None,
            op: MaskOp::Add,
            invert: false,
            shape: MaskShape::Object { hint: vec![], exclude: vec![], seg: Some(seg.clone()), detail: vec![], edge: 0.0 },
        }],
        ..Default::default()
    });
    s
}

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<String> = env::args().collect();
    if args.len() != 3 { return Err("usage: mask_auto_compare <input dir> <output dir>".into()); }
    let out = Path::new(&args[2]);
    fs::create_dir_all(out)?;
    let mut names: Vec<_> = fs::read_dir(&args[1])?.filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().is_some_and(|x| x == "png")).collect();
    names.sort();
    let info = SourceInfo::default();
    let req = RenderRequest::fit(EDGE, EDGE);
    let mut all = Vec::new();
    for path in names {
        let stem = path.file_stem().unwrap_or_default().to_string_lossy().to_string();
        let seg_path = path.with_file_name(format!("{stem}.seg.json"));
        let Ok(text) = fs::read_to_string(&seg_path) else { continue };
        let v: serde_json::Value = serde_json::from_str(&text)?;
        let side = v["side"].as_u64().unwrap_or(0) as usize;
        let logits: Vec<f32> = v["logits"].as_array().map(|a| a.iter().filter_map(|x| x.as_f64()).map(|x| x as f32).collect()).unwrap_or_default();
        if side < 8 || logits.len() != side * side { continue; }
        let subject = SegMask::from_logits(side, &logits);
        let negated: Vec<f32> = logits.iter().map(|l| -l).collect();
        let background = SegMask::from_logits(side, &negated);
        let src = load(&path)?;
        let original = lightcraft_pipeline::render(&src, &info, &DevelopSettings::default(), &req).image;
        let mut panels = vec![original];
        let mut row = json!({"photo": stem});
        for (label, seg) in [("Select Subject", &subject), ("Select Background", &background)] {
            let mut s = with_mask(label, seg);
            match mask_auto(&src, &info, &s, 1) {
                Ok(r) => {
                    s.masks[0].adjust = r.adjust;
                    panels.push(lightcraft_pipeline::render(&src, &info, &s, &req).image);
                    row[label] = serde_json::to_value(&r)?;
                    println!("{stem:28} {label:18} {:?} cover {:.2} median {:.0}→{:.0} (rest {:?}) exp {:+.2} hi {} sh {} wh {} bl {} sat {} dh {} {:?}",
                        r.kind, r.coverage, r.before.median, r.after.median, r.rest_median.map(|m| m.round()),
                        r.adjust.exposure, r.adjust.highlights, r.adjust.shadows, r.adjust.whites, r.adjust.blacks,
                        r.adjust.saturation, r.adjust.dehaze, r.notes);
                }
                Err(e) => {
                    println!("{stem:28} {label:18} refused: {e}");
                    row[label] = json!({"refused": e});
                }
            }
        }
        side_by_side(&panels).save(out.join(format!("{stem}_mask_auto.jpg")))?;
        all.push(row);
    }
    fs::write(out.join("mask_auto.json"), serde_json::to_string_pretty(&all)?)?;
    Ok(())
}
