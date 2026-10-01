package com.winlator.socialhub;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal async image loader with memory cache. Avoids adding Glide/Picasso
 * to keep the APK small; supports http(s) URLs and local files.
 */
public final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    static {
        int maxMemKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheKb = Math.max(4 * 1024, maxMemKb / 8);
        CACHE = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private ImageLoader() {}

    public static void load(String url, ImageView target) {
        load(url, target, 0, 0);
    }

    public static void load(String url, ImageView target, int reqWidth, int reqHeight) {
        if (target == null) return;
        if (url == null || url.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setTag(url);
        target.setImageDrawable(null);
        WeakReference<ImageView> ref = new WeakReference<>(target);
        POOL.execute(() -> {
            try {
                Bitmap bitmap = download(url, reqWidth, reqHeight);
                if (bitmap != null) CACHE.put(url, bitmap);
                ImageView view = ref.get();
                if (view != null && bitmap != null) {
                    final Bitmap result = bitmap;
                    view.post(() -> {
                        Object tag = view.getTag();
                        if (url.equals(tag)) view.setImageBitmap(result);
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private static Bitmap download(String url, int reqWidth, int reqHeight) throws Exception {
        if (url.startsWith("file://")) {
            String path = url.substring("file://".length());
            return decodeFile(path, reqWidth, reqHeight);
        }
        if (url.startsWith("/") && !url.startsWith("//")) {
            return decodeFile(url, reqWidth, reqHeight);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return null;
            try (InputStream in = connection.getInputStream()) {
                if (reqWidth > 0 && reqHeight > 0) {
                    byte[] bytes = readAll(in);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight);
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                }
                return BitmapFactory.decodeStream(in);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Bitmap decodeFile(String path, int reqWidth, int reqHeight) {
        if (reqWidth <= 0 || reqHeight <= 0) return BitmapFactory.decodeFile(path);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight);
        return BitmapFactory.decodeFile(path, opts);
    }

    private static int calcSampleSize(int w, int h, int reqW, int reqH) {
        int sample = 1;
        while ((w / (sample * 2)) >= reqW && (h / (sample * 2)) >= reqH) sample *= 2;
        return sample;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
