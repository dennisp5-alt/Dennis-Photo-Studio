package com.mirran.photo;

import android.graphics.Bitmap;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

public final class RemoteAi {
    private RemoteAi() {}
    public static EditRecipe analyze(String server,String accessToken,Bitmap original,AutoAnalyzer.Metrics metrics) throws Exception {
        String base=server.trim();
        if(!base.startsWith("https://"))throw new SecurityException("Only HTTPS servers are allowed");
        if(base.endsWith("/"))base=base.substring(0,base.length()-1);
        Bitmap mini=original;
        int max=Math.max(original.getWidth(),original.getHeight());
        if(max>1200) {
            float scale=1200f/max;
            mini=Bitmap.createScaledBitmap(original,Math.max(1,Math.round(original.getWidth()*scale)),
                Math.max(1,Math.round(original.getHeight()*scale)),true);
        }
        ByteArrayOutputStream photo=new ByteArrayOutputStream();
        if(!mini.compress(Bitmap.CompressFormat.JPEG,82,photo))throw new IOException("Thumbnail encoding failed");
        if(mini!=original)mini.recycle();
        JSONObject request=new JSONObject();
        request.put("image","data:image/jpeg;base64,"+Base64.encodeToString(photo.toByteArray(),Base64.NO_WRAP));
        request.put("metrics",metrics.asJson());
        HttpsURLConnection c=(HttpsURLConnection)new URL(base+"/analyze").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(17000);c.setReadTimeout(65000);
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            c.setRequestProperty("Authorization","Bearer "+accessToken);
            c.setDoOutput(true);
            byte[] body=request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(OutputStream os=c.getOutputStream()){os.write(body);}
            int status=c.getResponseCode();
            if(status<200||status>=300)throw new IOException("Private AI server returned HTTP "+status);
            byte[] reply;
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>40000)throw new IOException("AI response too long");}
                reply=out.toByteArray();
            }
            JSONObject result=new JSONObject(new String(reply,java.nio.charset.StandardCharsets.UTF_8));
            EditRecipe recipe=EditRecipe.fromJson(result.getJSONObject("edits"));
            recipe.summary=result.optString("summary","AI assessed the photograph and proposed natural adjustments.");
            recipe.cloud=true;return recipe;
        } finally {c.disconnect();}
    }
}