package com.mirran.photo;

import android.graphics.Bitmap;

public final class ToneEngine {
    private ToneEngine() {}
    private static float clip(float n){return n<0?0:Math.min(1,n);}
    private static float sigmoid(float x,float amount) {
        // Contrast via a smooth cubic S-curve that retains endpoints.
        float s=x*x*(3f-2f*x);
        return clip(x+(s-x)*amount);
    }
    public static void applyInPlace(Bitmap b, EditRecipe e) {
        int w=b.getWidth(),h=b.getHeight();
        final float gain=(float)Math.pow(2,e.exposure*.9f);
        final float contrast=e.contrast*.9f;
        final float shadows=e.shadows*.46f, highs=e.highlights*.48f;
        final float vibrance=e.vibrance*.68f, warmth=e.warmth*.085f;
        final float clarity=e.clarity*.28f;
        int[] prev=new int[w],cur=new int[w],next=new int[w];
        b.getPixels(cur,0,w,0,0,w,1);
        if(h>1)b.getPixels(next,0,w,0,1,w,1);
        int[] out=new int[w];
        for(int y=0;y<h;y++) {
            for(int x=0;x<w;x++) {
                int c=cur[x],a=(c>>>24)&255;
                float r=((c>>>16)&255)/255f,g=((c>>>8)&255)/255f,bl=(c&255)/255f;
                float lum=.2126f*r+.7152f*g+.0722f*bl;
                float target=clip(lum*gain);
                float shadowWeight=(1-target)*(1-target);
                float highWeight=target*target;
                target+=shadows*shadowWeight*(1-target);
                target+=highs*highWeight*target;
                target=sigmoid(clip(target),contrast);
                // Apply exposure once, then adjust the new luminance by the tone curve.
                // Avoid double-boosting exposure and clipping bright colours.
                float delta=target-clip(lum*gain);
                r=clip(r*gain + delta);
                g=clip(g*gain + delta);
                bl=clip(bl*gain + delta);
                // Small selective warming, guarded from tint extremes.
                r=clip(r+warmth*.55f);
                bl=clip(bl-warmth*.62f);
                float grey=.2126f*r+.7152f*g+.0722f*bl;
                float max=Math.max(r,Math.max(g,bl)),min=Math.min(r,Math.min(g,bl));
                float sat=max-min;
                boolean skin=r>g && g>bl && r-g>.04 && g-bl>.025 && r<.92;
                float vibGain=1+vibrance*(1-sat)*(skin?.43f:1f);
                r=clip(grey+(r-grey)*vibGain);g=clip(grey+(g-grey)*vibGain);
                bl=clip(grey+(bl-grey)*vibGain);
                if(clarity!=0f&&x>1&&x<w-2&&y>0&&y<h-1) {
                    // Luma-only 3x3 local contrast; limited to avoid halos.
                    int a1=prev[x-2],a2=prev[x],a3=prev[x+2];
                    int b1=cur[x-2],b2=cur[x+2];
                    int d1=next[x-2],d2=next[x],d3=next[x+2];
                    float avg=(yi(a1)+yi(a2)+yi(a3)+yi(b1)+yi(c)+yi(b2)+yi(d1)+yi(d2)+yi(d3))/9f;
                    float diff=(lum-avg)*clarity;
                    r=clip(r+diff);g=clip(g+diff);bl=clip(bl+diff);
                }
                out[x]=(a<<24)|((int)(r*255+.5f)<<16)|((int)(g*255+.5f)<<8)|(int)(bl*255+.5f);
            }
            b.setPixels(out,0,w,0,y,w,1);
            int[] swap=prev;prev=cur;cur=next;next=swap;
            if(y+2<h)b.getPixels(next,0,w,0,y+2,w,1);
        }
    }
    private static float yi(int c) {
        return (.2126f*((c>>16)&255)+.7152f*((c>>8)&255)+.0722f*(c&255))/255f;
    }
}
