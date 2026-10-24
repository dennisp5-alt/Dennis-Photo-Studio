package com.dennis.craftmobile;

/**
 * Experimental 128x128 micro-zone analysis. Uses same input scan as 64x64.
 * Provides conservative fine shadow weights; no global tone/WB changes.
 */
final class SpatialFine128 {
    static final int SIZE=128, CELLS=SIZE*SIZE;

    static float[] build(float[] sums,int[] counts,int[] hot,
                         float[] coarseZones,float[] sums64,int[] counts64) {
        if(sums.length!=CELLS || counts.length!=CELLS ||
                hot.length!=CELLS || coarseZones.length!=4096)
            throw new IllegalArgumentException("Invalid 128x128 zone data");
        float[] deviations=new float[CELLS];
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
            int i=y*SIZE+x, parent=(y/2)*64+x/2;
            if(counts[i]<3 || counts64[parent]<4)continue;
            float local=sums[i]/counts[i];
            float broad=sums64[parent]/counts64[parent];
            float bright=hot[i]/(float)counts[i];
            // Bright sub-regions get less shadow lift. +/-12% limit.
            deviations[i]=Math.max(-.12f,Math.min(.12f,
                    (broad-local)*.48f-bright*.045f));
        }
        float[] zones=new float[CELLS];
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
            float sum=0f,total=0f;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                int yy=Math.max(0,Math.min(SIZE-1,y+dy));
                int xx=Math.max(0,Math.min(SIZE-1,x+dx));
                int w=(dy==0?2:1)*(dx==0?2:1);
                sum+=deviations[yy*SIZE+xx]*w;total+=w;
            }
            int parent=(y/2)*64+x/2;
            zones[y*SIZE+x]=Math.max(0f,Math.min(1f,
                    coarseZones[parent]*(1f+sum/total)));
        }
        return zones;
    }

    static int[] xIndices(int width) {
        int[] result=new int[width];
        for(int x=0;x<width;x++){
            float at=Math.max(0f,Math.min(SIZE-1f,(x+.5f)*SIZE/width-.5f));
            result[x]=(int)at;
        }
        return result;
    }
    static float[] xFractions(int width) {
        float[] result=new float[width];
        for(int x=0;x<width;x++){
            float at=Math.max(0f,Math.min(SIZE-1f,(x+.5f)*SIZE/width-.5f));
            result[x]=at-(int)at;
        }
        return result;
    }
    static void interpolateRow(float[] fine,int y,int height,float[] row) {
        float at=Math.max(0f,Math.min(SIZE-1f,(y+.5f)*SIZE/height-.5f));
        int top=(int)at,bottom=Math.min(SIZE-1,top+1);
        float f=at-top;
        for(int x=0;x<SIZE;x++){
            row[x]=fine[top*SIZE+x]*(1f-f)+fine[bottom*SIZE+x]*f;
        }
    }
    private SpatialFine128(){}
}
