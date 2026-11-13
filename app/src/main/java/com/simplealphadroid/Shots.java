package com.simplealphadroid;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shots downloaded from the camera, kept on phone 1 in three subfolders of the folder the user picked
 * (Storage Access Framework tree, e.g. /A6400; persisted permission, so it survives an uninstall):
 * <pre>
 *   A6400/.nomedia            keeps it out of the media index (galleries, OneDrive camera upload)
 *   A6400/RAW/DSC05230.ARW    camera original
 *   A6400/JPEG/DSC05230.JPG   camera original (JPEG or RAW+JPEG modes)
 *   A6400/Preview/DSC05230.jpg medium JPEG: the ARW's embedded preview (A6400: 1616x1080) or a downscaled JPG
 * </pre>
 * Until a folder is picked, the same layout goes under Download/A6400 via MediaStore (no permission; only files
 * this install created are visible). Files are grouped by base name for the web viewer. Saving runs on its own
 * thread so the USB worker isn't held up.
 */
public final class Shots {
    static final String FALLBACK = "Download/A6400/";
    static final String RAW = "RAW", JPEG = "JPEG", PREVIEW = "Preview";
    static final int PREVIEW_MAX = 2048;
    private static final String PREFS = "shots", KEY_TREE = "tree";

    /** One stored file. */
    public static final class File {
        public String name, sub; public long size, time; public Uri uri;
    }

    /** One shot = the files sharing a base name. */
    public static final class Shot {
        public String base; public long time; public File raw, jpeg, preview;
        File original() { return raw != null ? raw : jpeg; }
    }

    private static Shots inst;
    public static synchronized Shots get(Context c) {
        if (inst == null) inst = new Shots(c.getApplicationContext());
        return inst;
    }

    private final ContentResolver cr;
    private final SharedPreferences prefs;
    private final ExecutorService saver = Executors.newSingleThreadExecutor();
    private final Map<String, String> dirIds = new HashMap<>(); // tree subfolder name -> document id
    public volatile int saved;

    private Shots(Context c) {
        cr = c.getContentResolver();
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        saver.execute(this::prepareTree); // folder picked earlier: make sure .nomedia and the subfolders exist
    }

    // ------------------------------------------------------------------ folder choice

    /** The picked folder, if we still hold a permission for it. */
    public Uri tree() {
        String s = prefs.getString(KEY_TREE, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : cr.getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return u;
        return null;
    }

    /** Called by MainActivity with the folder-picker result. */
    public synchronized void setTree(Uri u) {
        cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        prefs.edit().putString(KEY_TREE, u.toString()).apply();
        dirIds.clear();
        L.i("SHOT folder set: " + folderLabel());
        saver.execute(this::prepareTree);
    }

    /** Human-readable folder, e.g. "A6400" or "Download/A6400 (no folder picked)". */
    public String folderLabel() {
        Uri t = tree();
        if (t == null) return FALLBACK.replaceAll("/$", "") + " (no folder picked)";
        String id = DocumentsContract.getTreeDocumentId(t); // "primary:A6400"
        int c = id.indexOf(':');
        String path = c >= 0 ? id.substring(c + 1) : id;
        return (id.startsWith("primary:") ? "" : id.substring(0, Math.max(0, c)) + ": ") + (path.isEmpty() ? "/" : path);
    }

    /** .nomedia in the root and the three subfolders. */
    private void prepareTree() {
        Uri t = tree();
        if (t == null) return;
        try {
            String root = DocumentsContract.getTreeDocumentId(t);
            boolean noMedia = false;
            for (String[] k : children(t, root)) if (k[1].equals(".nomedia")) noMedia = true;
            if (!noMedia) {
                DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(t, root), "application/octet-stream", ".nomedia");
                L.i("SHOT .nomedia created in " + folderLabel());
            }
            for (String sub : new String[]{RAW, JPEG, PREVIEW}) dirId(t, sub, true);
        } catch (Exception e) { L.e("prepare folder", e); }
    }

    /** children of a tree document: {docId, name, mime, size, lastModified} */
    private List<String[]> children(Uri t, String docId) {
        List<String[]> out = new ArrayList<>();
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId);
        String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
        try (Cursor c = cr.query(kids, cols, null, null, null)) {
            while (c != null && c.moveToNext())
                out.add(new String[]{c.getString(0), c.getString(1), c.getString(2), String.valueOf(c.getLong(3)), String.valueOf(c.getLong(4))});
        } catch (Exception e) { L.e("list " + docId, e); }
        return out;
    }

    /** Document id of a subfolder of the tree root; created if asked. null if missing. */
    private synchronized String dirId(Uri t, String sub, boolean create) throws IOException {
        String id = dirIds.get(sub);
        if (id != null) return id;
        String root = DocumentsContract.getTreeDocumentId(t);
        for (String[] k : children(t, root))
            if (Document.MIME_TYPE_DIR.equals(k[2]) && k[1].equalsIgnoreCase(sub)) { dirIds.put(sub, k[0]); return k[0]; }
        if (!create) return null;
        Uri d = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(t, root), Document.MIME_TYPE_DIR, sub);
        if (d == null) throw new IOException("cannot create folder " + sub);
        id = DocumentsContract.getDocumentId(d);
        dirIds.put(sub, id);
        return id;
    }

    // ------------------------------------------------------------------ saving

    /** Queue a file downloaded from the camera. Returns at once. */
    public void saveAsync(String name, byte[] data) {
        saver.execute(() -> {
            try { saveNow(name, data); } catch (Throwable e) { L.e("SHOT save " + name, e); }
        });
    }

    /** Save on the calling thread (card sync: keeps at most one 25 MB file in memory). Returns the stored name. */
    public String saveNow(String name, byte[] data) throws IOException {
        long t0 = System.currentTimeMillis();
        String sub = isJpegName(name) || name.toLowerCase(Locale.US).matches(".*\\.(hif|heif)$") ? JPEG : RAW;
        String stored = write(sub, name, data);
        String pvNote = "kept";
        String base = base(stored);
        if (findFile(PREVIEW, base + ".jpg") == null) { // RAW+JPEG: the first of the pair makes it
            byte[] pv = makePreview(data);
            pvNote = pv == null ? "none" : pv.length + " B";
            if (pv != null) write(PREVIEW, base + ".jpg", pv);
        }
        saved++;
        L.i("SHOT saved " + sub + "/" + stored + " in " + folderLabel() + " (" + data.length + " B) preview=" + pvNote
                + " in " + (System.currentTimeMillis() - t0) + "ms");
        return stored;
    }

    /**
     * Identity of a photo across naming schemes: the card calls it "_DSC5231.ARW" (or "DSC05231.ARW" in sRGB),
     * a PC Remote download "DSC05231.ARW". Key = extension + file number + size; a " (1)" clash suffix is ignored.
     */
    static String key(String name, long size) {
        String b = base(name).replaceAll(" \\(\\d+\\)$", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\D*$").matcher(b);
        String num = m.find() ? String.valueOf(Long.parseLong(m.group(1))) : b;
        String ext = name.lastIndexOf('.') >= 0 ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.US) : "";
        if (ext.equals("jpeg")) ext = "jpg";
        return ext + ":" + num + ":" + size;
    }

    /** Keys of every original on the phone (RAW/ and JPEG/), for the card sync. */
    public java.util.Set<String> originalKeys() {
        java.util.Set<String> k = new java.util.HashSet<>();
        for (File f : files()) if (!PREVIEW.equals(f.sub)) k.add(key(f.name, f.size));
        return k;
    }

    /** Write a file into a subfolder; returns the name actually stored (providers rename on a clash). */
    private String write(String sub, String name, byte[] data) throws IOException {
        Uri t = tree();
        if (t != null) {
            // octet-stream for non-JPEG: the provider would otherwise append an extension derived from the MIME type
            String mime = isJpegName(name) ? "image/jpeg" : "application/octet-stream";
            Uri doc = null;
            try {
                doc = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(t, dirId(t, sub, true)), mime, name);
            } catch (Exception e) { L.w("createDocument in " + sub + " failed (" + e + "); looking the folder up again"); }
            if (doc == null) {
                // the subfolder was deleted (e.g. in a file manager) while we still had its id cached: look it up / recreate
                synchronized (this) { dirIds.remove(sub); }
                doc = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(t, dirId(t, sub, true)), mime, name);
            }
            if (doc == null) throw new IOException("createDocument failed in " + folderLabel() + "/" + sub);
            try (OutputStream o = cr.openOutputStream(doc)) {
                if (o == null) throw new IOException("no output stream");
                o.write(data);
            }
            return displayName(doc, name);
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime(name));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, FALLBACK + sub + "/");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("MediaStore insert failed");
        try (OutputStream o = cr.openOutputStream(u)) {
            if (o == null) throw new IOException("no output stream");
            o.write(data);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, done, null, null);
        return displayName(u, name);
    }

    private String displayName(Uri u, String def) {
        try (Cursor c = cr.query(u, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return def;
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (isJpegName(n)) return "image/jpeg";
        if (n.endsWith(".arw")) return "image/x-sony-arw";
        if (n.endsWith(".hif") || n.endsWith(".heif")) return "image/heif";
        return "application/octet-stream";
    }

    static boolean isJpegName(String n) { n = n.toLowerCase(Locale.US); return n.endsWith(".jpg") || n.endsWith(".jpeg"); }

    static String base(String name) { int d = name.lastIndexOf('.'); return d > 0 ? name.substring(0, d) : name; }

    // ------------------------------------------------------------------ listing

    /** Every stored file: the three subfolders plus loose files in the root (classified by extension). */
    public List<File> files() {
        List<File> out = new ArrayList<>();
        Uri t = tree();
        if (t != null) {
            String root = DocumentsContract.getTreeDocumentId(t);
            for (String[] k : children(t, root)) {
                if (Document.MIME_TYPE_DIR.equals(k[2])) {
                    String sub = k[1].equalsIgnoreCase(RAW) ? RAW : k[1].equalsIgnoreCase(JPEG) ? JPEG : k[1].equalsIgnoreCase(PREVIEW) ? PREVIEW : null;
                    if (sub == null) continue;
                    synchronized (this) { dirIds.put(sub, k[0]); }
                    for (String[] f : children(t, k[0])) if (!Document.MIME_TYPE_DIR.equals(f[2])) out.add(file(t, f, sub));
                } else if (!k[1].startsWith(".")) out.add(file(t, k, null));
            }
        } else {
            String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.RELATIVE_PATH};
            try (Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{FALLBACK + "%"}, null)) {
                while (c != null && c.moveToNext()) {
                    File f = new File();
                    f.name = c.getString(1); f.size = c.getLong(2); f.time = c.getLong(3) * 1000;
                    f.uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                    String rel = c.getString(4).substring(FALLBACK.length()).replace("/", "");
                    f.sub = rel.equalsIgnoreCase(RAW) ? RAW : rel.equalsIgnoreCase(JPEG) ? JPEG : rel.equalsIgnoreCase(PREVIEW) ? PREVIEW : null;
                    out.add(f);
                }
            } catch (Exception e) { L.e("list downloads", e); }
        }
        for (File f : out) if (f.sub == null) f.sub = isJpegName(f.name) ? JPEG : RAW; // loose root files (older builds)
        return out;
    }

    private File file(Uri t, String[] k, String sub) {
        File f = new File();
        f.name = k[1]; f.sub = sub; f.size = Long.parseLong(k[3]); f.time = Long.parseLong(k[4]);
        f.uri = DocumentsContract.buildDocumentUriUsingTree(t, k[0]);
        return f;
    }

    private File findFile(String sub, String name) {
        for (File f : files()) if (f.sub.equals(sub) && f.name.equalsIgnoreCase(name)) return f;
        return null;
    }

    /** Shots grouped by base name, newest first. */
    public List<Shot> list(int max) {
        Map<String, Shot> m = new HashMap<>();
        for (File f : files()) {
            String n = f.name.toLowerCase(Locale.US);
            if (!(n.endsWith(".arw") || isJpegName(n) || n.endsWith(".hif") || n.endsWith(".heif"))) continue;
            Shot s = m.get(base(f.name));
            if (s == null) { s = new Shot(); s.base = base(f.name); m.put(s.base, s); }
            if (PREVIEW.equals(f.sub)) s.preview = f; else if (JPEG.equals(f.sub)) s.jpeg = f; else s.raw = f;
            if (!PREVIEW.equals(f.sub)) s.time = Math.max(s.time, f.time);
        }
        List<Shot> out = new ArrayList<>();
        for (Shot s : m.values()) if (s.original() != null) out.add(s); // a lone preview isn't a shot
        out.sort((a, b) -> a.time != b.time ? Long.compare(b.time, a.time) : b.base.compareTo(a.base));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    public Shot find(String base) {
        if (base == null) return null;
        for (Shot s : list(Integer.MAX_VALUE)) if (s.base.equals(base)) return s;
        return null;
    }

    public InputStream open(File f) throws IOException {
        InputStream in = cr.openInputStream(f.uri);
        if (in == null) throw new IOException("cannot open " + f.name);
        return in;
    }

    /** Preview bytes for a shot; built from the original (and saved to Preview/) if missing. */
    public byte[] preview(Shot s) throws IOException {
        if (s.preview != null) try (InputStream in = open(s.preview)) { return readAll(in, (int) s.preview.size); }
        File o = s.original();
        if (o == null) return null;
        byte[] data;
        try (InputStream in = open(o)) { data = readAll(in, (int) o.size); }
        byte[] pv = makePreview(data);
        if (pv != null) try { write(PREVIEW, s.base + ".jpg", pv); } catch (IOException e) { L.e("save preview", e); }
        return pv;
    }

    static byte[] readAll(InputStream in, int hint) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(8192, hint));
        byte[] b = new byte[64 * 1024]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ preview extraction

    static byte[] makePreview(byte[] d) {
        if (d == null || d.length < 16) return null;
        byte[] jpg = null;
        if (isJpeg(d, 0)) jpg = d;
        else if (isTiff(d)) jpg = tiffPreview(d);
        if (jpg == null) jpg = largestJpegByScan(d);
        if (jpg == null) return null;
        return fit(jpg);
    }

    static boolean isJpeg(byte[] d, int o) {
        return o + 3 < d.length && (d[o] & 0xFF) == 0xFF && (d[o + 1] & 0xFF) == 0xD8 && (d[o + 2] & 0xFF) == 0xFF;
    }

    static boolean isTiff(byte[] d) {
        return (d[0] == 'I' && d[1] == 'I' && d[2] == 42 && d[3] == 0) || (d[0] == 'M' && d[1] == 'M' && d[2] == 0 && d[3] == 42);
    }

    /** Walk the TIFF IFD chain (and SubIFDs) for JPEGInterchangeFormat (0x0201/0x0202); take the largest. */
    static byte[] tiffPreview(byte[] d) {
        boolean le = d[0] == 'I';
        int best = -1, bestLen = 0;
        List<Long> todo = new ArrayList<>();
        todo.add(u32(d, 4, le));
        for (int guard = 0; guard < 16 && !todo.isEmpty(); guard++) {
            long ifd = todo.remove(0);
            if (ifd <= 0 || ifd + 2 > d.length) continue;
            int n = u16(d, (int) ifd, le);
            long off = -1, len = -1;
            for (int i = 0; i < n; i++) {
                int e = (int) ifd + 2 + 12 * i;
                if (e + 12 > d.length) break;
                int tag = u16(d, e, le), type = u16(d, e + 2, le);
                long cnt = u32(d, e + 4, le), val = type == 3 ? u16(d, e + 8, le) : u32(d, e + 8, le);
                if (tag == 0x0201) off = val;
                else if (tag == 0x0202) len = val;
                else if (tag == 0x014A) { // SubIFDs
                    if (cnt == 1) todo.add(val);
                    else for (int k = 0; k < cnt && val + 4 * k + 4 <= d.length; k++) todo.add(u32(d, (int) val + 4 * k, le));
                }
            }
            if (off > 0 && len > 0 && off + len <= d.length && len > bestLen && isJpeg(d, (int) off)) { best = (int) off; bestLen = (int) len; }
            long next = (int) ifd + 2 + 12L * n;
            if (next + 4 <= d.length) todo.add(u32(d, (int) next, le));
        }
        if (best < 0) return null;
        byte[] j = new byte[bestLen];
        System.arraycopy(d, best, j, 0, bestLen);
        return j;
    }

    /** Fallback: the longest FFD8FF..FFD9 run in the file. */
    static byte[] largestJpegByScan(byte[] d) {
        int bestS = -1, bestL = 0;
        for (int i = 0; i + 3 < d.length; i++) {
            if (!isJpeg(d, i)) continue;
            for (int j = i + 2; j + 1 < d.length; j++) {
                if ((d[j] & 0xFF) == 0xFF && (d[j + 1] & 0xFF) == 0xD9) {
                    int l = j + 2 - i;
                    if (l > bestL) { bestL = l; bestS = i; }
                    i = j + 1;
                    break;
                }
            }
        }
        if (bestS < 0 || bestL < 1024) return null;
        byte[] r = new byte[bestL];
        System.arraycopy(d, bestS, r, 0, bestL);
        return r;
    }

    /** Pass through if the long side is <= PREVIEW_MAX, else downscale and re-encode. */
    static byte[] fit(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        int longSide = Math.max(o.outWidth, o.outHeight);
        if (longSide <= 0) return null;
        if (longSide <= PREVIEW_MAX) return jpg;
        int sample = 1;
        while (longSide / (sample * 2) >= PREVIEW_MAX * 3 / 4) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o2);
        if (b == null) return null;
        int lw = Math.max(b.getWidth(), b.getHeight());
        if (lw > PREVIEW_MAX) {
            float s = PREVIEW_MAX / (float) lw;
            Bitmap sc = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            b.recycle();
            b = sc;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 82, bo);
        b.recycle();
        return bo.toByteArray();
    }

    static int u16(byte[] d, int o, boolean le) {
        return le ? (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 : (d[o] & 0xFF) << 8 | (d[o + 1] & 0xFF);
    }

    static long u32(byte[] d, int o, boolean le) {
        if (o + 4 > d.length) return -1;
        return le ? ((d[o] & 0xFFL) | (d[o + 1] & 0xFFL) << 8 | (d[o + 2] & 0xFFL) << 16 | (d[o + 3] & 0xFFL) << 24)
                : ((d[o] & 0xFFL) << 24 | (d[o + 1] & 0xFFL) << 16 | (d[o + 2] & 0xFFL) << 8 | (d[o + 3] & 0xFFL));
    }
}
