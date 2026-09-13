package net.elfradio.d31bootstrap.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import org.json.JSONObject;

/** 沿用D22179的小图上限和缩放阶梯；原JPEG保持不变并由既有上传器确认。 */
final class PreparedPhotoPreview {
    static JSONObject create(JSONObject receipt)throws Exception {
        File file=MediaFiles.plain(new File(receipt.getString("path")));
        if(file.length()<4||file.length()>256*1024||file.length()!=receipt.getLong("bytes")
                ||!MediaFiles.hash(file).equals(receipt.getString("sha256")))return null;
        byte[] original=new byte[(int)file.length()];
        try(java.io.DataInputStream in=new java.io.DataInputStream(new FileInputStream(file))){in.readFully(original);}
        byte[] small=shrink(original);
        if(small==null)return null;
        return new JSONObject().put("type","photo_preview").put("jpeg",Base64.encodeToString(small,Base64.NO_WRAP))
                .put("captured_at",receipt.getLong("captured_at"));
    }
    static byte[] shrink(byte[] original) {
        if(original==null||original.length<4||original.length>256*1024||(original[0]&255)!=255||(original[1]&255)!=216)return null;
        if(original.length<=66000)return original;
        Bitmap image=null;
        try {
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(original,0,original.length,options);
            if(options.outWidth<=0||options.outHeight<=0||options.outWidth>8192||options.outHeight>8192)return null;
            options.inJustDecodeBounds=false;options.inSampleSize=1;
            while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1280)options.inSampleSize*=2;
            image=BitmapFactory.decodeByteArray(original,0,original.length,options);if(image==null)return null;
            for(int edge:new int[]{640,480,320,160}) {
                int longest=Math.max(image.getWidth(),image.getHeight());
                if(longest>edge) {
                    Bitmap smaller=Bitmap.createScaledBitmap(image,Math.max(1,image.getWidth()*edge/longest),Math.max(1,image.getHeight()*edge/longest),true);
                    if(smaller!=image)image.recycle();image=smaller;
                }
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                if(image.compress(Bitmap.CompressFormat.JPEG,65,output)&&output.size()<=66000)return output.toByteArray();
            }
            return null;
        }catch(RuntimeException failure){return null;}
        finally{if(image!=null)image.recycle();}
    }
}
