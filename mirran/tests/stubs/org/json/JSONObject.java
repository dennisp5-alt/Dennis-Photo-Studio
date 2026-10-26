package org.json;
import java.util.HashMap;
import java.util.Map;
public final class JSONObject {
    private final Map<String,Object> values=new HashMap<>();
    public JSONObject put(String key,Object value){values.put(key,value);return this;}
    public double optDouble(String key,double fallback) {
        Object n=values.get(key);return n instanceof Number?((Number)n).doubleValue():fallback;
    }
}
