package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Edge-sensitive detail tools with bounded working memory.
 * Reads original rows into a rolling buffer, then writes results in place so that
 * even large S23 Ultra JPEGs need no second full-size detail bitmap.
 *
 * Denoising is a lightweight guided-neighbourhood filter; dehaze is explicitly
 * local-contrast enhancement, not an atmospheric depth estimation model.
 */
final class DetailEngine {
    static final class Settings {
        float luminanceNoise, colorNoise, sharpening, clarity, texture, dehaze;
        boolean enabled() {
            return luminanceNoise > .01f || colorNoise > .01f ||
                    sharpening > .01f || clarity > .01f ||
                    texture > .01f || dehaze > .01f;
        }
    }
    static final class Recommended {
        final float luminanceNoise, colorNoise, sharpening, clarity, texture, dehaze;
        Recommended(float l, float c, float s, float cl, float t, float d) {
            luminanceNoise=l; colorNoise=c; sharpening=s; clarity=cl;
            texture=t; dehaze=d;
        }
    }
    private static float clamp(float n, float lo, float hi) {
        return Math.max(lo,Math.min(hi,n));
    }
    private static float luma(int pixel) {
        return (.2126f*Color.red(pixel)+.7152f*Color.green(pixel)+
                .0722f*Color.blue(pixel))/255f;
    }
    private static int rounded(float v) {
        return Math.round(clamp(v,0f,1f)*255f);
    }
    private static float smooth(float a, float b, float x) {
        float t=clamp((x-a)/(b-a),0f,1f);
        return t*t*(3f-2f*t);
    }
    // 64x64 local scene probes; compute only from source image, never a processed
    // image. Protect smooth flowers/skin and avoid automatically sharpening noise.
    static Recommended analyse(Bitmap src) {
        int w=src.getWidth(), h=src.getHeight(), n=0, flat=0;
        float noise=0, chroma=0, detail=0;
        int[] hist=new int[256];
        for (int gy=0;gy<64;gy++) {
            for (int gx=0;gx<64;gx++) {
                int x=Math.max(2,Math.min(w-3,(int)((gx+.5f)*w/64f)));
                int y=Math.max(2,Math.min(h-3,(int)((gy+.5f)*h/64f)));
                if (w<5 || h<5) continue;
                int c=src.getPixel(x,y);
                int l=src.getPixel(x-2,y),r=src.getPixel(x+2,y);
                int u=src.getPixel(x,y-2),d=src.getPixel(x,y+2);
                float center=luma(c);
                float avg=(luma(l)+luma(r)+luma(u)+luma(d))*.25f;
                float edge=(Math.abs(luma(l)-luma(r))+Math.abs(luma(u)-luma(d)))*.5f;
                detail += Math.abs(center-avg);
                int bucket=Math.min(255,Math.max(0,(int)(center*255)));
                hist[bucket]++;n++;
                if (edge < .055f && center>.08f && center<.85f) {
                    flat++; noise += Math.abs(center-avg);
                    float rCentre=Color.red(c)/255f-center;
                    float bCentre=Color.blue(c)/255f-center;
                    float lDiff=(Color.red(l)+Color.red(r)+Color.red(u)+Color.red(d))/1020f-avg;
                    float bDiff=(Color.blue(l)+Color.blue(r)+Color.blue(u)+Color.blue(d))/1020f-avg;
                    chroma+=(Math.abs(rCentre-lDiff)+Math.abs(bCentre-bDiff))*.5f;
                }
            }
        }
        if (n==0) return new Recommended(0,0,0,0,0,0);
        float flatFraction=flat/(float)n;
        float avgNoise=flat>40?noise/flat:0f;
        float avgChroma=flat>40?chroma/flat:0f;
        float apparentDetail=detail/n;
        int p10=percentile(hist,n,.10f),p90=percentile(hist,n,.90f);
        float dynamic=(p90-p10)/255f;
        // Conservative measured defaults; a clean photo is allowed zero NR.
        float lum=clamp((avgNoise-.009f)*720f,0f,22f);
        float col=clamp((avgChroma-.014f)*680f,0f,24f);
        float sharpen=clamp(11f+(.025f-apparentDetail)*140f-lum*.32f,0f,18f);
        float texture=clamp(6f+(.026f-apparentDetail)*80f-lum*.18f,0f,12f);
        float clarity=clamp((dynamic-.25f)*20f,0f,10f);
        float haze=clamp((.44f-dynamic)*24f,0f,8f);
        if (flatFraction<.06f) {lum=Math.min(lum,7f);col=Math.min(col,8f);}
        return new Recommended(lum,col,sharpen,clarity,texture,haze);
    }
    private static int percentile(int[] hist, int total, float fraction) {
        int min=Math.max(1,(int)Math.ceil(total*fraction)),n=0;
        for(int i=0;i<256;i++) {
            n+=hist[i]; if(n>=min)return i;
        }
        return 255;
    }
    private static int sample(int[][] rings,int rows,int y,int height,int x,int width) {
        int cy=Math.min(height-1,Math.max(0,y));
        int cx=Math.min(width-1,Math.max(0,x));
        return rings[cy%rows][cx];
    }
    static void applyInPlace(Bitmap bitmap, Settings e) {
        if (e==null || !e.enabled()) return;
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        if (w<3||h<3) return;
        final int rad=Math.max(1,Math.min(3,Math.round(w/2000f)));
        final int bigRad=rad*3, count=bigRad*2+1;
        // Rolling original rows: writing back must never contaminate neighbour data.
        int[][] ring=new int[count][w];
        for (int row=0;row<bigRad && row<h;row++) {
            bitmap.getPixels(ring[row%count],0,w,0,row,w,1);
        }
        int[] out=new int[w];
        float lumNR=clamp(e.luminanceNoise,0f,100f)/100f*.52f;
        float colorNR=clamp(e.colorNoise,0f,100f)/100f*.63f;
        float sharpen=clamp(e.sharpening,0f,100f)/100f*.55f;
        float texture=clamp(e.texture,0f,100f)/100f*.34f;
        float clarity=clamp(e.clarity,0f,100f)/100f*.24f;
        float dehaze=clamp(e.dehaze,0f,100f)/100f*.20f;
        boolean useSmall=lumNR>0 || colorNR>0 || sharpen>0 || texture>0;
        boolean useLarge=clarity>0;
        int[] off={-1,0,1};
        for (int y=0;y<h;y++) {
            int next=y+bigRad;
            if (next<h) bitmap.getPixels(ring[next%count],0,w,0,next,w,1);
            for (int x=0;x<w;x++) {
                int p=sample(ring,count,y,h,x,w);
                float a=Color.alpha(p)/255f;
                float r=Color.red(p)/255f,g=Color.green(p)/255f,b=Color.blue(p)/255f;
                float originalLum=.2126f*r+.7152f*g+.0722f*b;
                float maxRgb=Math.max(r,Math.max(g,b));
                // Avoid amplifying near-clipped petals, clouds, white clothing.
                float brightGate=1f-smooth(.74f,.98f,maxRgb);
                float baseLum=originalLum;
                float detail=0f;
                if (useSmall) {
                    float sumW=0,sumLum=0,sumR=0,sumG=0,sumB=0;
                    for(int dy:off)for(int dx:off) {
                        int pix=sample(ring,count,y+dy*rad,h,x+dx*rad,w);
                        float nl=luma(pix);
                        float distance=Math.abs(nl-originalLum);
                        float spatialW=(dx==0?2f:1f)*(dy==0?2f:1f);
                        float edgeW=clamp(1f-distance/.14f,.08f,1f);
                        float weight=spatialW*edgeW;
                        sumW+=weight;sumLum+=nl*weight;
                        if(colorNR>0) {
                            sumR+=Color.red(pix)/255f*weight;
                            sumG+=Color.green(pix)/255f*weight;
                            sumB+=Color.blue(pix)/255f*weight;
                        }
                    }
                    float blurLum=sumLum/sumW;
                    detail=originalLum-blurLum;
                    float flatGate=1f-smooth(.018f,.11f,Math.abs(detail));
                    // Denoise only flats. Never erase hard edges or eyes/text edges.
                    baseLum=originalLum+(blurLum-originalLum)*lumNR*flatGate;
                    if (colorNR>0) {
                        float avgR=sumR/sumW,avgG=sumG/sumW,avgB=sumB/sumW;
                        float avgGray=.2126f*avgR+.7152f*avgG+.0722f*avgB;
                        float colorBlend=colorNR*(.3f+.7f*flatGate);
                        r+=(avgR-avgGray-(r-originalLum))*colorBlend;
                        g+=(avgG-avgGray-(g-originalLum))*colorBlend;
                        b+=(avgB-avgGray-(b-originalLum))*colorBlend;
                    }
                    if(sharpen>0 || texture>0) {
                        float fine=Math.signum(detail)*Math.max(0f,Math.abs(detail)-.003f);
                        float moderate=1f-smooth(.07f,.19f,Math.abs(detail));
                        baseLum+=fine*(sharpen*brightGate*.55f+
                                texture*moderate*brightGate);
                    }
                }
                if(useLarge) {
                    float sum=0f;
                    for(int dy:off)for(int dx:off) {
                        int pix=sample(ring,count,y+dy*bigRad,h,x+dx*bigRad,w);
                        sum+=luma(pix);
                    }
                    float largeBlur=sum/9f;
                    float broad=originalLum-largeBlur;
                    float softGate=1f-smooth(.13f,.33f,Math.abs(broad));
                    baseLum+=clamp(broad,-.1f,.1f)*clarity*softGate*brightGate;
                }
                if(dehaze>0) {
                    // Restrained contrast/dehaze curve. Does not claim depth recovery.
                    float toneGate=1f-smooth(.77f,.98f,maxRgb);
                    baseLum+=(originalLum-.47f)*dehaze*toneGate*.65f;
                }
                float correction=baseLum-originalLum;
                // Luminance-only correction preserves original hue/saturation.
                float rr=r+correction,gg=g+correction,bb=b+correction;
                // Maintain headroom rather than forcing any channel to clip.
                float high=Math.max(rr,Math.max(gg,bb));
                if(high>1f) {
                    float amount=high-1f;
                    rr-=amount;gg-=amount;bb-=amount;
                }
                out[x]=Color.argb(Math.round(a*255f),rounded(rr),rounded(gg),rounded(bb));
            }
            bitmap.setPixels(out,0,w,0,y,w,1);
        }
    }
    private DetailEngine() {}
}
