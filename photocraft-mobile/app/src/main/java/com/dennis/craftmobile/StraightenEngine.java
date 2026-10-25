package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * Conservative geometric straightening, separate from the locked photographic
 * tone/WB/detail algorithms. No neural models or external libraries.
 */
final class StraightenEngine {
    static final float MANUAL_MAX = 15f;
    private static final float AUTO_MAX = 10f;
    private static final float BIN_STEP = .5f;
    private static final int BINS = 49; // -12° ... +12°

    static final class Estimate {
        final float correction;
        final float confidence;
        final boolean reliable;
        Estimate(float degrees, float confidence, boolean reliable) {
            this.correction=degrees;
            this.confidence=confidence;
            this.reliable=reliable;
        }
    }

    static Estimate analyse(Bitmap source) {
        if(source==null)return new Estimate(0,0,false);
        Bitmap small=source;
        int longEdge=Math.max(source.getWidth(),source.getHeight());
        if(longEdge>800) {
            float scale=800f/longEdge;
            small=Bitmap.createScaledBitmap(source,
                    Math.max(40,Math.round(source.getWidth()*scale)),
                    Math.max(40,Math.round(source.getHeight()*scale)),true);
        }
        try {
            int w=small.getWidth(),h=small.getHeight();
            if(w<96||h<96)return new Estimate(0,0,false);
            int[] p=new int[w*h];
            small.getPixels(p,0,w,0,0,w,h);
            float[] grey=new float[p.length];
            for(int i=0;i<p.length;i++) {
                int c=p[i];
                grey[i]=(.2126f*Color.red(c)+.7152f*Color.green(c)+
                        .0722f*Color.blue(c));
            }
            float[] votes=new float[BINS];
            float[][] regional=new float[16][BINS];
            int edges=0;
            for(int y=2;y<h-2;y+=2) {
                if(Thread.currentThread().isInterrupted())
                    return new Estimate(0,0,false);
                for(int x=2;x<w-2;x+=2) {
                    int i=y*w+x;
                    float gx=(grey[i-w+1]+2f*grey[i+1]+grey[i+w+1])
                            -(grey[i-w-1]+2f*grey[i-1]+grey[i+w-1]);
                    float gy=(grey[i+w-1]+2f*grey[i+w]+grey[i+w+1])
                            -(grey[i-w-1]+2f*grey[i-w]+grey[i-w+1]);
                    float magnitude=(float)Math.hypot(gx,gy);
                    if(magnitude<105f)continue;
                    // Edge tangent in image coordinates, relative to the
                    // nearest horizontal or vertical architectural axis.
                    float angle=(float)Math.toDegrees(Math.atan2(-gx,gy));
                    while(angle>45f)angle-=90f;
                    while(angle< -45f)angle+=90f;
                    if(Math.abs(angle)>12f)continue;
                    float pos=(angle+12f)/BIN_STEP;
                    int at=(int)Math.floor(pos);
                    float f=pos-at;
                    float weight=Math.min(420f,magnitude);
                    int region=Math.min(3,y*4/h)*4+Math.min(3,x*4/w);
                    if(at>=0&&at<BINS) {
                        float a=weight*(1f-f);
                        votes[at]+=a;
                        regional[region][at]+=a;
                    }
                    if(at+1>=0&&at+1<BINS) {
                        float b=weight*f;
                        votes[at+1]+=b;
                        regional[region][at+1]+=b;
                    }
                    edges++;
                }
            }
            if(edges<100)return new Estimate(0,0,false);
            float total=0f;
            for(float v:votes)total+=v;
            if(total<9000f)return new Estimate(0,0,false);
            float[] smooth=new float[BINS];
            int best=0;
            for(int b=1;b<BINS-1;b++){
                smooth[b]=votes[b-1]+votes[b]+votes[b+1];
                if(smooth[b]>smooth[best])best=b;
            }
            if(best==0||best==BINS-1)return new Estimate(0,0,false);
            float peak=smooth[best];
            float fraction=peak/total;
            float atZero=smooth[BINS/2];
            float angle=-12f+best*BIN_STEP;
            if(best>1 && best<BINS-2) {
                // Refine the histogram maximum sub-bin for 0.1-degree-level
                // manual follow-up without making a brittle single-edge fit.
                float l=smooth[best-1],m=smooth[best],r=smooth[best+1];
                float denom=l-2f*m+r;
                if(Math.abs(denom)>1e-4f) {
                    float shift=.5f*(l-r)/denom;
                    angle+=BIN_STEP*Math.max(-.5f,Math.min(.5f,shift));
                }
            }
            int regions=0,columns=0,rows=0;
            for(int region=0;region<16;region++) {
                float local=regional[region][best-1]+regional[region][best]+
                        regional[region][best+1];
                if(local>peak*.055f) {
                    regions++;
                    columns|=1<<(region%4);
                    rows|=1<<(region/4);
                }
            }
            int columnCount=Integer.bitCount(columns),rowCount=Integer.bitCount(rows);
            // Significant structured evidence across the picture; no
            // automatic correction for a lone sloping limb or branch.
            boolean reliable=fraction>.205f && peak>atZero*1.17f &&
                    regions>=3 && (columnCount>=3 || rowCount>=3) &&
                    Math.abs(angle)>=.35f && Math.abs(angle)<=AUTO_MAX;
            float confidence=Math.min(1f,Math.max(0f,
                    (fraction-.14f)*4.0f));
            return new Estimate(reliable?-angle:0f,confidence,reliable);
        } finally {
            if(small!=source&&!small.isRecycled())small.recycle();
        }
    }

    /**
     * Rotate into the SAME output frame with exactly enough centred zoom to
     * cover every output corner. This avoids black triangles, at the cost
     * of a modest crop that increases with angle / aspect ratio.
     */
    static Bitmap rotateAndFill(Bitmap source,float degrees) {
        if(source==null||Math.abs(degrees)<.015f)return source;
        float angle=Math.max(-MANUAL_MAX,Math.min(MANUAL_MAX,degrees));
        int w=source.getWidth(),h=source.getHeight();
        double rad=Math.toRadians(angle);
        float cosine=(float)Math.abs(Math.cos(rad));
        float sine=(float)Math.abs(Math.sin(rad));
        float zoom=Math.max(cosine+sine*(h/(float)w),
                cosine+sine*(w/(float)h));
        // Small safety margin against fractional antialias sampling at edges.
        zoom=Math.max(1f,zoom*1.001f);
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(result);
        Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
        float cx=w*.5f,cy=h*.5f;
        canvas.translate(cx,cy);
        canvas.rotate(angle);
        canvas.scale(zoom,zoom);
        canvas.translate(-cx,-cy);
        canvas.drawBitmap(source,0f,0f,paint);
        return result;
    }
    private StraightenEngine() {}
}
