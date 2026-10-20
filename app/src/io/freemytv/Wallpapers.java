package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The home screen's background (Home Settings > Wallpaper): "picks" = the first recommendation's photo, blurred (the
 * original look); "plain" = the light or dark gradient; or one of the images in assets/wallpapers, shown as it is
 * (center-cropped to the screen). A dark image keeps the home screen in its dark look so the text stays readable.
 */
final class Wallpapers {
    private Wallpapers() {}

    static final String PICKS = "picks", PLAIN = "plain";
    private static final String DIR = "wallpapers";

    /** Every choice: the two built-in ones, then the images, by name. */
    static List<String> ids(Context c) {
        List<String> out = new ArrayList<>(Arrays.asList(PICKS, PLAIN));
        try {
            String[] files = c.getAssets().list(DIR);
            if (files != null) {
                Arrays.sort(files);
                for (String f : files) if (isImageFile(f)) out.add(f);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    static boolean isImage(String id) {
        return !PICKS.equals(id) && !PLAIN.equals(id);
    }

    private static boolean isImageFile(String f) {
        String s = f.toLowerCase(Locale.ROOT);
        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp");
    }

    /** "black-sand.jpg" → "Black Sand". */
    static String name(String id) {
        if (PICKS.equals(id)) return Ui.tr("Recomendações", "Recommendations", "Recomendaciones");
        if (PLAIN.equals(id)) return Ui.tr("Liso", "Plain", "Liso");
        String base = id.replaceAll("\\.[^.]+$", "").replace('-', ' ').replace('_', ' ').trim();
        StringBuilder sb = new StringBuilder();
        for (String word : base.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /** The image at w × h (center-cropped, filtered), or null if it's missing or can't be read. */
    static Bitmap load(Context c, String id, int w, int h) {
        if (!isImage(id) || w <= 0 || h <= 0) return null;
        try (InputStream in = c.getAssets().open(DIR + "/" + id)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap src;
            try (InputStream again = c.getAssets().open(DIR + "/" + id)) {
                src = BitmapFactory.decodeStream(again, null, d);
            }
            if (src == null) return null;
            float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
            float cw = w / scale, ch = h / scale;
            float left = (src.getWidth() - cw) / 2, top = (src.getHeight() - ch) / 2;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, new Rect(Math.round(left), Math.round(top), Math.round(left + cw), Math.round(top + ch)),
                    new RectF(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            src.recycle();
            return out;
        } catch (IOException | OutOfMemoryError e) {
            AppLog.w("wallpaper " + id + ": " + e.getMessage());
            return null;
        }
    }

    /** The image's pixels at a small size (what the frosted glass shows), ARGB. */
    static int[] pixels(Bitmap b, int w, int h) {
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != b) small.recycle();
        return px;
    }

    /** Dark enough that white text reads well on it (average luminance under 40%). */
    static boolean isDark(int[] px) {
        double sum = 0;
        for (int p : px) sum += 0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255);
        return px.length > 0 && sum / px.length / 255 < 0.4;
    }
}
