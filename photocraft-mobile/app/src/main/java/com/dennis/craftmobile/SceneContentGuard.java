package com.dennis.craftmobile;

/**
 * Conservative subject-region importance and intentional-black protection.
 *
 * Derives all evidence from the existing 64x64 scene statistics. The 16x16
 * graph detects sizeable, visibly lit connected regions against darkness;
 * it does not recognise faces, skin, people, objects or ethnicities.
 *
 * Only constrains positive Auto exposure and shadow strength. The optional
 * near-black weight guard acts on the existing 64x64 zone map. No colour
 * correction or detail-processing algorithm is changed.
 */
final class SceneContentGuard {
    static final class Decision {
        final float exposureCeiling;
        final float shadowScale;
        final boolean importantLitRegion;
        final boolean preserveNearBlack;
        final int darkestRegions, largestLitRegion;
        Decision(float ceiling, float shadowScale, boolean importantLitRegion,
                 boolean preserveNearBlack, int darkestRegions, int largestLitRegion) {
            this.exposureCeiling=ceiling;
            this.shadowScale=shadowScale;
            this.importantLitRegion=importantLitRegion;
            this.preserveNearBlack=preserveNearBlack;
            this.darkestRegions=darkestRegions;
            this.largestLitRegion=largestLitRegion;
        }
    }

    static Decision assess(float[] sums64, int[] counts64, int[] histogram,
                           int sampleCount, SceneJudgement16.Decision regional,
                           SceneAtmosphere8.Decision atmosphere,
                           SceneIntent4.Decision overall) {
        if(sums64.length!=4096 || counts64.length!=4096 ||
                histogram.length!=256) {
            throw new IllegalArgumentException("Invalid sampled scene dimensions");
        }
        if(sampleCount<1) {
            return new Decision(overall.exposureCeiling,overall.shadowScale,false,false,0,0);
        }

        final int SIZE=16;
        float[] coarse=new float[SIZE*SIZE];
        int dark=0;
        for(int y=0;y<SIZE;y++) {
            for(int x=0;x<SIZE;x++) {
                float sum=0f;
                int total=0;
                for(int dy=0;dy<4;dy++) {
                    for(int dx=0;dx<4;dx++) {
                        int i=(y*4+dy)*64+x*4+dx;
                        sum+=sums64[i];
                        total+=counts64[i];
                    }
                }
                float mean=total>0 ? sum/total : 0f;
                coarse[y*SIZE+x]=mean;
                if(mean<.17f)dark++;
            }
        }

        // Find contiguous regions of meaningfully lit midtones. Bounding the
        // size reduces response to single specular dots and uniformly bright
        // scenery. Importance is structural, never skin-colour based.
        boolean[] visited=new boolean[SIZE*SIZE];
        int[] queue=new int[SIZE*SIZE];
        int largest=0;
        for(int start=0;start<coarse.length;start++) {
            if(visited[start] || !candidate(coarse[start]))continue;
            visited[start]=true;
            int head=0,tail=0;
            queue[tail++]=start;
            while(head<tail) {
                int node=queue[head++];
                int x=node%SIZE, y=node/SIZE;
                if(x>0)tail=add(coarse,visited,queue,tail,node-1);
                if(x<SIZE-1)tail=add(coarse,visited,queue,tail,node+1);
                if(y>0)tail=add(coarse,visited,queue,tail,node-SIZE);
                if(y<SIZE-1)tail=add(coarse,visited,queue,tail,node+SIZE);
            }
            largest=Math.max(largest,tail);
        }

        int p10=percentile(histogram,sampleCount,.10f);
        int p50=percentile(histogram,sampleCount,.50f);
        int p85=percentile(histogram,sampleCount,.85f);
        int p95=percentile(histogram,sampleCount,.95f);

        // Require convergent evidence. A bright fire has separate atmosphere
        // safeguards; a uniformly underexposed photo should still be fixed.
        boolean meaningfulRegion=!regional.trulyDark &&
                dark>=72 && p50<82 && p85>=97 && p95>=123 &&
                largest>=9 && largest<=110;

        float ceiling=overall.exposureCeiling;
        float shadows=overall.shadowScale;
        if(meaningfulRegion) {
            ceiling=Math.min(ceiling,.09f);
            shadows=Math.min(shadows,.66f);
        }

        // Preserve deep blacks ONLY when the four-grid analysis already
        // indicates low-key intent, or there is a credible lit-region signal.
        // Unlit / genuinely underexposed scenes retain the previous recovery.
        boolean keepBlack=p10<27 && dark>=72 &&
                (atmosphere.protectedAtmosphere || overall.preserved ||
                 regional.protectedLowKey || meaningfulRegion);
        return new Decision(ceiling,shadows,meaningfulRegion,keepBlack,dark,largest);
    }

    static float restrainedLocalWeight(float original, float localLuminance,
                                       boolean preserveNearBlack) {
        if(!preserveNearBlack)return original;
        // Only pitch-black regions are protected, with a gentle transition
        // over dark shades. Up to 25% less local shadow lift; never negative.
        float dark=(.17f-localLuminance)/.13f;
        dark=Math.max(0f,Math.min(1f,dark));
        return original*(1f-.25f*dark);
    }

    private static boolean candidate(float luminance) {
        return luminance>=.33f && luminance<=.80f;
    }

    private static int add(float[] coarse,boolean[] visited,int[] queue,
                           int tail,int index) {
        if(!visited[index] && candidate(coarse[index])) {
            visited[index]=true;
            queue[tail++]=index;
        }
        return tail;
    }

    private static int percentile(int[] histogram,int total,float quantile) {
        int target=Math.max(1,(int)Math.ceil(total*quantile));
        int sum=0;
        for(int i=0;i<histogram.length;i++) {
            sum+=histogram[i];
            if(sum>=target)return i;
        }
        return 255;
    }

    private SceneContentGuard(){}
}
