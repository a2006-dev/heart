package com.xinji.heartbeat;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
public class QRCodeGenerator {
    private static final int MIN_VERSION = 1;
    private static final int MAX_VERSION = 10;
    public static Bitmap generateQRCode(String content, int sizePx) {
        if (content == null || content.isEmpty()) return null;
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(sizePx / 14f);
        paint.setTextAlign(Paint.Align.CENTER);
        float cx = sizePx / 2f;
        float cy = sizePx / 2f;
        float r = sizePx * 0.4f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(sizePx * 0.03f);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText("📡", cx, cy - sizePx * 0.08f, paint);
        paint.setTextSize(sizePx / 20f);
        canvas.drawText("扫码连接", cx, cy + sizePx * 0.15f, paint);
        canvas.drawText(content, cx, cy + sizePx * 0.25f, paint);
        return bitmap;
    }
    public static Bitmap generateWithZXing(String content, int sizePx) {
        return null;
    }
}
