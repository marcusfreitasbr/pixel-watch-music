package dev.watchmusic;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny HTTP server for adding music from a browser on the same Wi-Fi (PC or iPhone).
 * Serves the upload page, lists the library, saves uploads into Music/ through MediaStore (so they're
 * indexed with their tags straight away), and deletes. Every /api call must carry the code shown on
 * the watch in an X-Code header. After a few wrong codes the watch shows a new one and the server
 * refuses everything for a while, so the 4 digits can't simply be tried one after another.
 */
final class UploadServer {
    interface Listener {
        /** Short progress line for the watch screen ("Receiving Caramel", "Added Caramel"). */
        void onStatus(String status);

        /** Deleting files the app didn't create (e.g. pushed with adb) needs the user's OK on the watch. */
        void onNeedsConfirm(PendingIntent request);

        /** Too many wrong codes: show this new one. */
        void onNewCode(String code);
    }

    static final int PORT = 8080;
    private static final int MAX_WRONG = 5;
    private static final long LOCKOUT_MS = 30_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Uri AUDIO = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    private static final Map<String, String> TYPES = Map.of(
            "mp3", "audio/mpeg", "m4a", "audio/mp4", "aac", "audio/aac", "flac", "audio/flac",
            "ogg", "audio/ogg", "opus", "audio/ogg", "wav", "audio/wav");

    /** A failed request, answered with this status and message. */
    private static final class HttpError extends Exception {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Context context;
    private final Listener listener;
    private String code; // these three: guarded by this
    private int wrong;
    private long lockedUntil;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket server;

    UploadServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.code = newCode();
        this.listener = listener;
    }

    synchronized String code() {
        return code;
    }

    private static String newCode() {
        return String.format(Locale.ROOT, "%04d", RANDOM.nextInt(10_000));
    }

    /** Lets the right code through. The MAX_WRONG-th wrong one changes the code and locks everyone out a while. */
    private void checkCode(String given) throws HttpError {
        String fresh;
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            if (now < lockedUntil) throw tooMany(lockedUntil - now);
            if (code.equals(given)) return;
            if (++wrong < MAX_WRONG) throw new HttpError(403, "Wrong code");
            wrong = 0;
            fresh = code = newCode();
            lockedUntil = now + LOCKOUT_MS;
        }
        Log.i(PlayerService.TAG, "upload server: too many wrong codes, new code shown");
        listener.onNewCode(fresh);
        throw tooMany(LOCKOUT_MS);
    }

    private static HttpError tooMany(long waitMs) {
        return new HttpError(429, "Too many wrong codes. The watch has a new one; try it in "
                + (waitMs + 999) / 1000 + " s.");
    }

    void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true); // the screen may be reopened right after closing
        server.bind(new InetSocketAddress(PORT));
        Thread t = new Thread(this::acceptLoop, "upload-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                break; // closed by stop()
            }
        }
    }

    // --- HTTP ---

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String[] request = readLine(in).split(" ");
            if (request.length < 2) return;
            Map<String, String> headers = new HashMap<>();
            for (String line; !(line = readLine(in)).isEmpty(); ) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
            String target = request[1];
            int q = target.indexOf('?');
            String route = request[0] + " " + (q < 0 ? target : target.substring(0, q));
            Map<String, String> query = parseQuery(q < 0 ? "" : target.substring(q + 1));
            long length = Long.parseLong(headers.getOrDefault("content-length", "0"));

            try {
                if (route.equals("GET /")) {
                    respond(out, 200, "text/html; charset=utf-8", page());
                    return;
                }
                if (!route.contains(" /api/")) throw new HttpError(404, "Not found");
                checkCode(headers.get("x-code"));
                String json;
                switch (route) {
                    case "GET /api/songs": json = songs(); break;
                    case "GET /api/has": json = "{\"has\":" + has(query.get("path")) + "}"; break;
                    case "POST /api/upload": json = upload(in, length, query.get("path")); break;
                    case "POST /api/delete": json = delete(new String(readBody(in, length), StandardCharsets.UTF_8)); break;
                    default: throw new HttpError(404, "Not found");
                }
                respond(out, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
            } catch (HttpError e) {
                respond(out, e.status, "application/json",
                        ("{\"error\":" + quote(e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(PlayerService.TAG, "upload server", e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1 && b != '\n'; ) {
            if (b != '\r') line.write(b);
            if (line.size() > 16_384) throw new IOException("header too long");
        }
        return line.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, long length) throws IOException {
        if (length > 1_000_000) throw new IOException("body too large");
        byte[] body = new byte[(int) length];
        for (int off = 0, n; off < body.length; off += n) {
            if ((n = in.read(body, off, body.length - off)) < 0) throw new IOException("body cut short");
        }
        return body;
    }

    private static Map<String, String> parseQuery(String q) throws IOException {
        Map<String, String> map = new HashMap<>();
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return map;
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private byte[] page() throws IOException {
        try (InputStream in = context.getResources().openRawResource(R.raw.upload)) {
            return in.readAllBytes();
        }
    }

    // --- API ---

    private String songs() {
        StatFs disk = new StatFs(Environment.getExternalStorageDirectory().getPath());
        StringBuilder json = new StringBuilder("{\"free\":").append(disk.getAvailableBytes())
                .append(",\"total\":").append(disk.getTotalBytes())
                .append(",\"songs\":[");
        List<Library.Track> all = Library.load(context);
        for (int i = 0; i < all.size(); i++) {
            Library.Track t = all.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(t.id)
                    .append(",\"title\":").append(quote(t.title))
                    .append(",\"artist\":").append(quote(t.artist))
                    .append(",\"album\":").append(quote(t.album))
                    .append(",\"number\":").append(t.number)
                    .append(",\"duration\":").append(t.durationMs).append('}');
        }
        return json.append("]}").toString();
    }

    /** "Album/01 Song.flac" → { "Music/Album/", "01 Song.flac" }, with unsafe path parts dropped. */
    private static String[] target(String path) throws HttpError {
        if (path == null) throw new HttpError(400, "Missing path");
        List<String> parts = new ArrayList<>();
        for (String p : path.split("/")) {
            p = p.replaceAll("[\\\\:*?\"<>|]", "_").trim();
            if (!p.isEmpty() && !p.equals(".") && !p.equals("..")) parts.add(p);
        }
        if (parts.isEmpty()) throw new HttpError(400, "Missing file name");
        String name = parts.remove(parts.size() - 1);
        String dir = Environment.DIRECTORY_MUSIC + "/";
        for (String p : parts) dir += p + "/";
        return new String[] { dir, name };
    }

    /** Whether a song already sits at that path, so the page can skip re-sending it. */
    private boolean has(String path) throws HttpError {
        String[] t = target(path);
        try (Cursor c = context.getContentResolver().query(AUDIO, new String[] { MediaStore.MediaColumns._ID },
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                t, null)) {
            return c != null && c.getCount() > 0;
        }
    }

    private String upload(InputStream in, long length, String path) throws HttpError, IOException {
        String[] t = target(path);
        String name = t[1];
        String mime = TYPES.get(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        if (mime == null) throw new HttpError(415, "Not a music file: " + name);

        listener.onStatus("Receiving " + name);
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, t[0]);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1); // hidden until complete
        Uri uri = resolver.insert(AUDIO, values);
        if (uri == null) throw new HttpError(500, "Couldn't create " + name);
        try (OutputStream file = resolver.openOutputStream(uri)) {
            byte[] buf = new byte[256 * 1024];
            for (long left = length; left > 0; ) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("upload cut short");
                file.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            listener.onStatus("Upload of " + name + " failed");
            throw e;
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0); // publishing it makes MediaStore read the tags
        resolver.update(uri, values, null, null);
        listener.onStatus("Added " + name);
        Log.i(PlayerService.TAG, "uploaded " + t[0] + name + " (" + length + " bytes)");
        return "{\"ok\":true}";
    }

    /** Body: comma-separated song ids. Ours are deleted at once; others need a confirmation on the watch. */
    private String delete(String body) {
        ContentResolver resolver = context.getContentResolver();
        List<Uri> needOk = new ArrayList<>();
        int deleted = 0;
        for (String s : body.split(",")) {
            Uri uri;
            try {
                uri = ContentUris.withAppendedId(AUDIO, Long.parseLong(s.trim()));
            } catch (NumberFormatException e) {
                continue;
            }
            try {
                deleted += resolver.delete(uri, null, null);
            } catch (SecurityException e) {
                needOk.add(uri);
            }
        }
        if (!needOk.isEmpty()) listener.onNeedsConfirm(MediaStore.createDeleteRequest(resolver, needOk));
        listener.onStatus(deleted > 0 ? "Deleted " + deleted + (deleted == 1 ? " song" : " songs")
                : needOk.isEmpty() ? "Nothing deleted" : "Confirm on the watch");
        return "{\"deleted\":" + deleted + ",\"confirm\":" + needOk.size() + "}";
    }

    private static String quote(String s) {
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') q.append('\\').append(c);
            else if (c < 0x20) q.append(String.format("\\u%04x", (int) c));
            else q.append(c);
        }
        return q.append('"').toString();
    }
}
