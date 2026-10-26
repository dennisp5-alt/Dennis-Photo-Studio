package android.graphics;
public final class Bitmap {
    private final int width,height;
    private final int[] pixels;
    public Bitmap(int w,int h) { width=w;height=h;pixels=new int[w*h];}
    public int getWidth() {return width;}
    public int getHeight() {return height;}
    public int getPixel(int x,int y) {return pixels[y*width+x];}
    public void setPixel(int x,int y,int p) {pixels[y*width+x]=p;}
    public void getPixels(int[] dst,int offset,int stride,int x,int y,int w,int h) {
        for(int yy=0;yy<h;yy++)for(int xx=0;xx<w;xx++)dst[offset+yy*stride+xx]=getPixel(x+xx,y+yy);
    }
    public void setPixels(int[] src,int offset,int stride,int x,int y,int w,int h) {
        for(int yy=0;yy<h;yy++)for(int xx=0;xx<w;xx++)setPixel(x+xx,y+yy,src[offset+yy*stride+xx]);
    }
}
