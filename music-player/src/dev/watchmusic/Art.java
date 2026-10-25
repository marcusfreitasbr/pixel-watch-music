package dev.watchmusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Album covers, from MediaStore's thumbnail of a song: its embedded picture, or a cover.jpg / folder.jpg
 * beside it. Loaded off the main thread and cached; songs without art are remembered so they aren't retried.
 */
final class Art {
    interface Callback { void onArt(Bitmap art); } // null when the song has no cover

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private static final Set<String> missing = new HashSet<>(); // main thread only
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Art() {}

    /** The cover at up to {@code px} square; the callback runs on the main thread, at once when cached. */
    static void load(Context context, Library.Track t, int px, Callback cb) {
        String key = t.id + "@" + px;
        Bitmap hit = cache.get(key);
        if (hit != null || missing.contains(key)) {
            cb.onArt(hit);
            return;
        }
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            Bitmap b = null;
            try {
                b = app.getContentResolver().loadThumbnail(t.uri(), new Size(px, px), null);
            } catch (Exception e) {
                // IOException when there's no cover; anything else just means no art either
            }
            Bitmap art = b;
            main.post(() -> {
                if (art != null) cache.put(key, art); else missing.add(key);
                cb.onArt(art);
            });
        });
    }

    /** A rounded cover in a list or header, with a grey note placeholder until (or unless) art arrives. */
    static void show(ImageView v, Library.Track t, int px) {
        v.setTag(t.id); // the view may be recycled for another album before the art arrives
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setImageResource(R.drawable.ic_note);
        v.setColorFilter(Ui.SECONDARY);
        load(v.getContext(), t, px, art -> {
            if (art == null || !Long.valueOf(t.id).equals(v.getTag())) return;
            v.clearColorFilter();
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setImageBitmap(art);
        });
    }
}
