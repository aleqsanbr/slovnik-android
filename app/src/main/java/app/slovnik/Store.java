package app.slovnik;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Files shared by the app screen and the reminders: the local copy of the dictionary
 * (snapshot.json, written by Словник itself) and changes waiting for the cloud (pending.json).
 * Everything that touches them holds {@link #LOCK}.
 */
final class Store {
    static final Object LOCK = new Object();

    private Store() { }

    static File snapshot(Context c) { return new File(c.getFilesDir(), "snapshot.json"); }
    static File pending(Context c) { return new File(c.getFilesDir(), "pending.json"); }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static String read(File f) {
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static void write(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    /** The saved dictionary, or null if Словник has never been opened online. Caller holds LOCK. */
    static JSONObject readSnapshot(Context c) {
        String t = read(snapshot(c));
        if (t.isEmpty()) return null;
        try { return new JSONObject(t); } catch (JSONException e) { return null; }
    }

    /** Queues a changed word for the cloud; the online page picks it up via takePending(). Caller holds LOCK. */
    static void queueWord(Context c, String id, JSONObject word) {
        String t = read(pending(c));
        JSONObject p;
        try { p = t.isEmpty() ? new JSONObject() : new JSONObject(t); } catch (JSONException e) { p = new JSONObject(); }
        try {
            JSONObject words = p.optJSONObject("words");
            if (words == null) { words = new JSONObject(); p.put("words", words); }
            words.put(id, word);
            write(pending(c), p.toString());
        } catch (JSONException ignored) { }
    }
}
