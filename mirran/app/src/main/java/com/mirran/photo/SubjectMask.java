package com.mirran.photo;
/** A compact, softened and bilinearly sampled foreground confidence mask. */
public final class SubjectMask {
    public final int width,height;
    private final byte[] alpha;
    public SubjectMask(int w,int h,float[] probabilities){
        if(w<=0||h<=0||probabilities==null||probabilities.length!=w*h)
            throw new IllegalArgumentException("Invalid subject mask dimensions");
        width=w;height=h;alpha=new byte[w*h];
        for(int i=0;i<alpha.length;i++){
            float v=Math.max(0f,Math.min(1f,probabilities[i]));
            // Confidence ramp feathers subject boundaries.
            float t=Math.max(0f,Math.min(1f,(v-.20f)/.65f));
            float softened=t*t*(3f-2f*t);
            alpha[i]=(byte)Math.round(255f*softened);
        }
    }
    public float sample(int x,int y,int imageW,int imageH){
        float fx=(x+.5f)*width/Math.max(1,imageW)-.5f;
        float fy=(y+.5f)*height/Math.max(1,imageH)-.5f;
        int x0=Math.max(0,Math.min(width-1,(int)Math.floor(fx)));
        int y0=Math.max(0,Math.min(height-1,(int)Math.floor(fy)));
        int x1=Math.min(width-1,x0+1),y1=Math.min(height-1,y0+1);
        float wx=Math.max(0f,Math.min(1f,fx-x0)),wy=Math.max(0f,Math.min(1f,fy-y0));
        float a=(alpha[y0*width+x0]&255)*(1-wx)+(alpha[y0*width+x1]&255)*wx;
        float b=(alpha[y1*width+x0]&255)*(1-wx)+(alpha[y1*width+x1]&255)*wx;
        return (a*(1-wy)+b*wy)/255f;
    }
    public float coverage(){
        double sum=0;
        for(byte b:alpha)sum+=(b&255)/255d;
        return (float)(sum/alpha.length);
    }
}
