package com.mirran.photo;
import android.graphics.Bitmap;

/**
 * Offline image enhancements. Uses bounded row buffers rather than storing a
 * second full-resolution frame. All operations are deterministic and optional.
 * Input and output are explicitly sRGB ARGB_8888 in this release.
 */
public final class DetailEngine {
    private DetailEngine(){}
    private static float clip(float n){return Math.max(0f,Math.min(1f,n));}
    private static int channel(float n){return Math.max(0,Math.min(255,Math.round(n*255f)));}
    private static float lum(int p){
        return (((p>>16)&255)*.2126f+((p>>8)&255)*.7152f+(p&255)*.0722f)/255f;
    }
    public static void denoiseInPlace(Bitmap bitmap,float amount){
        amount=clip(amount);
        if(amount<=.001f)return;
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        if(w<3||h<3)return;
        int[] previous=new int[w],current=new int[w],next=new int[w],out=new int[w];
        bitmap.getPixels(current,0,w,0,0,w,1);
        bitmap.getPixels(next,0,w,0,1,w,1);
        for(int y=0;y<h;y++){
            for(int x=0;x<w;x++){
                int p=current[x];
                if(y==0||y==h-1||x==0||x==w-1){out[x]=p;continue;}
                float l=lum(p),sumR=(p>>16)&255,sumG=(p>>8)&255,sumB=p&255;
                float total=1f;
                // 4-neighbour edge-aware filter; restrict cross-edge bleeding.
                int[] samples={previous[x],next[x],current[x-1],current[x+1]};
                for(int q:samples){
                    float diff=Math.abs(l-lum(q));
                    float weight=(float)Math.exp(-(diff*diff)/(.044f*.044f));
                    if(weight<.025f)continue;
                    total+=weight;
                    sumR+=((q>>16)&255)*weight;
                    sumG+=((q>>8)&255)*weight;
                    sumB+=(q&255)*weight;
                }
                float alpha=amount*.72f;
                float r=((p>>16)&255)*(1-alpha)+sumR/total*alpha;
                float g=((p>>8)&255)*(1-alpha)+sumG/total*alpha;
                float b=(p&255)*(1-alpha)+sumB/total*alpha;
                // Output luma restoration limits fine-detail smoothing.
                float originalL=l*255f,filteredL=.2126f*r+.7152f*g+.0722f*b;
                float restore=(originalL-filteredL)*(.70f-.15f*amount);
                r=Math.max(0,Math.min(255,r+restore));
                g=Math.max(0,Math.min(255,g+restore));
                b=Math.max(0,Math.min(255,b+restore));
                out[x]=(p&0xff000000)|((int)(r+.5f)<<16)|((int)(g+.5f)<<8)|(int)(b+.5f);
            }
            bitmap.setPixels(out,0,w,0,y,w,1);
            int[] old=previous;previous=current;current=next;next=old;
            if(y+2<h)bitmap.getPixels(next,0,w,0,y+2,w,1);
        }
    }
    /**
     * Cautious RGB airlight/transmission dehaze. Does not invent missing detail,
     * and avoids strong dehazing of deep shadows, saturated colours and skin.
     */
    public static void dehazeInPlace(Bitmap bitmap,float strength){
        strength=clip(strength);
        if(strength<=.001f)return;
        int w=bitmap.getWidth(),h=bitmap.getHeight(),stride=Math.max(1,(int)Math.sqrt(w*(double)h/12000d));
        float ar=0,ag=0,ab=0,best=-1;
        for(int y=0;y<h;y+=stride)for(int x=0;x<w;x+=stride){
            int c=bitmap.getPixel(x,y);
            float r=((c>>16)&255)/255f,g=((c>>8)&255)/255f,b=(c&255)/255f;
            float low=Math.min(r,Math.min(g,b)),high=Math.max(r,Math.max(g,b));
            float score=low*1.8f+high*.16f;
            if(score>best){best=score;ar=r;ag=g;ab=b;}
        }
        // Airlight selection is not trustworthy for an entirely dark scene.
        if(best<.45f)return;
        ar=Math.max(.55f,ar);ag=Math.max(.55f,ag);ab=Math.max(.55f,ab);
        int[] prev=new int[w],cur=new int[w],next=new int[w],out=new int[w];
        bitmap.getPixels(cur,0,w,0,0,w,1);
        if(h>1)bitmap.getPixels(next,0,w,0,1,w,1);
        for(int y=0;y<h;y++){
            for(int x=0;x<w;x++){
                int c=cur[x],a=c&0xff000000;
                float r=((c>>16)&255)/255f,g=((c>>8)&255)/255f,b=(c&255)/255f;
                float l=.2126f*r+.7152f*g+.0722f*b;
                float max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
                float sat=max-min;
                float dark=Math.min(r/ar,Math.min(g/ag,b/ab));
                if(x>0)dark=Math.min(dark,Math.min(((cur[x-1]>>16)&255)/255f/ar,
                    Math.min(((cur[x-1]>>8)&255)/255f/ag,(cur[x-1]&255)/255f/ab)));
                if(x+1<w)dark=Math.min(dark,Math.min(((cur[x+1]>>16)&255)/255f/ar,
                    Math.min(((cur[x+1]>>8)&255)/255f/ag,(cur[x+1]&255)/255f/ab)));
                if(y>0)dark=Math.min(dark,Math.min(((prev[x]>>16)&255)/255f/ar,
                    Math.min(((prev[x]>>8)&255)/255f/ag,(prev[x]&255)/255f/ab)));
                if(y+1<h)dark=Math.min(dark,Math.min(((next[x]>>16)&255)/255f/ar,
                    Math.min(((next[x]>>8)&255)/255f/ag,(next[x]&255)/255f/ab)));
                float t=Math.max(.76f,1f-.55f*strength*clip(dark));
                float guard=clip((l-.14f)/.16f)*clip((.95f-l)/.15f);
                guard*=1f-.80f*clip((sat-.19f)/.45f);
                boolean skin=r>g&&g>b&&r-g>.04&&g-b>.018&&l>.25f;
                if(skin)guard*=.28f;
                float alpha=strength*.76f*guard;
                float rr=clip(r+(((r-ar)/t+ar)-r)*alpha);
                float gg=clip(g+(((g-ag)/t+ag)-g)*alpha);
                float bb=clip(b+(((b-ab)/t+ab)-b)*alpha);
                out[x]=a|(channel(rr)<<16)|(channel(gg)<<8)|channel(bb);
            }
            bitmap.setPixels(out,0,w,0,y,w,1);
            int[] swap=prev;prev=cur;cur=next;next=swap;
            if(y+2<h)bitmap.getPixels(next,0,w,0,y+2,w,1);
        }
    }
    /** Blend a mild masked exposure adjustment; never adjust without a valid
     * semantic mask. Subject and background strengths are signed -1..1.
     */
    public static void maskedExposureInPlace(Bitmap bitmap,EditRecipe plan,SubjectMask mask){
        if(mask==null || (Math.abs(plan.subject)<.001f && Math.abs(plan.background)<.001f))return;
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        int[] row=new int[w];
        for(int y=0;y<h;y++){
            bitmap.getPixels(row,0,w,0,y,w,1);
            for(int x=0;x<w;x++){
                int c=row[x];float coverage=mask.sample(x,y,w,h);
                float ev=(plan.subject*coverage+plan.background*(1f-coverage))*.34f;
                if(Math.abs(ev)<.0002f)continue;
                float r=((c>>16)&255)/255f,g=((c>>8)&255)/255f,b=(c&255)/255f;
                float l=.2126f*r+.7152f*g+.0722f*b;
                float protect=clip((l-.045f)/.14f)*(1f-.88f*clip((l-.72f)/.26f));
                float change=(float)Math.pow(2,ev*protect)-1f;
                r=clip(r*(1f+change));g=clip(g*(1f+change));b=clip(b*(1f+change));
                row[x]=(c&0xff000000)|(channel(r)<<16)|(channel(g)<<8)|channel(b);
            }
            bitmap.setPixels(row,0,w,0,y,w,1);
        }
    }
}
