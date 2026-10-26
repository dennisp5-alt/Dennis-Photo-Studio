//! Smart Auto A/B: renders each photograph three times — as it is, with the
//! v0.6.30 Smart Auto, and with the current (v0.6.32) Smart Auto — side by side,
//! plus the values each chose. White balance is decided once (Auto WB, as the
//! one-tap command does) and applied to both, so only the tone logic differs.
//!
//! usage: auto_compare <input dir> <output dir>
//! Inputs: .jpg/.jpeg/.png treated as sRGB camera JPEGs (no ICC handling), and
//! a few synthetic scenes the protections must keep (night, fire, Moon).
use std::{env, error::Error, fs, path::Path};
use lightcraft_develop::{DevelopSettings, WbMode, WhiteBalance};
use lightcraft_pipeline::auto::{AutoTone, AutoToneAuditReport};
use lightcraft_pipeline::{RenderRequest, SourceInfo};
use lightcraft_raster::{Rgb32f, Rgba8};
use serde_json::json;

const EDGE: usize = 560;

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

/// Synthetic scenes whose established protections must survive.
fn synthetic() -> Vec<(String, Rgb32f)> {
    let fire = Rgb32f::from_fn(640, 480, |x, y| {
        let (dx, dy) = (x as f32 - 320.0, y as f32 - 260.0);
        let d = (dx * dx / 1600.0 + dy * dy / 900.0).sqrt();
        if d < 1.0 { [0.98, 0.52 - 0.2 * d, 0.06] }
        else if y > 300 && (x / 9 + y / 7) % 3 == 0 { [0.09, 0.05, 0.03] }
        else if y > 300 { [0.05, 0.03, 0.02] }
        else { [0.004, 0.004, 0.005] }
    });
    let moon = Rgb32f::from_fn(640, 480, |x, y| {
        let (dx, dy) = (x as f32 - 330.0, y as f32 - 210.0);
        if dx * dx + dy * dy < 55.0 * 55.0 { [0.93, 0.93, 0.92] } else { [0.006, 0.007, 0.012] }
    });
    let petals = Rgb32f::from_fn(640, 480, |x, y| {
        let (dx, dy) = (x as f32 - 320.0, y as f32 - 240.0);
        let r = (dx * dx + dy * dy).sqrt();
        let a = dy.atan2(dx) * 5.0;
        if r < 90.0 + 40.0 * a.cos().abs() { [0.90, 0.91, 0.88] }
        else { let t = ((x * 7 + y * 13) % 17) as f32 * 0.003; [0.02 + t, 0.07 + t, 0.02 + t] }
    });
    let night_street = Rgb32f::from_fn(640, 480, |x, y| {
        let lamp = ((x as f32 - 500.0).powi(2) + (y as f32 - 80.0).powi(2)).sqrt() < 14.0;
        if lamp { [0.97, 0.80, 0.45] }
        else { let t = ((x * 3 + y * 5) % 23) as f32 * 0.0008; [0.010 + t, 0.009 + t, 0.008 + t] }
    });
    vec![
        ("zz_synthetic_fire".into(), fire),
        ("zz_synthetic_moon".into(), moon),
        ("zz_synthetic_white_flower_dark".into(), petals),
        ("zz_synthetic_night_street".into(), night_street),
    ]
}

fn with_tone(s: &DevelopSettings, a: &AutoTone) -> DevelopSettings {
    let mut t = s.clone();
    t.light.exposure = a.exposure;
    t.light.contrast = a.contrast;
    t.light.highlights = a.highlights;
    t.light.shadows = a.shadows;
    t.light.whites = a.whites;
    t.light.blacks = a.blacks;
    t.color.vibrance = a.vibrance;
    t.color.saturation = a.saturation;
    t.curve.shadows = a.curve_shadows;
    t.curve.darks = a.curve_darks;
    t.curve.lights = a.curve_lights;
    t.curve.highlights = a.curve_highlights;
    t
}

/// The settings the one-tap command would apply: tone, WB, scene-approved Dehaze.
fn applied(src: &Rgb32f, info: &SourceInfo, base: &DevelopSettings, wb: Option<WhiteBalance>, tone: &AutoTone) -> DevelopSettings {
    let mut s = with_tone(base, tone);
    if let Some(wb) = wb { s.wb = wb; }
    let scene = lightcraft_pipeline::scene_auto::evaluate(src, info, &s);
    if s.effects.dehaze.abs() < 0.001 && scene.dehaze > 0.0 { s.effects.dehaze = scene.dehaze; }
    s
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

fn tone_json(a: &AutoTone) -> serde_json::Value {
    json!({"exposure": a.exposure, "contrast": a.contrast, "highlights": a.highlights, "shadows": a.shadows,
           "whites": a.whites, "blacks": a.blacks, "vibrance": a.vibrance, "saturation": a.saturation,
           "curve": [a.curve_shadows, a.curve_darks, a.curve_lights, a.curve_highlights]})
}

/// Fraction of pure-white (all channels >= 254) and pure-black pixels, and mean luma.
fn stats(img: &Rgba8) -> (f64, f64, f64) {
    let n = img.data.len().max(1) as f64;
    let mut white = 0usize;
    let mut black = 0usize;
    let mut luma = 0.0;
    for c in &img.data {
        if c[0] >= 254 && c[1] >= 254 && c[2] >= 254 { white += 1; }
        if c[0] <= 1 && c[1] <= 1 && c[2] <= 1 { black += 1; }
        luma += (54.0 * c[0] as f64 + 183.0 * c[1] as f64 + 19.0 * c[2] as f64) / 256.0;
    }
    (white as f64 / n, black as f64 / n, luma / n)
}

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<String> = env::args().collect();
    if args.len() != 3 { return Err("usage: auto_compare <input dir> <output dir>".into()); }
    let out = Path::new(&args[2]);
    fs::create_dir_all(out)?;
    let mut cases: Vec<(String, Rgb32f)> = Vec::new();
    let mut names: Vec<_> = fs::read_dir(&args[1])?.filter_map(|e| e.ok().map(|e| e.path())).collect();
    names.sort();
    for p in names {
        let ext = p.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
        if !["jpg", "jpeg", "png"].contains(&ext.as_str()) { continue; }
        match load(&p) {
            Ok(img) => cases.push((p.file_stem().and_then(|s| s.to_str()).unwrap_or("photo").to_string(), img)),
            Err(e) => eprintln!("skip {}: {e}", p.display()),
        }
    }
    cases.extend(synthetic());
    let info = SourceInfo::default();
    let base = DevelopSettings::default();
    let req = RenderRequest::fit(EDGE, EDGE);
    let mut rows = Vec::new();
    for (name, src) in &cases {
        let report = lightcraft_pipeline::auto::auto_wb_report_balanced(src, &info);
        let wb = Some(WhiteBalance { mode: WbMode::Auto, temp: report.temp, tint: report.tint });
        let old: AutoToneAuditReport = lightcraft_pipeline::auto::smart_auto_tone_report_balanced_v0630(src, &info, &base);
        let new = lightcraft_pipeline::auto::smart_auto_tone_report_balanced_with_wb(src, &info, &base, wb);
        let s_old = applied(src, &info, &base, wb, &old.final_tone);
        let s_new = applied(src, &info, &base, wb, &new.final_tone);
        let r0 = lightcraft_pipeline::render(src, &info, &base, &req).image;
        let r1 = lightcraft_pipeline::render(src, &info, &s_old, &req).image;
        let r2 = lightcraft_pipeline::render(src, &info, &s_new, &req).image;
        side_by_side(&[r0.clone(), r1.clone(), r2.clone()]).save(out.join(format!("{name}.jpg")))?;
        let (w0, b0, l0) = stats(&r0);
        let (w1, b1, l1) = stats(&r1);
        let (w2, b2, l2) = stats(&r2);
        let row = json!({
            "name": name,
            "evidence": {"median_ev": new.evidence.median_ev, "p05_ev": new.evidence.p05_ev,
                         "p95_ev": new.evidence.p95_ev, "p995_ev": new.evidence.p995_ev,
                         "dark": new.evidence.dark_fraction, "bright": new.evidence.bright_fraction,
                         "micro_white": new.evidence.zonal.micro.neutral_white_subject,
                         "nano_white": new.evidence.zonal.nano.neutral_white_subject},
            "wb": {"decision": report.decision, "reason": report.reason, "temp": report.temp, "tint": report.tint},
            "old": {"proposed": tone_json(&old.proposed), "final": tone_json(&old.final_tone), "guard": old.guard_reasons,
                    "white": w1, "black": b1, "mean_luma": l1},
            "new": {"proposed": tone_json(&new.proposed), "final": tone_json(&new.final_tone), "guard": new.guard_reasons,
                    "exposure_correction": new.exposure_correction_ev,
                    "white": w2, "black": b2, "mean_luma": l2},
            "original": {"white": w0, "black": b0, "mean_luma": l0},
        });
        println!("{name}: luma {l0:.0} -> old {l1:.0} / new {l2:.0}; EV old {:+.2} new {:+.2} (stage 1 {:+.2}); white% {:.2}/{:.2}/{:.2}; black% {:.2}/{:.2}/{:.2}; guard old {:?} new {:?}",
            old.final_tone.exposure, new.final_tone.exposure, new.exposure_correction_ev,
            w0 * 100.0, w1 * 100.0, w2 * 100.0, b0 * 100.0, b1 * 100.0, b2 * 100.0,
            old.guard_reasons, new.guard_reasons);
        rows.push(row);
    }
    fs::write(out.join("results.json"), serde_json::to_vec_pretty(&rows)?)?;
    Ok(())
}
