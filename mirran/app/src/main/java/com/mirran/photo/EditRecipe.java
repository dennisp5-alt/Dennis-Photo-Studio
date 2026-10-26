package com.mirran.photo;

import org.json.JSONObject;
import java.util.Locale;

public final class EditRecipe {
    // UI scale -100..+100; exposure internally maps to +/-1 EV.
    public float exposure, highlights, shadows, contrast, vibrance, warmth, clarity;
    public String summary = "Choose a photograph to begin.";
    public boolean cloud = false;

    public EditRecipe copy() {
        EditRecipe b = new EditRecipe();
        b.exposure=exposure; b.highlights=highlights; b.shadows=shadows;
        b.contrast=contrast; b.vibrance=vibrance; b.warmth=warmth; b.clarity=clarity;
        b.summary=summary; b.cloud=cloud; return b;
    }
    public float get(String k) {
        switch(k) {
            case "Exposure":return exposure; case "Highlights":return highlights;
            case "Shadows":return shadows; case "Contrast":return contrast;
            case "Vibrance":return vibrance; case "Warmth":return warmth;
            case "Clarity":return clarity; default:return 0;
        }
    }
    public void set(String k,float v) {
        v=clamp(v,-1,1);
        switch(k) {
            case "Exposure": exposure=v;break; case "Highlights":highlights=v;break;
            case "Shadows":shadows=v;break;case "Contrast":contrast=v;break;
            case "Vibrance":vibrance=v;break;case "Warmth":warmth=v;break;
            case "Clarity":clarity=v;break;
        }
    }
    public static float clamp(float n,float a,float b) {return Math.max(a,Math.min(b,n));}
    public static EditRecipe fromJson(JSONObject o) {
        EditRecipe r=new EditRecipe();
        r.exposure=(float)o.optDouble("exposure",0);
        r.highlights=(float)o.optDouble("highlights",0);
        r.shadows=(float)o.optDouble("shadows",0);
        r.contrast=(float)o.optDouble("contrast",0);
        r.vibrance=(float)o.optDouble("vibrance",0);
        r.warmth=(float)o.optDouble("warmth",0);
        r.clarity=(float)o.optDouble("clarity",0);
        r.exposure=clamp(r.exposure,-1,1);r.highlights=clamp(r.highlights,-1,1);
        r.shadows=clamp(r.shadows,-1,1);r.contrast=clamp(r.contrast,-1,1);
        r.vibrance=clamp(r.vibrance,-1,1);r.warmth=clamp(r.warmth,-1,1);
        r.clarity=clamp(r.clarity,-1,1); return r;
    }
    public String amount(String key) {
        float f=get(key)*100;
        return String.format(Locale.US,"%+.0f",f);
    }
}
