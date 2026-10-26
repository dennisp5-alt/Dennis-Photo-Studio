package com.mirran.photo;

import android.graphics.Bitmap;
import org.json.JSONObject;
import java.util.Locale;

/** Lightweight offline scene statistics, not semantic AI/subject segmentation. */
public final class AutoAnalyzer {
    public static final class Metrics {
        public float avgLuma,p10,p90,shadowShare,highlightShare,avgSat,redBias;
        public float centerLuma,centerP75,noiseIndex,channelClipShare;
        public float blueShare,neutralFraction,neutralRedBlue;
        public JSONObject asJson() {
            JSONObject j=new JSONObject();
            try {
                j.put("meanLuma",avgLuma);j.put("p10",p10);j.put("p90",p90);
                j.put("shadowShare",shadowShare);j.put("highlightShare",highlightShare);
                j.put("saturation",avgSat);j.put("redMinusBlue",redBias);
                j.put("centerLuma",centerLuma);j.put("centerP75",centerP75);
                j.put("noiseProxy",noiseIndex);j.put("channelClipping",channelClipShare);
                j.put("blueDominance",blueShare);j.put("neutralFraction",neutralFraction);
            } catch(Exception ignored) {}
            return j;
        }
    }
    private static float luma(int pixel) {
        return (.2126f*((pixel>>16)&255)+.7152f*((pixel>>8)&255)+.0722f*(pixel&255))/255f;
    }
    public static Metrics measure(Bitmap b) {
        Metrics m=new Metrics();
        int w=b.getWidth(),h=b.getHeight();
        if(w==0||h==0)return m;
        int step=Math.max(1,(int)Math.sqrt(w*(double)h/26000d));
        int[] hist=new int[256],centerHist=new int[256];
        int count=0,centerCount=0,shadows=0,highlights=0,clipped=0,blues=0,neutrals=0,noiseSamples=0;
        double lumSum=0,centerSum=0,satSum=0,rbSum=0,neutralRB=0,noiseSum=0;
        for(int y=0;y<h;y+=step)for(int x=0;x<w;x+=step){
            int p=b.getPixel(x,y),r=(p>>16)&255,g=(p>>8)&255,bl=p&255;
            float lum=(.2126f*r+.7152f*g+.0722f*bl)/255f;
            int bin=Math.min(255,Math.max(0,Math.round(lum*255f)));
            hist[bin]++;lumSum+=lum;count++;
            int max=Math.max(r,Math.max(g,bl)),min=Math.min(r,Math.min(g,bl));
            float sat=(max-min)/(float)Math.max(1,max);
            satSum+=sat;rbSum+=(r-bl)/255f;
            if(bin<45)shadows++;if(bin>240)highlights++;
            // Channel clipping is especially important for blue skies/red clothing:
            // luminous histogram alone cannot detect clipped individual channels.
            if(max>=252)clipped++;
            if(bl>r*1.12f && bl>g*1.06f && sat>.2f)blues++;
            if(sat<.13f && lum>.22f && lum<.86f){
                neutrals++;neutralRB+=(r-bl)/255f;
            }
            if(x>=w/4 && x<3*w/4 && y>=h/4 && y<3*h/4){
                centerSum+=lum;centerCount++;centerHist[bin]++;
            }
            // Smooth-area high-frequency proxy: not reliable enough to call ISO,
            // but useful for suppressing sharpening of visibly noisy photos.
            if(x>1&&x<w-2&&y>1&&y<h-2&&lum>.065f&&lum<.85f){
                float left=luma(b.getPixel(x-1,y));
                float right=luma(b.getPixel(x+1,y));
                float up=luma(b.getPixel(x,y-1));
                float down=luma(b.getPixel(x,y+1));
                float opposite=(Math.abs(left-right)+Math.abs(up-down))*.5f;
                if(opposite<.09f){
                    float residual=Math.abs(lum-(left+right+up+down)*.25f);
                    noiseSum+=residual;noiseSamples++;
                }
            }
        }
        if(count==0)return m;
        m.avgLuma=(float)(lumSum/count);
        m.avgSat=(float)(satSum/count);
        m.redBias=(float)(rbSum/count);
        m.shadowShare=shadows/(float)count;
        m.highlightShare=highlights/(float)count;
        m.channelClipShare=clipped/(float)count;
        m.blueShare=blues/(float)count;
        m.neutralFraction=neutrals/(float)count;
        m.neutralRedBlue=neutrals>0?(float)(neutralRB/neutrals):0f;
        m.centerLuma=centerCount>0?(float)(centerSum/centerCount):m.avgLuma;
        m.noiseIndex=noiseSamples>0?(float)(noiseSum/noiseSamples):0f;
        int running=0,midRunning=0;
        for(int i=0;i<256;i++){
            running+=hist[i];midRunning+=centerHist[i];
            if(m.p10==0f&&running>=Math.max(1,count*.10f))m.p10=i/255f;
            if(running>=count*.9f && m.p90==0f)m.p90=i/255f;
            if(centerCount>0&&midRunning>=centerCount*.75f&&m.centerP75==0f)
                m.centerP75=i/255f;
        }
        return m;
    }
    public static EditRecipe suggest(Metrics m) {
        EditRecipe r=new EditRecipe();
        float range=m.p90-m.p10;
        // Fire, Christmas lights, night scenes: do not brighten intentional blacks.
        boolean lowKey=m.shadowShare>.72f&&m.avgLuma<.20f&&m.p90<.40f;
        if(lowKey){
            r.exposure=0f;r.highlights=-.06f;r.shadows=0f;
            r.contrast=.025f;r.vibrance=0f;r.warmth=0f;r.clarity=0f;
            r.summary="Local analysis • intentional dark scene protected; blacks and luminous details retained.";
            return r;
        }
        // Prevent dark surroundings from forcing an already-lit central subject
        // brighter (e.g., a pale flower against nearly black mulch).
        boolean litSubject=m.shadowShare>.25f &&
            m.centerLuma>m.avgLuma+.085f&&m.centerP75>.60f;
        if(!litSubject&&m.avgLuma<.32f)
            r.exposure=EditRecipe.clamp((.37f-m.avgLuma)*1.1f,0,.18f);
        else if(m.avgLuma>.68f)
            r.exposure=EditRecipe.clamp((.57f-m.avgLuma)*.9f,-.16f,0);
        r.highlights=-EditRecipe.clamp(.075f+m.highlightShare*1.4f+
            Math.max(0f,m.p90-.83f)*.6f+m.channelClipShare*.25f,.06f,.32f);
        r.shadows=EditRecipe.clamp(.025f+Math.max(0f,m.shadowShare-.09f)*.32f,0,.14f);
        if(litSubject)r.shadows=Math.min(r.shadows,.045f);
        r.contrast=EditRecipe.clamp(.125f+(.60f-range)*.18f,.075f,.185f);
        r.vibrance=EditRecipe.clamp(.20f-.30f*m.avgSat,.02f,.13f);
        // Protect heavily blue or already channel-clipped images. Clipped RGB
        // channel information cannot be reconstructed by global highlights.
        if(m.blueShare>.44f)r.vibrance=Math.min(r.vibrance,.025f);
        if(m.channelClipShare>.065f) {
            r.vibrance=Math.min(r.vibrance,.02f);
            r.contrast=Math.min(r.contrast,.135f);
        }
        // Genuine neutral regions are required for a WB proposal. Global R-B
        // differences alone are not a colour cast (water, sky, flowers, skin).
        if(m.neutralFraction>.09f&&m.blueShare<.35f)
            r.warmth=EditRecipe.clamp(-m.neutralRedBlue*.18f,-.05f,.05f);
        else r.warmth=0f;
        // Do not sharpen the residual grain in bright, heavily compressed or
        // noisy photographs. Existing detail is maintained, not smeared.
        if(m.noiseIndex>.0135f) {
            r.clarity=0f;
            r.contrast=Math.min(r.contrast,.135f);
        } else if(m.noiseIndex>.009f) {
            r.clarity=.025f;
        } else {
            r.clarity=range<.44f?.085f:.055f;
        }
        if(litSubject)r.clarity=Math.min(r.clarity,.025f);
        // Conservative local detail recommendations, not semantic scene inference.
        r.denoise=m.noiseIndex>.009f?
            EditRecipe.clamp((m.noiseIndex-.009f)*12f,.06f,.32f):0f;
        if(m.p90-m.p10<.36f&&m.avgLuma>.34f&&m.avgLuma<.70f
            &&m.shadowShare<.14f&&m.blueShare<.28f&&m.channelClipShare<.045f)
            r.dehaze=.10f;
        r.summary=String.format(Locale.US,
            "Local tonal analysis • %s; %s; %s. Balanced exposure, protected bright colours and preserved natural shadows.",
            litSubject?"lit subject in dark surroundings":"whole-frame tone",
            m.blueShare>.44f?"cool scene colour preserved":"colour confidence checked",
            m.noiseIndex>.0135f?"grain protected":"detail retained");
        return r;
    }
}