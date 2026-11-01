//! Auto for one mask (LumaWorks v0.6.32-beta.6).
//!
//! Smart Auto looks at the whole photograph. Inside a mask, the question is narrower:
//! is the selected part itself well exposed, with real blacks and whites, without
//! clipped highlights, and with enough colour — and, for Subject, Background and Sky,
//! how should it sit against the rest of the picture?
//!
//! Everything is measured on small renders (192 px) of the photograph exactly as it
//! will look, weighted by the mask's own evaluated alpha, so a soft edge counts as
//! partly selected. Each local slider is moved only as far as needed to reach its
//! target, and every move that brightens is checked against new clipping inside the
//! mask. Only the mask's Light controls and Saturation/Dehaze are set; any other
//! local slider the person already chose is kept.

use lightcraft_develop::{DevelopSettings, LocalAdjustments, Mask, MaskOp, MaskShape};
use lightcraft_raster::Rgb32f;
use serde::Serialize;

use crate::{MaskView, Overlay, RenderRequest, SourceInfo};

const EDGE: usize = 192;
/// Newly clipped (any channel at 254+) share of the mask a brightening move may add.
const NEW_WHITE_CLIP_LIMIT: f64 = 0.015;
const NEW_BLACK_CLIP_LIMIT: f64 = 0.020;

/// What a mask selects, as far as its Auto is concerned.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum RegionKind {
    Subject,
    Background,
    Sky,
    /// Brushes, gradients, ranges: treated like a subject, at reduced strength.
    Other,
}

/// Display-referred measurements of a region (sRGB 0..255 luma, chroma 0..1).
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize)]
pub struct RegionStats {
    pub median: f64,
    pub p01: f64,
    pub p10: f64,
    pub p99: f64,
    /// Mean (max − min) / 255.
    pub chroma: f64,
    /// Mean blue − red, in levels (a blue sky is positive).
    pub blueness: f64,
    pub white_clip: f64,
    pub black_clip: f64,
}

#[derive(Clone, Debug, Serialize)]
pub struct MaskAutoReport {
    pub kind: RegionKind,
    /// Mean alpha of the mask over the photograph.
    pub coverage: f64,
    /// The mask's complete local adjustments after Auto.
    pub adjust: LocalAdjustments,
    pub before: RegionStats,
    pub after: RegionStats,
    /// Median of the unselected part (what the mask is compared with), if any.
    pub rest_median: Option<f64>,
    pub notes: Vec<&'static str>,
}

/// The targets Auto aims the region at; pure, so its policy is testable.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Plan {
    /// Median to reach, or None to leave exposure alone.
    pub median: Option<f64>,
    pub exposure_range: (f64, f64),
    pub recover_highlights: bool,
    pub lift_shadows: bool,
    pub deepen_blacks: bool,
    pub brighten_whites: bool,
    pub saturation: f64,
    pub dehaze: f64,
}

/// What `m` selects: its first added, visible component decides (Object and People
/// selections count as a subject); an inverted mask selects the opposite.
pub fn region_kind(m: &Mask) -> RegionKind {
    let first = m.components.iter().find(|c| matches!(c.op, MaskOp::Add) && !c.hidden);
    let kind = match first.map(|c| &c.shape) {
        Some(MaskShape::Sky) => RegionKind::Sky,
        Some(MaskShape::Background) => RegionKind::Background,
        Some(MaskShape::Subject | MaskShape::People { .. }) => RegionKind::Subject,
        // The Android app stores its Select Subject / Sky / Background results as
        // Object segmentations named after the selection (the person may rename them).
        Some(MaskShape::Object { .. }) => {
            let named = |n: &str| n.to_lowercase();
            let name = first.and_then(|c| c.name.as_deref()).map(named).unwrap_or_default() + " " + &named(&m.name);
            if name.contains("sky") {
                RegionKind::Sky
            } else if name.contains("background") {
                RegionKind::Background
            } else {
                RegionKind::Subject
            }
        }
        Some(MaskShape::Prompt { text, .. }) if text.to_lowercase().contains("sky") => RegionKind::Sky,
        _ => RegionKind::Other,
    };
    if m.invert ^ first.is_some_and(|c| c.invert) {
        match kind {
            RegionKind::Subject => RegionKind::Background,
            RegionKind::Background => RegionKind::Subject,
            // everything but the sky is not a sky
            _ => RegionKind::Other,
        }
    } else {
        kind
    }
}

/// Auto's targets for a region of `kind` measured as `r`, with the rest of the
/// photograph measured as `rest`.
pub fn plan(kind: RegionKind, r: &RegionStats, rest: Option<&RegionStats>) -> Plan {
    let colourful = r.chroma >= 0.06;
    let saturation_up = |goal: f64, cap: f64| if colourful && r.chroma < goal - 0.04 { ((goal - r.chroma) * 120.0).min(cap).round() } else { 0.0 };
    match kind {
        RegionKind::Subject | RegionKind::Other => {
            // A subject a little brighter than its surroundings, within a normal range.
            let rest_median = rest.map_or(r.median, |x| x.median);
            let mut goal = (rest_median + 10.0).clamp(105.0, 135.0);
            if r.median > goal {
                // Already brighter than that: a bright subject is welcome, and only
                // an overexposed one is brought down.
                goal = if r.median > 175.0 || r.white_clip > 0.03 { 160.0f64.max(goal) } else { r.median };
            }
            if kind == RegionKind::Other {
                goal = r.median + 0.6 * (goal - r.median);
            }
            // A spot-lit dark scene keeps its mood: at most +1 EV.
            let low_key = r.median < 40.0 && r.p99 > 200.0;
            Plan {
                median: ((goal - r.median).abs() > 6.0).then_some(goal),
                exposure_range: (-1.5, if low_key { 1.0 } else { 1.5 }),
                recover_highlights: true,
                lift_shadows: true,
                deepen_blacks: true,
                brighten_whites: true,
                saturation: saturation_up(0.28, 15.0),
                dehaze: 0.0,
            }
        }
        RegionKind::Background => {
            // Never brighter than the subject: darken to sit below it, never lift, and
            // by a modest amount (a darkened background, not a grey one).
            let goal = rest.map(|s| (s.median - 12.0).max(r.median - 35.0)).filter(|g| r.median > *g + 3.0);
            let mut saturation = 0.0;
            if let Some(s) = rest
                && r.chroma > s.chroma + 0.04
            {
                saturation = -((r.chroma - s.chroma) * 150.0).min(20.0).round();
            }
            Plan {
                median: goal.map(|g| g.max(40.0)),
                exposure_range: (-0.6, 0.0),
                recover_highlights: true,
                lift_shadows: false,
                deepen_blacks: true,
                brighten_whites: false,
                saturation,
                dehaze: 0.0,
            }
        }
        RegionKind::Sky => {
            // A sky stays bright; only a washed-out one is brought down, and a pale
            // blue one gets its colour back. Sunset and night skies are left alone.
            let blue = r.blueness > 8.0;
            Plan {
                median: (r.median > 205.0).then_some(188.0),
                exposure_range: (-0.8, 0.0),
                recover_highlights: true,
                lift_shadows: false,
                deepen_blacks: false,
                brighten_whites: false,
                saturation: if blue && r.chroma < 0.22 { ((0.26 - r.chroma) * 150.0).min(25.0).round() } else { 0.0 },
                dehaze: if r.p99 - r.p01 < 60.0 && r.median > 120.0 { 12.0 } else { 0.0 },
            }
        }
    }
}

fn luma(p: &[u8; 4]) -> f64 {
    0.2126 * p[0] as f64 + 0.7152 * p[1] as f64 + 0.0722 * p[2] as f64
}

fn quantile(v: &[(f64, f64)], total: f64, q: f64) -> f64 {
    let goal = total * q;
    let mut acc = 0.0;
    for (value, w) in v {
        acc += w;
        if acc >= goal {
            return *value;
        }
    }
    v.last().map_or(0.0, |x| x.0)
}

/// Stats of `img` weighted by `w` (0..1 per pixel).
pub fn region_stats(img: &[[u8; 4]], w: &[f64]) -> RegionStats {
    let mut v: Vec<(f64, f64)> = Vec::with_capacity(img.len());
    let (mut total, mut chroma, mut blue, mut wc, mut bc) = (0.0, 0.0, 0.0, 0.0, 0.0);
    for (p, &a) in img.iter().zip(w) {
        if a < 0.02 {
            continue;
        }
        v.push((luma(p), a));
        total += a;
        let (hi, lo) = (p[0].max(p[1]).max(p[2]) as f64, p[0].min(p[1]).min(p[2]) as f64);
        chroma += a * (hi - lo) / 255.0;
        blue += a * (p[2] as f64 - p[0] as f64);
        if hi >= 254.0 {
            wc += a;
        }
        if lo <= 1.0 && hi <= 3.0 {
            bc += a;
        }
    }
    if total <= 0.0 {
        return RegionStats::default();
    }
    v.sort_by(|a, b| a.0.total_cmp(&b.0));
    RegionStats {
        median: quantile(&v, total, 0.50),
        p01: quantile(&v, total, 0.01),
        p10: quantile(&v, total, 0.10),
        p99: quantile(&v, total, 0.99),
        chroma: chroma / total,
        blueness: blue / total,
        white_clip: wc / total,
        black_clip: bc / total,
    }
}

/// Share of the mask (weighted) newly clipped white (`white`) or black in `after`.
fn newly_clipped(before: &[[u8; 4]], after: &[[u8; 4]], w: &[f64], white: bool) -> f64 {
    let (mut total, mut new) = (0.0, 0.0);
    for ((b, a), &k) in before.iter().zip(after).zip(w) {
        if k < 0.02 {
            continue;
        }
        total += k;
        let clipped = |p: &[u8; 4]| if white { p[0].max(p[1]).max(p[2]) >= 254 } else { p[0].max(p[1]).max(p[2]) <= 3 };
        if clipped(a) && !clipped(b) {
            new += k;
        }
    }
    if total > 0.0 { new / total } else { 0.0 }
}

fn srgb_to_linear(v: f64) -> f64 {
    let x = (v / 255.0).clamp(1e-4, 1.0);
    if x <= 0.04045 { x / 12.92 } else { ((x + 0.055) / 1.055).powf(2.4) }
}

/// Smallest k in 0..=1 (to 1/32) for which `reached(k)`, or 1 when even 1 does not.
fn smallest_reaching(mut reached: impl FnMut(f64) -> bool) -> f64 {
    if !reached(1.0) {
        return 1.0;
    }
    let (mut lo, mut hi) = (0.0f64, 1.0f64);
    for _ in 0..5 {
        let mid = (lo + hi) * 0.5;
        if reached(mid) { hi = mid } else { lo = mid }
    }
    hi
}

/// Largest k in 0..=1 (to 1/32) for which `ok(k)` (0 when none).
fn largest_passing(mut ok: impl FnMut(f64) -> bool) -> f64 {
    if ok(1.0) {
        return 1.0;
    }
    let (mut lo, mut hi) = (0.0f64, 1.0f64);
    for _ in 0..5 {
        let mid = (lo + hi) * 0.5;
        if ok(mid) { lo = mid } else { hi = mid }
    }
    lo
}

/// The controls Auto sets, zeroed on top of the person's other local choices.
fn cleared(a: LocalAdjustments) -> LocalAdjustments {
    LocalAdjustments {
        exposure: 0.0,
        contrast: 0.0,
        highlights: 0.0,
        shadows: 0.0,
        whites: 0.0,
        blacks: 0.0,
        saturation: 0.0,
        dehaze: 0.0,
        ..a
    }
}

/// Auto for mask `id` of `s`. Errors are short sentences for the person.
pub fn mask_auto(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings, id: u32) -> Result<MaskAutoReport, &'static str> {
    let i = s.masks.iter().position(|m| m.id == id).ok_or("That mask no longer exists.")?;
    if s.masks[i].components.is_empty() {
        return Err("This mask has no selection yet.");
    }
    let kind = region_kind(&s.masks[i]);
    let proxy = lightcraft_raster::resample::fit(src, EDGE, EDGE, lightcraft_raster::resample::Filter::Box);
    let req = RenderRequest::fit(EDGE, EDGE);
    let mut base = s.clone();
    let start = cleared(base.masks[i].adjust);
    base.masks[i].adjust = start;
    base.masks[i].visible = true;
    let render = |a: &LocalAdjustments| {
        let mut t = base.clone();
        t.masks[i].adjust = *a;
        crate::render(&proxy, info, &t, &req).image.data
    };
    let mut areq = req;
    areq.overlay = Overlay::Mask { id: id.min(u16::MAX as u32) as u16, view: MaskView::WhiteOnBlack, color: [255, 255, 255], opacity: 100 };
    let alpha: Vec<f64> = crate::render(&proxy, info, &base, &areq).image.data.iter().map(|p| p[0] as f64 / 255.0).collect();
    let before = render(&start);
    if before.is_empty() || alpha.len() != before.len() {
        return Err("The photograph could not be measured.");
    }
    let coverage = alpha.iter().sum::<f64>() / alpha.len() as f64;
    if coverage < 0.005 {
        return Err("This mask selects almost nothing, so there is nothing to measure.");
    }
    let rest_w: Vec<f64> = alpha.iter().map(|a| 1.0 - a).collect();
    let region = region_stats(&before, &alpha);
    let rest = (coverage < 0.98).then(|| region_stats(&before, &rest_w));
    let p = plan(kind, &region, rest.as_ref());
    let mut notes = Vec::new();
    let mut a = start;
    let measure = |a: &LocalAdjustments| region_stats(&render(a), &alpha);
    let new_whites = |a: &LocalAdjustments| newly_clipped(&before, &render(a), &alpha, true);
    let new_blacks = |a: &LocalAdjustments| newly_clipped(&before, &render(a), &alpha, false);

    // 1. Exposure toward the planned median (two corrections at most).
    if let Some(goal) = p.median {
        let (lo, hi) = p.exposure_range;
        let mut e = (srgb_to_linear(goal) / srgb_to_linear(region.median)).log2().clamp(lo, hi);
        for _ in 0..2 {
            let m = measure(&LocalAdjustments { exposure: e, ..a }).median;
            if (m - goal).abs() <= 3.0 {
                break;
            }
            e = (e + 0.8 * (srgb_to_linear(goal) / srgb_to_linear(m)).log2()).clamp(lo, hi);
        }
        e = (e * 20.0).round() / 20.0;
        if e > 0.0 {
            let ok = |k: f64| new_whites(&LocalAdjustments { exposure: ((e * k) * 20.0).floor() / 20.0, ..a }) <= NEW_WHITE_CLIP_LIMIT;
            let k = largest_passing(ok);
            if k < 1.0 {
                notes.push("brightening held back to avoid clipping");
            }
            e = ((e * k) * 20.0).floor() / 20.0;
        }
        if e != 0.0 {
            a.exposure = e + 0.0;
            notes.push(if e > 0.0 { "brightened" } else { "darkened" });
        }
    }
    let now = measure(&a);
    // 2. Highlights the region clips.
    if p.recover_highlights && (now.white_clip > 0.02 || now.p99 >= 252.0) {
        let base_a = a;
        let pull = move |k: f64| LocalAdjustments { highlights: (-70.0 * k).round() + 0.0, ..base_a };
        let k = smallest_reaching(|k| {
            let st = measure(&pull(k));
            st.white_clip <= 0.01 && st.p99 <= 246.0
        });
        a = pull(k);
        if a.highlights != 0.0 {
            notes.push("recovered highlights");
        }
    }
    // 3. Dark shadows inside a subject.
    let now = measure(&a);
    if p.lift_shadows && now.p10 < 35.0 && now.median > 60.0 {
        let base_a = a;
        let lift = move |k: f64| LocalAdjustments { shadows: (40.0 * k).round() + 0.0, ..base_a };
        let k = smallest_reaching(|k| measure(&lift(k)).p10 >= 42.0);
        if measure(&lift(k)).p10 - now.p10 >= 4.0 {
            a = lift(k);
        }
        if a.shadows != 0.0 {
            notes.push("opened shadows");
        }
    }
    // 4. A lifted black point (never on a flat, misty region with nothing dark).
    let now = measure(&a);
    if p.deepen_blacks && now.p01 > 24.0 && now.p01 < 80.0 {
        let base_a = a;
        let deepen = move |k: f64| LocalAdjustments { blacks: (-40.0 * k).round() + 0.0, ..base_a };
        let k = smallest_reaching(|k| measure(&deepen(k)).p01 <= 14.0);
        let k = k * largest_passing(|x| new_blacks(&deepen(k * x)) <= NEW_BLACK_CLIP_LIMIT);
        // Kept only when it really deepens the black point (the local Blacks slider
        // barely moves some photographs' end points; a maxed slider doing nothing is noise).
        if now.p01 - measure(&deepen(k)).p01 >= 4.0 {
            a = deepen(k);
        }
        if a.blacks != 0.0 {
            notes.push("deepened blacks");
        }
    }
    // 5. A dull white point on a region with real highlights.
    let now = measure(&a);
    if p.brighten_whites && now.p99 < 215.0 && now.p99 >= 140.0 {
        let base_a = a;
        let brighten = move |k: f64| LocalAdjustments { whites: (40.0 * k).round() + 0.0, ..base_a };
        let k = smallest_reaching(|k| measure(&brighten(k)).p99 >= 228.0);
        let k = k * largest_passing(|x| new_whites(&brighten(k * x)) <= NEW_WHITE_CLIP_LIMIT);
        if measure(&brighten(k)).p99 - now.p99 >= 4.0 {
            a = brighten(k);
        }
        if a.whites != 0.0 {
            notes.push("brightened whites");
        }
    }
    // 6. Colour.
    if p.saturation != 0.0 {
        a.saturation = p.saturation + 0.0;
        notes.push(if p.saturation > 0.0 { "added colour" } else { "calmed colour" });
    }
    if p.dehaze != 0.0 {
        a.dehaze = p.dehaze;
        notes.push("cleared haze");
    }
    if notes.is_empty() {
        notes.push("already well balanced");
    }
    let after = measure(&a);
    Ok(MaskAutoReport { kind, coverage, adjust: a, before: region, after, rest_median: rest.map(|r| r.median), notes })
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_develop::{Mask, MaskComponent};
    use lightcraft_geom::Point;

    fn stats(median: f64, p01: f64, p99: f64, chroma: f64, blueness: f64) -> RegionStats {
        RegionStats { median, p01, p10: median * 0.5, p99, chroma, blueness, white_clip: 0.0, black_clip: 0.0 }
    }

    /// A photo whose left part (x < 0.45) is dark and right part mid-grey, with one
    /// linear mask over the left part.
    fn scene(left: f32, right: f32) -> (Rgb32f, DevelopSettings) {
        let img = Rgb32f::from_fn(240, 120, |x, y| {
            let t = ((x * 7 + y * 13) % 11) as f32 / 10.0;
            let v = (if x < 108 { left } else { right }) * (0.75 + 0.5 * t);
            [v, v * 0.95, v * 0.9]
        });
        let shape = MaskShape::Linear { start: Point::new(0.45, 0.5), end: Point::new(0.46, 0.5) };
        let mut s = DevelopSettings::default();
        s.masks.push(Mask {
            id: 7,
            components: vec![MaskComponent { hidden: false, name: None, op: MaskOp::Add, invert: false, shape }],
            ..Default::default()
        });
        (img, s)
    }

    #[test]
    fn a_dark_selected_region_is_brought_up_toward_its_surroundings() {
        let (img, s) = scene(0.02, 0.18);
        let r = mask_auto(&img, &SourceInfo::default(), &s, 7).expect("auto");
        assert_eq!(r.kind, RegionKind::Other);
        assert!(r.adjust.exposure >= 0.5, "{:?}", r.adjust);
        assert!(r.after.median > r.before.median + 15.0, "{:?} → {:?}", r.before, r.after);
        assert!(r.coverage > 0.3 && r.coverage < 0.6);
    }

    #[test]
    fn a_well_exposed_region_is_left_almost_alone() {
        let (img, s) = scene(0.18, 0.18);
        let r = mask_auto(&img, &SourceInfo::default(), &s, 7).expect("auto");
        assert!(r.adjust.exposure.abs() <= 0.25, "{:?}", r.adjust);
    }

    #[test]
    fn auto_keeps_the_persons_other_local_choices() {
        let (img, mut s) = scene(0.02, 0.18);
        s.masks[0].adjust.texture = 30.0;
        s.masks[0].adjust.exposure = -2.0;
        let r = mask_auto(&img, &SourceInfo::default(), &s, 7).expect("auto");
        assert_eq!(r.adjust.texture, 30.0);
        assert!(r.adjust.exposure > 0.0, "measured from zero, not from the old −2 EV");
    }

    #[test]
    fn a_mask_selecting_nothing_is_refused() {
        let (img, mut s) = scene(0.02, 0.18);
        s.masks[0].components[0].shape = MaskShape::Linear { start: Point::new(-0.5, 0.5), end: Point::new(-0.49, 0.5) };
        assert!(mask_auto(&img, &SourceInfo::default(), &s, 7).is_err());
        assert!(mask_auto(&img, &SourceInfo::default(), &s, 99).is_err());
    }

    #[test]
    fn a_sky_is_never_brightened_and_a_pale_blue_one_gets_colour() {
        let pale = stats(170.0, 120.0, 240.0, 0.12, 30.0);
        let p = plan(RegionKind::Sky, &pale, Some(&stats(90.0, 5.0, 230.0, 0.2, 0.0)));
        assert_eq!(p.median, None);
        assert!(p.exposure_range.1 <= 0.0);
        assert!(p.saturation > 0.0);
        let washed = stats(230.0, 200.0, 255.0, 0.05, 12.0);
        assert_eq!(plan(RegionKind::Sky, &washed, None).median, Some(188.0));
        let sunset = stats(150.0, 60.0, 250.0, 0.4, -40.0);
        assert_eq!(plan(RegionKind::Sky, &sunset, None).saturation, 0.0);
    }

    #[test]
    fn a_background_is_only_ever_darkened_below_the_subject() {
        let subject = stats(110.0, 10.0, 240.0, 0.2, 0.0);
        let bright_bg = stats(150.0, 20.0, 250.0, 0.35, 0.0);
        let p = plan(RegionKind::Background, &bright_bg, Some(&subject));
        assert_eq!(p.median, Some(115.0), "at most 35 levels darker");
        let a_little_brighter = stats(120.0, 20.0, 250.0, 0.2, 0.0);
        assert_eq!(plan(RegionKind::Background, &a_little_brighter, Some(&subject)).median, Some(98.0));
        assert!(p.exposure_range.1 <= 0.0);
        assert!(p.saturation < 0.0, "a louder background is calmed");
        let dark_bg = stats(60.0, 5.0, 200.0, 0.1, 0.0);
        assert_eq!(plan(RegionKind::Background, &dark_bg, Some(&subject)).median, None);
    }

    #[test]
    fn a_subject_aims_a_little_above_its_surroundings() {
        let dark_subject = stats(70.0, 8.0, 200.0, 0.2, 0.0);
        let p = plan(RegionKind::Subject, &dark_subject, Some(&stats(120.0, 5.0, 240.0, 0.2, 0.0)));
        assert_eq!(p.median, Some(130.0));
        let bright = stats(150.0, 8.0, 245.0, 0.3, 0.0);
        assert_eq!(plan(RegionKind::Subject, &bright, Some(&stats(70.0, 5.0, 240.0, 0.2, 0.0))).median, None,
            "a subject brighter than its surroundings is welcome");
        let blown = stats(200.0, 40.0, 255.0, 0.2, 0.0);
        assert_eq!(plan(RegionKind::Subject, &blown, Some(&stats(70.0, 5.0, 240.0, 0.2, 0.0))).median, Some(160.0));
        let fine = stats(118.0, 8.0, 240.0, 0.3, 0.0);
        assert_eq!(plan(RegionKind::Subject, &fine, Some(&stats(110.0, 5.0, 240.0, 0.3, 0.0))).median, None);
    }

    #[test]
    fn an_inverted_subject_is_treated_as_background() {
        let mut m = Mask {
            components: vec![MaskComponent { hidden: false, name: None, op: MaskOp::Add, invert: false, shape: MaskShape::Subject }],
            ..Default::default()
        };
        assert_eq!(region_kind(&m), RegionKind::Subject);
        m.invert = true;
        assert_eq!(region_kind(&m), RegionKind::Background);
        m.components[0].shape = MaskShape::Sky;
        assert_eq!(region_kind(&m), RegionKind::Other, "an inverted sky is the land");
        m.invert = false;
        assert_eq!(region_kind(&m), RegionKind::Sky);
        // The Android app's semantic selections: Object segmentations named by kind.
        m.invert = false;
        m.components[0].shape = MaskShape::Object { hint: vec![], exclude: vec![], seg: None, detail: vec![], edge: 0.0 };
        m.name = "Select Sky".into();
        assert_eq!(region_kind(&m), RegionKind::Sky);
        m.name = "Select Background".into();
        assert_eq!(region_kind(&m), RegionKind::Background);
        m.name = "Select Subject".into();
        assert_eq!(region_kind(&m), RegionKind::Subject);
    }
}
