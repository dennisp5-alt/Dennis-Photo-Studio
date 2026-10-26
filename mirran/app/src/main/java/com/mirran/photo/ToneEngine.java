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
    private static float smoothstep(float a,float b,float value) {
        float t=clip((value-a)/(b-a));
        return t*t*(3f-2f*t);
    }
    public static void applyInPlace(Bitmap b, EditRecipe e) {
        int w=b.getWidth(),h=b.getHeight();
        final float gain=(float)Math.pow(2,e.exposure*.9f);
        // In v0.1.2 this factor was just .9 and Auto's +10 contrast hardly
        // moved real pixels. Make the adjustment genuinely visible but keep the
        // manual control bounded to a monotonic, reversible tone curve.
        final float contrast=EditRecipe.clamp(e.contrast*2.65f,-.82f,.82f);
        final float shadows=e.shadows*.40f, highs=e.highlights*.48f;
        final float vibrance=e.vibrance*.90f, warmth=e.warmth*.085f;
        // Clarity is a restrained microcontrast adjustment, not noise removal.
        // Local Auto suppresses it when image statistics suggest visible grain.
        final float clarity=e.clarity*.20f;
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
                // Protect true blacks and dark foliage: never force black into grey.
                // Lift shadows only above the toe, fading smoothly into midtones.
                float shadowWeight=(1-target)*(1-target)*smoothstep(.055f,.22f,target);
                float highWeight=target*target;
                target+=shadows*shadowWeight*(1-target);
                target+=highs*highWeight*target;
                // A midtone-centred S-curve: no grey toe or white highlight surge.
                float contrastGuard=smoothstep(.04f,.18f,target)*
                    (1f-.76f*smoothstep(.70f,.96f,target));
                target=sigmoid(clip(target),contrast*contrastGuard);
                // Apply exposure once, then adjust the new luminance by the tone curve.
                // Avoid double-boosting exposure and clipping bright colours.
                float delta=target-clip(lum*gain);
                // Positive tonal changes must not create new blown RGB
                // channels on bright clothing, sunlit skin, or saturated skies.
                float channelPeak=Math.max(r,Math.max(g,bl))*gain;
                if(delta>0f && channelPeak>.82f)
                    delta*=1f-smoothstep(.82f,.98f,channelPeak);
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
                // Bright saturated petals and illuminated skin must not clip
                // further when the overall scene needs extra colour presence.
                float highlightGuard=1f-smoothstep(.88f,.98f,max);
                float vibGain=1+vibrance*(1-sat)*(skin?.43f:1f)*highlightGuard;
                r=clip(grey+(r-grey)*vibGain);g=clip(grey+(g-grey)*vibGain);
                bl=clip(grey+(bl-grey)*vibGain);
                if(clarity!=0f&&x>1&&x<w-2&&y>0&&y<h-1) {
                    // Luma-only 3x3 local contrast; limited to avoid halos.
                    int a1=prev[x-2],a2=prev[x],a3=prev[x+2];
                    int b1=cur[x-2],b2=cur[x+2];
                    int d1=next[x-2],d2=next[x],d3=next[x+2];
                    float avg=(yi(a1)+yi(a2)+yi(a3)+yi(b1)+yi(c)+yi(b2)+yi(d1)+yi(d2)+yi(d3))/9f;
                    // Avoid adding granular texture to already dark or nearly
                    // clipped areas; the original pixels stay unblurred.
                    float textureGuard=smoothstep(.14f,.34f,lum)*
                        (1f-.62f*smoothstep(.78f,.97f,lum));
                    float diff=(lum-avg)*clarity*textureGuard;
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
