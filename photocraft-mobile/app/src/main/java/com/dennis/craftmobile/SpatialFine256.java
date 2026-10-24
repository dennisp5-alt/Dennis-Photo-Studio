package com.dennis.craftmobile;

/**
 * 256x256 fine-zone EXPLORATION. Captures actual original-image samples and
 * compares each micro-zone with its 128x128 parent. No extra input-image scan.
 *
 * Sparse cells do not participate: every deviation is shrunk toward its
 * parent based on the observed sample count. Per-cell variation is limited
 * to 8% before smoothing. This cannot change exposure decisions or Auto WB.
 */
final class SpatialFine256 {
    static final int SIZE=256, CELLS=SIZE*SIZE;

    static float[] build(float[] sums, int[] counts, int[] hotspots,
                         float[] fine128, float[] sums128, int[] counts128) {
        if(sums.length!=CELLS || counts.length!=CELLS || hotspots.length!=CELLS ||
           fine128.length!=16384 || sums128.length!=16384 || counts128.length!=16384)
            throw new IllegalArgumentException("Invalid 256x256 zone data");
        float[] differences=new float[CELLS];
        for(int y=0;y<SIZE;y++) {
            for(int x=0;x<SIZE;x++) {
                int i=y*SIZE+x, parent=(y/2)*128+x/2;
                int n=counts[i], parentN=counts128[parent];
                if(n<3 || parentN<8) continue;
                float local=sums[i]/n;
                float broad=sums128[parent]/parentN;
                float saturated=hotspots[i]/(float)n;
                // Sparse measurements are not sufficiently trustworthy to
                // justify a full micro-adjustment.
                float confidence=Math.min(1f,(n-2f)/12f);
                float delta=(broad-local)*.36f-saturated*.030f;
                differences[i]=Math.max(-.08f,Math.min(.08f,delta))*confidence;
            }
        }
        float[] result=new float[CELLS];
        for(int y=0;y<SIZE;y++) {
            for(int x=0;x<SIZE;x++) {
                float weighted=0f,total=0f;
                for(int dy=-1;dy<=1;dy++) {
                    int yy=Math.max(0,Math.min(SIZE-1,y+dy));
                    for(int dx=-1;dx<=1;dx++) {
                        int xx=Math.max(0,Math.min(SIZE-1,x+dx));
                        int weight=(dy==0?2:1)*(dx==0?2:1);
                        weighted+=differences[yy*SIZE+xx]*weight;
                        total+=weight;
                    }
                }
                int parent=(y/2)*128+x/2;
                result[y*SIZE+x]=Math.max(0f,Math.min(1f,
                        fine128[parent]*(1f+weighted/total)));
            }
        }
        return result;
    }
    static int[] xIndices(int width) {
        int[] result=new int[width];
        for(int x=0;x<width;x++){
            float pos=Math.max(0f,Math.min(SIZE-1f,(x+.5f)*SIZE/width-.5f));
            result[x]=(int)pos;
        }
        return result;
    }
    static float[] xFractions(int width) {
        float[] result=new float[width];
        for(int x=0;x<width;x++){
            float pos=Math.max(0f,Math.min(SIZE-1f,(x+.5f)*SIZE/width-.5f));
            result[x]=pos-(int)pos;
        }
        return result;
    }
    static void interpolateRow(float[] zones,int y,int height,float[] output) {
        float pos=Math.max(0f,Math.min(SIZE-1f,
                (y+.5f)*SIZE/height-.5f));
        int top=(int)pos,bottom=Math.min(SIZE-1,top+1);
        float part=pos-top;
        for(int x=0;x<SIZE;x++) {
            output[x]=zones[top*SIZE+x]*(1f-part)+zones[bottom*SIZE+x]*part;
        }
    }
    private SpatialFine256(){}
}
