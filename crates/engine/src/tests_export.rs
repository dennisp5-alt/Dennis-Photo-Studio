//! Export end to end: output sizes, original + XMP, DNG.

use serde_json::json;

use crate::Session;
use crate::export::{ExportFormat, ExportOptions, export_photo};
use crate::tests_xmp::{synthetic_dng_with, temp_dir};

#[test]
fn full_size_export_of_a_cropped_photo_is_not_upscaled() {
    let mut s = Session::with_demo();
    let id = s.active().unwrap();
    let (pw, ph) = {
        let p = s.catalog.photo(id).unwrap();
        (p.width as usize, p.height as usize)
    };
    s.execute("crop.set", &json!({"rect": [0.25, 0.25, 0.75, 0.75]})).unwrap();
    let e = export_photo(&mut s, id, &ExportOptions::default(), 1).unwrap();
    assert!(e.width.abs_diff(pw / 2) <= 1 && e.height.abs_diff(ph / 2) <= 1, "{}×{} from {pw}×{ph}", e.width, e.height);
    // and the size modes apply to the cropped size
    let o = ExportOptions::from_json(&json!({"percent": 50}));
    let e = export_photo(&mut s, id, &o, 1).unwrap();
    assert!(e.width.abs_diff(pw / 4) <= 1, "{} vs {}", e.width, pw / 4);
    let o = ExportOptions::from_json(&json!({"width": 200, "height": 200, "format": "png"}));
    let e = export_photo(&mut s, id, &o, 1).unwrap();
    assert_eq!(e.width.max(e.height), 200);
    // the print resolution lands in the file
    let i = e.bytes.windows(4).position(|w| w == b"pHYs").expect("pHYs");
    assert_eq!(u32::from_be_bytes(e.bytes[i + 4..i + 8].try_into().unwrap()), 9449, "240 ppi");
}

/// Given a border on an export, when the photo is exported, then the photo keeps its size and the
/// file grows as the border's shape asks: an even margin of half the thickness (of the long edge),
/// or the photo padded out to a ratio (its own, or a fixed one) and that frame grown by the
/// thickness; its edges are the border's colour (8-bit and 16-bit), and an unreadable border is
/// refused before exporting.
#[test]
fn a_border_frames_the_photo_without_changing_its_size() {
    use crate::export::{Border, BorderRatio};
    let mut s = Session::with_demo();
    let id = s.active().unwrap();
    let plain = ExportOptions::from_json(&json!({"longEdge": 400, "format": "jpeg"}));
    let p = export_photo(&mut s, id, &plain, 1).unwrap();
    let (w, h) = (p.width, p.height);
    // even: 10 % of the long edge split over the two sides
    let o =
        ExportOptions::from_json(&json!({"longEdge": 400, "format": "jpeg", "border": {"thickness": 10, "ratio": "even", "color": [200, 10, 30]}}));
    let b = export_photo(&mut s, id, &o, 1).unwrap();
    assert_eq!((b.width, b.height), (w + 40, h + 40));
    assert_eq!(crate::export::output_size(s.catalog.photo(id).unwrap(), &o), crate::export::output_size(s.catalog.photo(id).unwrap(), &plain));
    let rgb = lightcraft_codecs::decode(&b.bytes, Default::default()).unwrap().to_srgb8();
    assert_eq!((rgb.width, rgb.height), (b.width, b.height));
    for at in [0, rgb.width - 1, rgb.width * (rgb.height - 1)] {
        let px = rgb.data[at];
        assert!(px[0].abs_diff(200) <= 6 && px[1].abs_diff(10) <= 6 && px[2].abs_diff(30) <= 6, "{px:?}");
    }
    // the photo's own shape grown by the thickness, and a square frame around a wide photo
    let own = Border { thickness: 10.0, ratio: BorderRatio::Original, color: [255; 3] };
    let (fw, fh) = own.framed(400, 300);
    assert_eq!((fw, fh), (440, 330));
    let square = Border { thickness: 0.0, ratio: BorderRatio::R1x1, color: [255; 3] };
    assert_eq!(square.margins(400, 300), [0, 0, 50, 50]);
    assert_eq!(Border { thickness: 10.0, ..square }.framed(400, 300), (440, 440));
    assert_eq!(Border { ratio: BorderRatio::R9x16, thickness: 0.0, ..square }.framed(900, 900).1 * 9, 900 * 16);
    // 16 bits a channel: the same frame
    let deep = ExportOptions::from_json(&json!({"longEdge": 400, "format": "tiff", "bitDepth": 16, "border": {"thickness": 10, "ratio": "even"}}));
    let t = export_photo(&mut s, id, &deep, 1).unwrap();
    assert_eq!((t.width, t.height), (w + 40, h + 40));
    let corner = lightcraft_codecs::decode(&t.bytes, Default::default()).unwrap().to_srgb8().data[0];
    assert_eq!(corner, [255, 255, 255, 255]);
    // nothing to draw, no border; out of range or unknown keys are refused
    assert!(ExportOptions::from_json(&json!({"border": {"thickness": 0, "ratio": "even"}})).border.is_none());
    for bad in [
        json!({"border": {"thickness": 150}}),
        json!({"border": {"ratio": "7:3"}}),
        json!({"border": {"color": [1, 2]}}),
        json!({"border": {"size": 3}}),
        json!({"border": 3}),
    ] {
        assert!(ExportOptions::from_params(&bad).is_err(), "{bad}");
    }
    assert!(ExportOptions::from_params(&json!({"border": {"thickness": 10, "ratio": "4:5", "color": [0, 0, 0]}})).is_ok());
}

/// Given a watermark with the reference's options, when it's drawn, then its offsets set its
/// margins from the edges (fractions of width and height), a quarter turn stands it on its side,
/// bold and italic change the letters, and the options are checked before exporting.
#[test]
fn watermark_offsets_turns_and_styles() {
    use crate::export::{Anchor, Watermark, draw_watermark};
    use lightcraft_raster::Rgba8;
    // the bounding box of what was drawn on black
    let drawn = |wm: &Watermark| {
        let mut img = Rgba8::new(400, 300);
        for p in img.data.iter_mut() {
            *p = [0, 0, 0, 255];
        }
        draw_watermark(&mut img, wm);
        let mut b = (usize::MAX, usize::MAX, 0usize, 0usize);
        for (i, p) in img.data.iter().enumerate() {
            if p[0] > 128 {
                let (x, y) = (i % 400, i / 400);
                b = (b.0.min(x), b.1.min(y), b.2.max(x), b.3.max(y));
            }
        }
        (b, img)
    };
    let base = Watermark { text: "LightCraft".into(), size: 0.1, opacity: 1.0, shadow: false, anchor: Anchor::BottomLeft, ..Default::default() };
    let at = Watermark { offset_x: Some(0.1), offset_y: Some(0.2), ..base.clone() };
    let ((x0, _, _, y1), _) = drawn(&at);
    assert!(x0.abs_diff(40) <= 4, "10 % of the width from the left: {x0}");
    assert!((300 - y1).abs_diff(60) <= 10, "20 % of the height from the bottom: {y1}");
    // a quarter turn: taller than wide
    let ((x0, y0, x1, y1), _) = drawn(&Watermark { rotation: 90, ..base.clone() });
    assert!(y1 - y0 > 2 * (x1 - x0), "standing on its side: {}×{}", x1 - x0, y1 - y0);
    let ((x0, y0, x1, y1), _) = drawn(&Watermark { rotation: 180, ..base.clone() });
    assert!(x1 - x0 > 2 * (y1 - y0), "upside down stays wide");
    // regular and bold draw differently; italic leans (wider)
    let (_, regular) = drawn(&Watermark { bold: false, ..base.clone() });
    let (_, bold) = drawn(&base);
    assert_ne!(regular.data, bold.data);
    let ((a0, _, a1, _), _) = drawn(&base);
    let ((b0, _, b1, _), _) = drawn(&Watermark { italic: true, ..base.clone() });
    assert!(b1 - b0 > a1 - a0, "italic leans: {} vs {}", b1 - b0, a1 - a0);
    // checks
    for bad in [json!({"watermark": {"rotation": 45}}), json!({"watermark": {"offsetX": 2}}), json!({"watermark": {"bold": "yes"}})] {
        assert!(ExportOptions::from_params(&bad).is_err(), "{bad}");
    }
    assert!(
        ExportOptions::from_params(
            &json!({"watermark": {"text": "a", "bold": false, "italic": true, "offsetX": 0.03, "offsetY": 0.03, "rotation": 270}})
        )
        .is_ok()
    );
}

/// A long edge of 2048 px is 2048 px, also when the crop is a hair off the box's aspect (the
/// render's own fit used to round it to 2047).
#[test]
fn a_long_edge_export_has_exactly_that_long_edge() {
    let mut s = Session::with_demo();
    let id = s.active().unwrap();
    let wide = {
        let p = s.catalog.photo(id).unwrap();
        p.width >= p.height
    };
    // the long side cropped by a hair: its aspect is just off the output's rounded one
    let rect = if wide { [0.0, 0.0, 0.99995, 1.0] } else { [0.0, 0.0, 1.0, 0.99995] };
    s.execute("crop.set", &json!({"rect": rect})).unwrap();
    for edge in [2048, 1080, 4000] {
        let o = ExportOptions::from_json(&json!({"longEdge": edge}));
        let e = export_photo(&mut s, id, &o, 1).unwrap();
        assert_eq!(e.width.max(e.height), edge, "{}×{} for a {edge} px long edge", e.width, e.height);
        // the short side as the size preview says, to the pixel (it follows the decoded source's aspect)
        let (pw, ph) = crate::export::output_size(s.catalog.photo(id).unwrap(), &o);
        assert!(e.width.min(e.height).abs_diff(pw.min(ph)) <= 1, "{}×{} vs {pw}×{ph}", e.width, e.height);
    }
    // a square crop: the long edge doesn't grow a pixel on the other side
    s.execute("crop.set", &json!({"rect": [0.0, 0.0, 0.6666, 0.99995]})).unwrap();
    let o = ExportOptions::from_json(&json!({"longEdge": 2048}));
    let e = export_photo(&mut s, id, &o, 1).unwrap();
    assert_eq!(e.width.max(e.height), 2048, "{}×{}", e.width, e.height);
    s.execute("crop.set", &json!({"rect": rect})).unwrap();
    // the short edge, width and height modes keep their side exact too
    for (opts, side) in [(json!({"shortEdge": 1000}), "short"), (json!({"width": 1500}), "width"), (json!({"height": 1500}), "height")] {
        let o = ExportOptions::from_json(&opts);
        let e = export_photo(&mut s, id, &o, 1).unwrap();
        let got = match side {
            "short" => e.width.min(e.height),
            "width" => e.width,
            _ => e.height,
        };
        assert_eq!(got, if side == "short" { 1000 } else { 1500 }, "{side}: {}×{}", e.width, e.height);
    }
}

#[test]
fn original_export_copies_the_file_and_writes_its_edits_beside_it() {
    let dir = temp_dir("export-orig");
    let src = dir.join("Shot.dng");
    let dng = synthetic_dng_with(None, Default::default());
    std::fs::write(&src, &dng).unwrap();
    let mut s = Session::new().with_fs();
    s.execute("library.import", &json!({"paths": [src.to_string_lossy()]})).unwrap();
    let id = s.catalog.photos().next().unwrap().id;
    s.execute("library.select", &json!({"ids": [id.0]})).unwrap();
    s.execute("develop.set", &json!({"control": "light.exposure", "value": 0.7})).unwrap();

    let o = ExportOptions::from_json(&json!({"format": "original", "naming": "{name}-{seq}"}));
    let e = export_photo(&mut s, id, &o, 3).unwrap();
    assert_eq!(e.file_name, "Shot-003.dng");
    assert_eq!(e.bytes, dng, "the original, byte for byte");
    assert_eq!(e.sidecars.len(), 1);
    let (ext, xmp) = &e.sidecars[0];
    assert_eq!(*ext, "xmp");
    let name = "Shot-003.xmp";
    let copy = dir.join("copy");
    std::fs::create_dir_all(&copy).unwrap();
    std::fs::write(copy.join(&e.file_name), &e.bytes).unwrap();
    std::fs::write(copy.join(name), xmp).unwrap();
    let mut s2 = Session::new().with_fs();
    s2.execute("library.import", &json!({"paths": [copy.join(&e.file_name).to_string_lossy()]})).unwrap();
    assert_eq!(s2.catalog.photos().next().unwrap().develop.light.exposure, 0.7, "edits travel in the sidecar");

    // DNG: re-encoded raw data with the edits embedded
    let o = ExportOptions { format: ExportFormat::Dng, ..Default::default() };
    let e = export_photo(&mut s, id, &o, 1).unwrap();
    assert_eq!(e.file_name, "Shot.dng");
    assert!(e.sidecars.is_empty());
    let back = lightcraft_raw::decode(&e.bytes).expect("our DNG decodes");
    let orig = lightcraft_raw::decode(&dng).unwrap();
    assert_eq!((back.width, back.height), (orig.width, orig.height));
    assert_eq!(back.data, orig.data, "lossless");
    let out = dir.join("out.dng");
    std::fs::write(&out, &e.bytes).unwrap();
    let mut s2 = Session::new().with_fs();
    s2.execute("library.import", &json!({"paths": [out.to_string_lossy()]})).unwrap();
    assert_eq!(s2.catalog.photos().next().unwrap().develop.light.exposure, 0.7, "edits embedded in the DNG are read back");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn original_and_dng_need_a_file() {
    let mut s = Session::with_demo();
    let id = s.active().unwrap();
    for f in ["original", "dng"] {
        let err = export_photo(&mut s, id, &ExportOptions::from_json(&json!({"format": f})), 1).err().unwrap();
        assert!(err.contains("no original file"), "{err}");
    }
}

#[test]
fn dng_export_rejects_non_raw_photos() {
    let dir = temp_dir("export-dng-jpeg");
    let src = dir.join("a.png");
    let img = lightcraft_raster::Rgba8::from_fn(32, 24, |x, y| [(x * 8) as u8, (y * 10) as u8, 90, 255]);
    std::fs::write(&src, crate::export::encode_image(&img, &ExportOptions { format: ExportFormat::Png, ..Default::default() }).unwrap()).unwrap();
    let mut s = Session::new().with_fs();
    s.execute("library.import", &json!({"paths": [src.to_string_lossy()]})).unwrap();
    let id = s.catalog.photos().next().unwrap().id;
    let err = export_photo(&mut s, id, &ExportOptions { format: ExportFormat::Dng, ..Default::default() }, 1).err().unwrap();
    assert!(err.contains("needs a raw photo"), "{err}");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn presets_conflicts_and_subfolders() {
    use crate::export::{Destination, export_batch};
    let mut s = Session::with_demo();
    let ids: Vec<_> = s.visible().iter().copied().take(2).collect();
    // built-ins and user presets; the call's own params override the preset's
    let names: Vec<String> =
        s.execute("export.presets", &json!({})).unwrap().as_array().unwrap().iter().map(|p| p["name"].as_str().unwrap().to_string()).collect();
    assert!(names.contains(&"JPEG (Small)".to_string()) && names.contains(&"Original + Settings".to_string()), "{names:?}");
    let p = s.export_params(&json!({"preset": "jpeg (small)", "quality": 50})).unwrap();
    assert_eq!((p["longEdge"].as_u64(), p["quality"].as_u64(), p.get("preset")), (Some(2048), Some(50), None));
    assert!(s.export_params(&json!({"preset": "nope"})).unwrap_err().contains("unknown export preset"));
    assert!(s.execute("export.savePreset", &json!({"name": "JPEG (Large)", "params": {}})).is_err(), "built-in names are reserved");
    s.execute("export.savePreset", &json!({"name": "Web", "params": {"format": "png", "width": 64, "ids": [1], "dir": "/x"}})).unwrap();
    let web = s.export_params(&json!({"preset": "Web"})).unwrap();
    assert_eq!(web, json!({"format": "png", "width": 64}), "targets are not part of a preset");
    s.execute("export.savePreset", &json!({"name": "web", "params": {"format": "webp"}})).unwrap();
    assert_eq!(s.export_presets.len(), 1, "same name (any case) replaces");
    s.execute("export.deletePreset", &json!({"name": "WEB"})).unwrap();
    assert!(s.export_presets.is_empty());

    // a fake file system: what exists, what was written
    let existing = ["out/Sub/LC01347.png".to_string()];
    let to = Destination { dir: "out".into(), exact: None };
    let batch = |s: &mut Session, ids: &[_], o: &ExportOptions| {
        let mut written: Vec<String> = Vec::new();
        let mut write = |p: &str, _: &[u8]| {
            written.push(p.to_string());
            Ok(())
        };
        let files = export_batch(s, ids, o, &to, &mut write, &|p| existing.contains(&p.to_string())).unwrap();
        (files, written)
    };
    let o = ExportOptions::from_json(&json!({"format": "png", "width": 32, "subfolder": "Sub", "naming": "LC01347"}));
    // both photos get the same name from the template: the first is renamed past the existing
    // file, the second past the first
    let (files, written) = batch(&mut s, &ids, &o);
    assert_eq!(written, ["out/Sub/LC01347-2.png", "out/Sub/LC01347-3.png"]);
    assert_eq!(files[0]["width"], 32);
    let (files, written) = batch(&mut s, &ids, &ExportOptions { conflict: crate::export::Conflict::Skip, ..o.clone() });
    assert_eq!(files.iter().filter(|f| f["skipped"] == "out/Sub/LC01347.png").count(), 2, "{files:?}");
    assert!(written.is_empty());
    let (_, written) = batch(&mut s, &ids[..1], &ExportOptions { conflict: crate::export::Conflict::Overwrite, ..o });
    assert_eq!(written, ["out/Sub/LC01347.png"]);
}

#[test]
fn prepared_exports_run_on_another_thread() {
    fn send<T: Send>(_: &T) {}
    let mut s = Session::with_demo();
    let id = s.active().unwrap();
    let o = ExportOptions::from_json(&json!({"format": "png", "width": 48}));
    let items: Vec<_> = (1..=2).map(|seq| crate::export::prepare_export(&mut s, id, &o, seq).unwrap()).collect();
    send(&items);
    let to = crate::export::Destination::default();
    let seen = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
    let seen2 = seen.clone();
    let files = std::thread::spawn(move || {
        let mut written = Vec::new();
        let mut write = |p: &str, b: &[u8]| {
            written.push((p.to_string(), b.len()));
            Ok(())
        };
        // cancel after the first photo
        crate::export::run_batch(items, &o, &to, &mut write, &|_| false, false, &mut |done, name| {
            seen2.lock().unwrap().push((done, name.to_string()));
            done < 1
        })
        .unwrap()
    })
    .join()
    .unwrap();
    assert_eq!(files.len(), 1, "cancelled before the second: {files:?}");
    assert_eq!(files[0]["width"], 48);
    assert_eq!(seen.lock().unwrap().len(), 2);
}

/// Issue #78: a GPU render whose work never ran (a driver that drops a submission without an
/// error) gave an all-black export. The export must come from the CPU instead — the same
/// image — with the reason recorded.
#[test]
fn export_falls_back_to_the_cpu_when_gpu_work_is_lost() {
    let mut s = Session::with_demo();
    let id = s.active().unwrap();
    s.execute("develop.set", &json!({"control": "light.exposure", "value": 0.4})).unwrap();
    s.execute("develop.set", &json!({"control": "effects.clarity", "value": 20})).unwrap();
    let o = ExportOptions::from_json(&json!({"format": "png"}));
    let mean = |b: &[u8]| {
        let d = lightcraft_codecs::decode(b, Default::default()).unwrap().image;
        d.data.iter().map(|p| (p[0] + p[1] + p[2]) as f64).sum::<f64>() / (3 * d.data.len()) as f64
    };
    let gpu = lightcraft_gpu::available();
    let healthy = mean(&export_photo(&mut s, id, &o, 1).unwrap().bytes);
    lightcraft_gpu::inject_fault(lightcraft_gpu::Fault::DropWork);
    let faulted = export_photo(&mut s, id, &o, 1).unwrap().bytes;
    if gpu {
        let why = lightcraft_gpu::last_fallback().unwrap_or_default();
        assert!(why.contains("incomplete"), "{why}");
        // the GPU is off for the process now: this export renders on the CPU
        assert!(!lightcraft_gpu::available());
    }
    let cpu = export_photo(&mut s, id, &o, 1).unwrap().bytes;
    lightcraft_gpu::reset_failures();
    let px = |b: &[u8]| lightcraft_codecs::decode(b, Default::default()).unwrap().image.data;
    assert!(px(&faulted) == px(&cpu), "the fallback is the CPU render");
    let m = mean(&faulted);
    assert!(m > 0.02, "not black: mean {m}");
    assert!((m - healthy).abs() < 0.01, "GPU {healthy} vs CPU {m}");
}

/// A JPEG original `dir/IMG_1.jpg` imported into a library on disk.
fn library_with_jpeg(tag: &str) -> (Session, lightcraft_catalog::PhotoId, std::path::PathBuf, std::path::PathBuf, Vec<u8>) {
    let dir = temp_dir(tag);
    let src = dir.join("IMG_1.jpg");
    let img = lightcraft_raster::Rgba8::from_fn(48, 32, |x, y| [(x * 5) as u8, (y * 7) as u8, 120, 255]);
    let bytes = crate::export::encode_image(&img, &ExportOptions::default()).unwrap();
    std::fs::write(&src, &bytes).unwrap();
    let mut s = Session::new().with_fs();
    s.execute("library.import", &json!({"paths": [src.to_string_lossy()]})).unwrap();
    let id = s.catalog.photos().next().unwrap().id;
    s.execute("develop.set", &json!({"ids": [id.0], "control": "light.exposure", "value": 1.0})).unwrap();
    (s, id, dir, src, bytes)
}

fn disk_batch(
    s: &mut Session,
    id: lightcraft_catalog::PhotoId,
    o: &ExportOptions,
    to: &crate::export::Destination,
) -> Result<Vec<serde_json::Value>, String> {
    crate::export::export_batch(s, &[id], o, to, &mut crate::export::write_file, &|p| std::path::Path::new(p).exists())
}

/// Issue #93: exporting into the photo's own folder as `{name}` with "Overwrite" replaced the
/// original with the re-encoded render. It is refused now, whatever the policy or exact path.
#[test]
fn export_never_overwrites_an_original() {
    use crate::export::{Conflict, Destination};
    let (mut s, id, dir, src, original) = library_with_jpeg("export-guard");
    let folder = Destination { dir: dir.to_string_lossy().to_string(), exact: None };
    let o = ExportOptions { naming: "{name}".into(), conflict: Conflict::Overwrite, ..Default::default() };
    let err = disk_batch(&mut s, id, &o, &folder).unwrap_err();
    assert!(err.contains("original of IMG_1.jpg"), "{err}");
    assert_eq!(std::fs::read(&src).unwrap(), original, "the original is byte-identical");

    // the exact path of the control channel / MCP, also spelled another way
    std::fs::create_dir_all(dir.join("sub")).unwrap();
    for exact in [src.to_string_lossy().to_string(), dir.join("sub/../IMG_1.jpg").to_string_lossy().to_string()] {
        let to = Destination { dir: String::new(), exact: Some(exact) };
        assert!(disk_batch(&mut s, id, &o, &to).unwrap_err().contains("never writes over an original"));
    }
    // the "Original" format re-writing the file onto itself (and its sidecar)
    let orig = ExportOptions { format: ExportFormat::Original, ..o.clone() };
    assert!(disk_batch(&mut s, id, &orig, &folder).is_err());
    assert_eq!(std::fs::read(&src).unwrap(), original);
    assert!(s.execute("export.checkTarget", &json!({"path": src.to_string_lossy()})).is_err());
    assert!(s.execute("export.checkTarget", &json!({"path": dir.join("free.jpg").to_string_lossy()})).is_ok());

    // Unique moves past it; Overwrite still replaces an ordinary earlier export
    let unique = ExportOptions { conflict: Conflict::Unique, ..o.clone() };
    let files = disk_batch(&mut s, id, &unique, &folder).unwrap();
    assert!(files[0]["path"].as_str().unwrap().ends_with("IMG_1-2.jpg"), "{files:?}");
    let earlier = dir.join("IMG_1-2.jpg");
    std::fs::write(&earlier, b"an earlier export").unwrap();
    let named = ExportOptions { naming: "{name}-2".into(), ..o };
    disk_batch(&mut s, id, &named, &folder).unwrap();
    assert_ne!(std::fs::read(&earlier).unwrap(), b"an earlier export", "an ordinary file is overwritten");
    assert_eq!(std::fs::read(&src).unwrap(), original);
    let _ = std::fs::remove_dir_all(&dir);
}

/// Issue #93: the XMP sidecar of an "Original" export ignored the conflict policy.
#[test]
fn export_sidecars_follow_the_conflict_policy() {
    use crate::export::{Conflict, Destination};
    let (mut s, id, dir, _src, original) = library_with_jpeg("export-sidecar");
    let out = dir.join("out");
    std::fs::create_dir_all(&out).unwrap();
    let theirs = out.join("IMG_1.xmp");
    std::fs::write(&theirs, b"someone else's sidecar").unwrap();
    let to = Destination { dir: out.to_string_lossy().to_string(), exact: None };
    let o = ExportOptions { format: ExportFormat::Original, naming: "{name}".into(), ..Default::default() };

    // Unique: the photo and its sidecar both move to the next free name
    let files = disk_batch(&mut s, id, &ExportOptions { conflict: Conflict::Unique, ..o.clone() }, &to).unwrap();
    assert!(files[0]["path"].as_str().unwrap().ends_with("IMG_1-2.jpg"), "{files:?}");
    assert!(files[0]["sidecars"][0].as_str().unwrap().ends_with("IMG_1-2.xmp"), "{files:?}");
    assert_eq!(std::fs::read(&theirs).unwrap(), b"someone else's sidecar");
    assert_eq!(std::fs::read(out.join("IMG_1-2.jpg")).unwrap(), original);
    // Skip: a taken sidecar name skips the photo
    std::fs::remove_file(out.join("IMG_1-2.jpg")).unwrap();
    std::fs::remove_file(out.join("IMG_1-2.xmp")).unwrap();
    let files = disk_batch(&mut s, id, &ExportOptions { conflict: Conflict::Skip, ..o.clone() }, &to).unwrap();
    assert!(files[0]["skipped"].as_str().is_some(), "{files:?}");
    assert!(!out.join("IMG_1.jpg").exists());
    assert_eq!(std::fs::read(&theirs).unwrap(), b"someone else's sidecar");
    // Overwrite: replaced, as asked
    disk_batch(&mut s, id, &ExportOptions { conflict: Conflict::Overwrite, ..o }, &to).unwrap();
    assert!(std::fs::read_to_string(&theirs).unwrap().contains("xmpmeta"));
    let _ = std::fs::remove_dir_all(&dir);
}

/// Issue #93: the native writer truncated the target first; a failure mid-write (full disk,
/// unplugged drive) now leaves the previous file intact and no partial one.
#[test]
fn a_failed_export_write_keeps_the_previous_file() {
    use crate::export::{Conflict, Destination};
    let (mut s, id, dir, _src, _) = library_with_jpeg("export-midwrite");
    let out = dir.join("out");
    std::fs::create_dir_all(&out).unwrap();
    let prev = out.join("IMG_1.jpg");
    std::fs::write(&prev, b"last week's export").unwrap();
    let to = Destination { dir: out.to_string_lossy().to_string(), exact: None };
    let o = ExportOptions { conflict: Conflict::Overwrite, ..Default::default() };
    {
        let _fault = lightcraft_catalog::safe_file::fail_writes_after(100);
        let err = disk_batch(&mut s, id, &o, &to).unwrap_err();
        assert!(err.contains("injected"), "{err}");
    }
    assert_eq!(std::fs::read(&prev).unwrap(), b"last week's export");
    assert_eq!(std::fs::read_dir(&out).unwrap().count(), 1, "no temp file left behind");
    disk_batch(&mut s, id, &o, &to).unwrap();
    assert_ne!(std::fs::read(&prev).unwrap(), b"last week's export");
    let _ = std::fs::remove_dir_all(&dir);
}

#[cfg(unix)]
#[test]
fn export_protects_the_target_of_an_imported_symlink() {
    use crate::export::{Conflict, Destination};
    let (original_session, _, dir, src, original) = library_with_jpeg("export-symlink-original");
    drop(original_session);
    let alias = dir.join("alias.jpg");
    std::os::unix::fs::symlink(&src, &alias).unwrap();
    let sidecar = dir.join("metadata.xmp");
    std::fs::write(&sidecar, b"<x/>").unwrap();
    std::os::unix::fs::symlink(&sidecar, alias.with_extension("xmp")).unwrap();
    let mut s = Session::new().with_fs();
    s.execute("library.import", &json!({"paths": [alias.to_string_lossy()]})).unwrap();
    let id = s.catalog.photos().next().unwrap().id;
    s.execute("develop.set", &json!({"ids": [id.0], "control": "light.exposure", "value": 1.0})).unwrap();
    let o = ExportOptions { conflict: Conflict::Overwrite, ..Default::default() };
    let to = Destination { dir: String::new(), exact: Some(src.to_string_lossy().to_string()) };
    let result = disk_batch(&mut s, id, &o, &to);
    assert!(result.is_err(), "export replaced the original through its imported alias: {result:?}");
    assert_eq!(std::fs::read(&src).unwrap(), original);
    assert!(s.execute("export.checkTarget", &json!({"path": src.to_string_lossy()})).is_err());
    assert!(s.execute("export.checkTarget", &json!({"path": sidecar.to_string_lossy()})).is_err());
    assert_eq!(std::fs::read(&sidecar).unwrap(), b"<x/>");
    let _ = std::fs::remove_dir_all(&dir);
}

/// Issue #134: exports can always be made again, so they are written atomically but not synced to
/// disk file by file; an edit copy that becomes a library photo still is.
#[test]
fn exports_are_atomic_without_a_sync() {
    use crate::export::{Conflict, Destination};
    use lightcraft_catalog::safe_file::syncs_on_this_thread;
    let (mut s, id, dir, _src, _) = library_with_jpeg("export-nosync");
    let out = dir.join("out");
    let to = Destination { dir: out.to_string_lossy().to_string(), exact: None };
    let o = ExportOptions { conflict: Conflict::Unique, ..Default::default() };
    let before = syncs_on_this_thread();
    for _ in 0..3 {
        disk_batch(&mut s, id, &o, &to).unwrap();
    }
    assert_eq!(syncs_on_this_thread(), before, "no per-file sync for exports");
    assert_eq!(std::fs::read_dir(&out).unwrap().count(), 3, "three exports, no temp files");
    crate::export::write_file_durable(&out.join("edit.tif").to_string_lossy(), b"tiff").unwrap();
    assert!(syncs_on_this_thread() > before, "the durable writer syncs");
    let _ = std::fs::remove_dir_all(&dir);
}
