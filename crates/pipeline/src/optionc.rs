//! Option C pilot model: genuine offline learned inference, diagnostic only.
//! Trained on controlled synthetic defects of finished photographs.
//! Does NOT predict correction magnitude or professional polish, and cannot
//! apply, veto, or alter Smart Auto settings. All photos remain on device.

use lightcraft_develop::DevelopSettings;
use lightcraft_raster::{Rgb32f, Rgba8};
use serde::{Deserialize, Serialize};
use std::sync::OnceLock;

use crate::{RenderRequest, SourceInfo};

const SCHEMA_FEATURES: [&str; 22] = [
    "p05", "p25", "p50", "p75", "p95", "p995", "subjectEV",
    "shadowFrac", "brightFrac", "recoverableFrac", "structureFrac",
    "meanChroma", "richFrac", "softFrac", "colourFrac",
    "channelNearClip", "upperEV", "middleEV", "lowerEV",
    "blackVoidFrac", "whitesFrac", "stdY",
];
const MODEL_JSON: &str = include_str!("../models/optionc_pilot_v1.json");
static FOREST: OnceLock<Option<Forest>> = OnceLock::new();

#[derive(Deserialize)]
struct Forest {
    schema: u32,
    method: String,
    classes: Vec<String>,
    features: Vec<String>,
    trees: Vec<Tree>,
}

#[derive(Deserialize)]
struct Tree {
    left: Vec<i32>,
    right: Vec<i32>,
    feature: Vec<i32>,
    threshold: Vec<f64>,
    values: Vec<[f64; 5]>,
}

impl Forest {
    fn validated(self) -> Option<Self> {
        if self.schema != 1 || self.method != "random_forest"
            || self.classes != ["preserve", "under", "over", "low_chroma", "low_contrast"]
            || self.features.iter().map(String::as_str).ne(SCHEMA_FEATURES)
            || self.trees.len() != 64 { return None; }
        for tree in &self.trees {
            let n=tree.left.len();
            if n == 0 || tree.right.len()!=n || tree.feature.len()!=n
                || tree.threshold.len()!=n || tree.values.len()!=n {return None;}
            for i in 0..n {
                let f=tree.feature[i];
                if f < 0 {
                    if tree.values[i].iter().any(|p| !p.is_finite() || *p<0.0) {
                        return None;
                    }
                } else if (f as usize)>=SCHEMA_FEATURES.len()
                    || !tree.threshold[i].is_finite()
                    || tree.left[i]<0 || tree.right[i]<0
                    || tree.left[i] as usize>=n || tree.right[i] as usize>=n {
                    return None;
                }
            }
        }
        Some(self)
    }

    fn infer(&self, features: &[f64; 22]) -> Option<[f64;5]> {
        let mut totals=[0.0f64;5];
        for tree in &self.trees {
            let mut i=0usize;
            let mut found=false;
            for _ in 0..tree.feature.len() {
                let f=tree.feature[i];
                if f<0 {
                    for (k,p) in tree.values[i].iter().enumerate() {totals[k]+=*p;}
                    found=true;
                    break;
                }
                let child=if features[f as usize]<=tree.threshold[i] {
                    tree.left[i]
                } else {tree.right[i]};
                if child<0 || child as usize>=tree.feature.len(){return None;}
                i=child as usize;
            }
            if !found {return None;}
        }
        for p in &mut totals {*p/=self.trees.len() as f64;}
        Some(totals)
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all="camelCase")]
pub struct PilotDiagnostic {
    pub status: &'static str,
    pub predicted_class: &'static str,
    pub confidence: f64,
    pub class_probabilities: [f64;5],
    pub class_labels: [&'static str;5],
    pub confident_defect: bool,
    pub limitation: &'static str,
}

fn unavailable() -> PilotDiagnostic {
    PilotDiagnostic {
        status: "unavailable",
        predicted_class: "unknown",
        confidence: 0.0,
        class_probabilities: [0.0;5],
        class_labels: ["preserve","under","over","low_chroma","low_contrast"],
        confident_defect: false,
        limitation: "Pilot model could not be loaded or image proxy was unavailable; no edits were made.",
    }
}

/// Read-only 64-tree pilot prediction at 160px. Never overrides adjustments.
pub fn diagnose(src:&Rgb32f, info:&SourceInfo, current:&DevelopSettings) -> PilotDiagnostic {
    let Some(model)=FOREST.get_or_init(|| {
        serde_json::from_str::<Forest>(MODEL_JSON).ok().and_then(Forest::validated)
    }).as_ref() else {return unavailable();};
    if src.width==0 || src.height==0 {return unavailable();}
    let thumbnail=lightcraft_raster::resample::fit(
        src,160,160,lightcraft_raster::resample::Filter::Box);
    // Pilot was trained on rendered 8-bit sRGB photographs. Use the app's
    // actual renderer to build its feature proxy instead of interpreting
    // linear Rec.2020 channels as if they were encoded sRGB.
    let base=DevelopSettings {wb:current.wb, ..DevelopSettings::default()};
    let encoded=crate::render(&thumbnail,info,&base,&RenderRequest::fit(160,160)).image;
    let Some(features)=extract_features(&encoded) else{return unavailable();};
    let Some(scores)=model.infer(&features) else{return unavailable();};
    let (best,&confidence)=scores.iter().enumerate()
        .max_by(|a,b|a.1.total_cmp(b.1)).unwrap_or((0,&scores[0]));
    const LABELS: [&str;5]=["preserve","under","over","low_chroma","low_contrast"];
    PilotDiagnostic {
        status: "diagnostic_only",
        predicted_class: LABELS[best],
        confidence,
        class_probabilities: scores,
        class_labels: LABELS,
        confident_defect: best!=0 && confidence>=0.75,
        limitation: "Preliminary synthetic-defect classifier; no validated professional-polish output. It never changes Smart Auto edits.",
    }
}

fn quantile(sorted:&[f64], fraction:f64)->f64 {
    if sorted.is_empty() {return 0.0;}
    let position=(sorted.len()-1) as f64*fraction;
    let index=position.floor() as usize;
    let mix=position-index as f64;
    let next=sorted[(index+1).min(sorted.len()-1)];
    sorted[index]*(1.0-mix)+next*mix
}
fn median(values:&mut [f64])->f64 {
    values.sort_by(f64::total_cmp);
    quantile(values,0.5)
}
fn lin(v:u8)->f64 {
    let v=v as f64/255.0;
    if v<=0.04045 {v/12.92} else {((v+0.055)/1.055).powf(2.4)}
}
fn ev(y:f64)->f64 {(y.max(1e-6)/0.18).log2()}

fn extract_features(image:&Rgba8)->Option<[f64;22]> {
    let (w,h)=(image.width,image.height);
    if w<4 || h<4 || image.data.len()!=w*h{return None;}
    let n=(w*h) as f64;
    let mut y=Vec::with_capacity(w*h);
    let mut evs=Vec::with_capacity(w*h);
    let mut spreads=Vec::new();
    let mut clipped=0usize;
    let mut dark=0usize;
    let mut bright=0usize;
    let mut recoverable=0usize;
    let mut black_void=0usize;
    let mut whites=0usize;
    let mut sum=0.0f64;
    let mut sum_sq=0.0f64;
    for p in &image.data {
        let c=[lin(p[0]),lin(p[1]),lin(p[2])];
        let lum=0.2126*c[0]+0.7152*c[1]+0.0722*c[2];
        y.push(lum);
        evs.push(ev(lum));
        dark+=(lum<0.028) as usize;
        bright+=(lum>0.70) as usize;
        recoverable+=(lum>0.035 && lum<0.28) as usize;
        black_void+=(lum<0.004) as usize;
        whites+=(lum>0.95) as usize;
        sum+=lum;sum_sq+=lum*lum;
        for channel in c {clipped+=(channel>=0.985) as usize;}
        if lum>0.025 && lum<0.8 {
            let max=c[0].max(c[1]).max(c[2]);
            let min=c[0].min(c[1]).min(c[2]);
            spreads.push((max-min)/max.max(1e-6));
        }
    }
    let mut ev_sorted=evs.clone();
    ev_sorted.sort_by(f64::total_cmp);
    let qs=[0.05,0.25,0.50,0.75,0.95,0.995]
        .map(|q|quantile(&ev_sorted,q));
    let mut structured=Vec::new();
    for row in 1..h {
        for col in 1..w {
            let i=row*w+col;
            let value=y[i];
            if value<=0.028 || value>=0.8 {continue;}
            let left=y[i-1];
            let top=y[i-w];
            let contrast=((value-left).abs()+(value-top).abs())
                /(0.03+value+left+top);
            if contrast>0.07 {structured.push(evs[i]);}
        }
    }
    let textured_frac=structured.len() as f64/((h-1)*(w-1)) as f64;
    let subject=if structured.len()>100 {median(&mut structured)} else{qs[2]};
    if spreads.is_empty(){spreads.push(0.0);}
    let chroma_len=spreads.len() as f64;
    let mean_chroma=spreads.iter().sum::<f64>()/chroma_len;
    let rich=spreads.iter().filter(|v|**v>0.66).count() as f64/chroma_len;
    let soft=spreads.iter().filter(|v|**v>0.075 && **v<0.45).count() as f64/chroma_len;
    let coloured=spreads.iter().filter(|v|**v>0.065).count() as f64/chroma_len;
    let mut thirds=[0f64;3];
    let mut row=0usize;
    for (zone,result) in thirds.iter_mut().enumerate() {
        let count=h/3+usize::from(zone<h%3);
        let mut values=y[row*w..(row+count)*w].to_vec();
        *result=ev(median(&mut values));
        row+=count;
    }
    let mean=sum/n;
    let std=(sum_sq/n-mean*mean).max(0.0).sqrt();
    let result=[
        qs[0],qs[1],qs[2],qs[3],qs[4],qs[5],subject,
        dark as f64/n, bright as f64/n, recoverable as f64/n,
        textured_frac, mean_chroma, rich, soft, coloured,
        clipped as f64/(n*3.0),thirds[0],thirds[1],thirds[2],
        black_void as f64/n,whites as f64/n,std,
    ];
    result.iter().all(|f|f.is_finite()).then_some(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bundled_real_pilot_has_expected_schema_and_64_trees() {
        let f=serde_json::from_str::<Forest>(MODEL_JSON)
            .ok().and_then(Forest::validated);
        assert!(f.is_some(),"bundled pilot model malformed");
        let forest=f.unwrap_or_else(||unreachable!());
        let scores=forest.infer(&[0.0;22]);
        assert!(scores.is_some());
        let s=scores.unwrap_or_default();
        assert!(s.iter().all(|v|v.is_finite() && *v>=0.0 && *v<=1.0));
        assert!((s.iter().sum::<f64>()-1.0).abs()<1e-6);
    }
    #[test]
    fn native_proxy_model_is_deterministic_and_non_destructive() {
        let src=Rgb32f::filled(160,120,[0.18,0.17,0.16]);
        let original=src.clone();
        let info=SourceInfo::default();
        let settings=DevelopSettings::default();
        let a=diagnose(&src,&info,&settings);
        let b=diagnose(&src,&info,&settings);
        assert_eq!(a.status,"diagnostic_only");
        assert_eq!(a.predicted_class,b.predicted_class);
        assert_eq!(a.confidence,b.confidence);
        assert_eq!(src.data,original.data);
    }
}
