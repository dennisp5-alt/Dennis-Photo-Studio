package com.mirran.photo;

import android.graphics.Bitmap;
import org.json.JSONObject;

public final class AutoAnalyzer {
    public static final class Metrics {
        public float avgLuma,p10,p90,shadowShare,highlightShare,avgSat,redBias;
        public JSONObject asJson() {
            JSONObject j=new JSONObject();
            try {
                j.put("meanLuma",avgLuma);j.put("p10",p10);j.put("p90",p90);
                j.put("shadowShare",shadowShare);j.put("highlightShare",highlightShare);
                j.put("saturation",avgSat);j.put("redMinusBlue",redBias);
            } catch(Exception ignored) {}
            return j;
        }
    }
    public static Metrics measure(Bitmap b) {
        Metrics m=new Metrics();
        int w=b.getWidth(),h=b.getHeight(),step=Math.max(1,(int)Math.sqrt(w*(double)h/24000d));
        int[] hist=new int[256]; int n=0; float sat=0,redBlue=0;int low=0,high=0;
        for(int y=0;y<h;y+=step)for(int x=0;x<w;x+=step) {
            int c=b.getPixel(x,y),r=(c>>16)&255,g=(c>>8)&255,bl=c&255;
            int yy=Math.min(255,(int)(.2126*r+.7152*g+.0722*bl+.5));
            hist[yy]++;m.avgLuma+=yy/255f;n++;
            int mx=Math.max(r,Math.max(g,bl)),mn=Math.min(r,Math.min(g,bl));
            sat+=(mx-mn)/(float)Math.max(1,mx);
            redBlue+=(r-bl)/255f;
            if(yy<45)low++;if(yy>240)high++;
        }
        if(n==0)return m;
        m.avgLuma/=n;m.avgSat=sat/n;m.redBias=redBlue/n;
        m.shadowShare=low/(float)n;m.highlightShare=high/(float)n;
        int seen=0;
        for(int i=0;i<256;i++) {
            seen+=hist[i];
            if(m.p10==0 && seen>=n*.1)m.p10=i/255f;
            if(seen>=n*.9){m.p90=i/255f;break;}
        }
        return m;
    }
    public static EditRecipe suggest(Metrics m) {
        EditRecipe r=new EditRecipe();
        float range=m.p90-m.p10;
        // Deliberately restrained, scene-dependent and deterministic. Not semantic AI.
        if(m.avgLuma<.32f)r.exposure=EditRecipe.clamp((.4f-m.avgLuma)*1.5f,0,.34f);
        else if(m.avgLuma>.68f)r.exposure=EditRecipe.clamp((.55f-m.avgLuma)*1.1f,-.22f,0);
        r.highlights=-EditRecipe.clamp(m.highlightShare*1.7f+.08f,0,.42f);
        // Preserve the photograph's natural shadow depth. Sparse dark pixels should not
        // trigger a blanket lift; even heavily shadowed scenes have a restrained cap.
        r.shadows=EditRecipe.clamp(.025f+Math.max(0f,m.shadowShare-.09f)*.38f,0,.16f);
        r.contrast=EditRecipe.clamp((.60f-range)*.22f,-.08f,.16f);
        r.vibrance=m.avgSat<.30f?.15f:.055f;
        r.warmth=EditRecipe.clamp(-m.redBias*.14f,-.09f,.09f);
        r.clarity=range<.43f?.13f:.075f;
        r.summary=String.format(java.util.Locale.US,
            "Local tonal assessment • %s light, %s tonal range. Gentle shadow lift protects dark depth and natural colour.",
            m.avgLuma<.37?"low":m.avgLuma>.64?"bright":"balanced",
            range<.43?"compressed":"good");
        return r;
    }
}