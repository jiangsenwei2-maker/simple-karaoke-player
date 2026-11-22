package com.local.karaoke.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.local.karaoke.lyric.LrcParser;
import com.local.karaoke.lyric.LyricStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 曲库仓库：扫描目录、拆分歌手/歌名、读取时长、自动匹配并保存 LRC、重命名。
 */
public final class LibraryRepository {

    private static final String TAG = "KaraokeRepo";
    private static final String DURATION_PREFS = "karaoke_durations";
    private static final String DURATION_KEY = "data";

    public interface Listener {
        default void onScanStarted() {
        }

        default void onSongsChanged(List<Song> songs) {
        }

        default void onLyricsUpdated(Song song) {
        }
    }

    public interface RenameCallback {
        void onResult(boolean success, String message);
    }

    private static volatile LibraryRepository instance;

    public static LibraryRepository get(Context context) {
        if (instance == null) {
            synchronized (LibraryRepository.class) {
                if (instance == null) {
                    instance = new LibraryRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context app;
    private final ContentResolver resolver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService scanExec = Executors.newSingleThreadExecutor();
    private final ExecutorService lyricExec = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final Object durationLock = new Object();
    private final Map<String, Long> durationCache = new HashMap<>();
    private final Set<String> lyricAttempted = ConcurrentHashMap.newKeySet();

    private volatile List<Song> songs = Collections.emptyList();
    private volatile Map<String, List<Uri>> dirImages = Collections.emptyMap();
    private volatile List<Uri> rootImages = Collections.emptyList();
    private volatile boolean scanning = false;
    private volatile boolean scanQueued = false;
    private volatile boolean hasScanned = false;
    private volatile int lyricGeneration = 0;

    private LibraryRepository(Context app) {
        this.app = app;
        this.resolver = app.getContentResolver();
    }

    // ------------------------------------------------------------------ 对外接口

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public List<Song> getSongs() {
        return songs;
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean hasScanned() {
        return hasScanned;
    }

    public List<Uri> getImagesForDir(String dirId) {
        List<Uri> own = dirImages.get(dirId);
        if (own != null && !own.isEmpty()) return own;
        return rootImages;
    }

    public void scan() {
        if (scanning) {
            scanQueued = true;
            return;
        }
        scanning = true;
        final int generation = ++lyricGeneration;
        lyricAttempted.clear();
        main.post(this::notifyScanStarted);

        final String treeStr = Prefs.getTreeUri(app);
        scanExec.execute(() -> {
            List<Song> result = new ArrayList<>();
            Map<String, List<Uri>> imageMap = new HashMap<>();
            List<Uri> rootImgs = new ArrayList<>();
            try {
                if (treeStr != null) {
                    Uri tree = Uri.parse(treeStr);
                    synchronized (durationLock) {
                        loadDurationCache();
                    }
                    String rootId = DocumentsContract.getTreeDocumentId(tree);
                    walk(tree, rootId, result, imageMap, rootImgs, 0);
                }
            } catch (Throwable t) {
                Log.w(TAG, "scan failed", t);
            }

            result.sort(COMPARATOR);
            synchronized (durationLock) {
                saveDurationCache();
            }

            Map<String, List<Uri>> frozenImages = new HashMap<>();
            for (Map.Entry<String, List<Uri>> e : imageMap.entrySet()) {
                frozenImages.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
            }
            dirImages = Collections.unmodifiableMap(frozenImages);
            rootImages = Collections.unmodifiableList(rootImgs);
            songs = Collections.unmodifiableList(result);
            scanning = false;
            hasScanned = true;

            main.post(() -> {
                notifySongsChanged();
                startLyricBackfill(generation);
                if (scanQueued) {
                    scanQueued = false;
                    scan();
                }
            });
        });
    }

    public void renameSong(Song song, String newArtist, String newTitle, RenameCallback callback) {
        lyricExec.execute(() -> {
            String newBase = newArtist + "-" + newTitle;
            String oldFileName = song.fileName;
            Uri newUri = null;
            try {
                newUri = DocumentsContract.renameDocument(resolver, song.uri, newBase + ".mp3");
            } catch (Throwable t) {
                Log.w(TAG, "rename via DocumentsContract failed", t);
            }
            if (newUri == null) {
                try {
                    DocumentFile file = DocumentFile.fromSingleUri(app, song.uri);
                    if (file != null && file.renameTo(newBase + ".mp3")) {
                        newUri = file.getUri();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "rename via DocumentFile failed", t);
                }
            }
            if (newUri == null) {
                String msg = "重命名失败，请检查文件权限";
                main.post(() -> callback.onResult(false, msg));
                return;
            }

            song.uri = newUri;
            song.fileName = newBase + ".mp3";

            if (song.lrcUri != null) {
                try {
                    Uri newLrc = DocumentsContract.renameDocument(
                            resolver, song.lrcUri, newBase + ".lrc");
                    if (newLrc != null) song.lrcUri = newLrc;
                } catch (Throwable t) {
                    Log.w(TAG, "rename lrc failed", t);
                }
            }

            synchronized (durationLock) {
                durationCache.remove(durationKey(song.docId, oldFileName));
                if (song.durationMs > 0) {
                    durationCache.put(durationKey(song.docId, song.fileName), song.durationMs);
                }
                saveDurationCache();
            }

            song.artist = newArtist;
            song.title = newTitle;
            song.updatePinyin();

            main.post(() -> {
                notifySongsChanged();
                callback.onResult(true, null);
            });

            // 重命名后重新联网匹配最新歌词（覆盖旧 LRC）
            try {
                lyricAttempted.remove(song.docId);
                fetchAndSave(song, true);
            } catch (Throwable t) {
                Log.w(TAG, "refresh lyric after rename failed", t);
            }
        });
    }

    // ------------------------------------------------------------------ 扫描实现

    private static final class Entry {
        final String id;
        final String name;

        Entry(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private void walk(Uri tree, String dirId, List<Song> out,
                      Map<String, List<Uri>> imageMap, List<Uri> rootImgs, int depth) {
        if (depth > 20) return;

        List<Entry> dirs = new ArrayList<>();
        List<Entry> mp3s = new ArrayList<>();
        Map<String, String> lrcIds = new HashMap<>();
        List<Uri> images = new ArrayList<>();

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId);
        Cursor cursor = null;
        try {
            cursor = resolver.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (id == null || name == null || name.startsWith(".")) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        dirs.add(new Entry(id, name));
                        continue;
                    }
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".mp3")) {
                        mp3s.add(new Entry(id, name));
                    } else if (lower.endsWith(".lrc")) {
                        lrcIds.put(lower, id);
                    } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                            || lower.endsWith(".png") || lower.endsWith(".webp")) {
                        images.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "query dir failed: " + dirId, t);
        } finally {
            if (cursor != null) cursor.close();
        }

        imageMap.put(dirId, images);
        if (depth == 0) rootImgs.addAll(images);

        Uri dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId);
        for (Entry e : mp3s) {
            out.add(buildSong(tree, dirId, dirUri, e, lrcIds));
        }
        for (Entry d : dirs) {
            walk(tree, d.id, out, imageMap, rootImgs, depth + 1);
        }
    }

    private Song buildSong(Uri tree, String dirId, Uri dirUri, Entry entry,
                           Map<String, String> lrcIds) {
        String base = entry.name.substring(0, entry.name.length() - 4);
        int dash = base.indexOf('-');
        String artist;
        String title;
        if (dash > 0) {
            artist = base.substring(0, dash).trim();
            title = base.substring(dash + 1).trim();
        } else if (dash == 0) {
            artist = "未知歌手";
            title = base.substring(1).trim();
        } else {
            artist = "未知歌手";
            title = base.trim();
        }
        if (artist.isEmpty()) artist = "未知歌手";
        if (title.isEmpty()) title = base;

        Song song = new Song();
        song.docId = entry.id;
        song.fileName = entry.name;
        song.uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id);
        song.dirId = dirId;
        song.dirUri = dirUri;
        song.artist = artist;
        song.title = title;

        String lrcName = (base + ".lrc").toLowerCase(Locale.ROOT);
        String lrcId = lrcIds.get(lrcName);
        if (lrcId != null) {
            song.hasLrc = true;
            song.lrcUri = DocumentsContract.buildDocumentUriUsingTree(tree, lrcId);
        }

        String key = durationKey(entry.id, entry.name);
        Long cached;
        synchronized (durationLock) {
            cached = durationCache.get(key);
        }
        if (cached != null && cached > 0) {
            song.durationMs = cached;
        } else {
            song.durationMs = extractDuration(song.uri);
            if (song.durationMs > 0) {
                synchronized (durationLock) {
                    durationCache.put(key, song.durationMs);
                }
            }
        }

        song.updatePinyin();
        return song;
    }

    private long extractDuration(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(app, uri);
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value == null) return 0L;
            return Long.parseLong(value);
        } catch (Throwable t) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private String durationKey(String docId, String fileName) {
        return docId + "|" + fileName;
    }

    private void loadDurationCache() {
        SharedPreferences sp = app.getSharedPreferences(DURATION_PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(DURATION_KEY, "{}");
        try {
            JSONObject object = new JSONObject(json);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long value = object.optLong(key, 0L);
                if (value > 0) durationCache.put(key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveDurationCache() {
        try {
            JSONObject object = new JSONObject();
            for (Map.Entry<String, Long> e : durationCache.entrySet()) {
                object.put(e.getKey(), e.getValue());
            }
            app.getSharedPreferences(DURATION_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(DURATION_KEY, object.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 歌词下载

    private void startLyricBackfill(int generation) {
        List<Song> snapshot = songs;
        for (Song song : snapshot) {
            if (song.hasLrc) continue;
            if (!lyricAttempted.add(song.docId)) continue;
            lyricExec.execute(() -> {
                if (generation != lyricGeneration) return;
                try {
                    fetchAndSave(song, false);
                } catch (Throwable t) {
                    Log.w(TAG, "lyric fetch failed: " + song.displayName(), t);
                }
                try {
                    Thread.sleep(350L);
                } catch (InterruptedException ignored) {
                }
            });
        }
    }

    private void fetchAndSave(Song song, boolean overwrite) {
        if (!overwrite && song.hasLrc) return;
        String lrc = fetchLyric(song);
        if (lrc == null || !LrcParser.looksLikeLrc(lrc)) return;

        if (overwrite && song.lrcUri != null && writeUri(song.lrcUri, lrc)) {
            song.hasLrc = true;
            final Song updated = song;
            main.post(() -> notifyLyrics(updated));
            return;
        }

        Saved saved = saveLrc(song, lrc);
        if (saved == null) return;
        song.hasLrc = true;
        song.lrcUri = saved.uri;
        song.cachedLrcPath = saved.path;
        final Song updated = song;
        main.post(() -> notifyLyrics(updated));
    }

    private static final class Saved {
        final Uri uri;
        final String path;

        Saved(Uri uri, String path) {
            this.uri = uri;
            this.path = path;
        }
    }

    private Saved saveLrc(Song song, String content) {
        String name = lrcFileName(song);
        if (song.dirUri != null) {
            try {
                Uri doc = DocumentsContract.createDocument(
                        resolver, song.dirUri, "text/plain", name);
                if (doc != null && writeUri(doc, content)) {
                    return new Saved(doc, null);
                }
            } catch (Throwable t) {
                Log.w(TAG, "save lrc beside song failed", t);
            }
        }
        try {
            File dir = new File(app.getFilesDir(), "lrc");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, Integer.toHexString(
                    (song.docId + "|" + song.baseName()).hashCode()) + ".lrc");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return new Saved(null, file.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "save lrc cache failed", t);
            return null;
        }
    }

    private static String lrcFileName(Song song) {
        String name = song.fileName;
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name == null || name.isEmpty()) name = song.baseName();
        return name + ".lrc";
    }

    private boolean writeUri(Uri uri, String content) {
        try {
            OutputStream out;
            try {
                out = resolver.openOutputStream(uri, "wt");
            } catch (Throwable t) {
                out = resolver.openOutputStream(uri, "w");
            }
            if (out == null) return false;
            try (OutputStream os = out) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String fetchLyric(Song song) {
        String lrc = lrclibGet(song);
        if (LrcParser.looksLikeLrc(lrc)) return lrc;

        lrc = lrclibSearch(song);
        if (LrcParser.looksLikeLrc(lrc)) return lrc;

        lrc = lrcCx(song);
        if (LrcParser.looksLikeLrc(lrc)) return lrc;
        return null;
    }

    private String lrclibGet(Song song) {
        try {
            String url = "https://lrclib.net/api/get?artist_name=" + encode(song.artist)
                    + "&track_name=" + encode(song.title);
            if (song.durationMs > 0) {
                url += "&duration=" + Math.round(song.durationMs / 1000.0);
            }
            String body = httpGet(url);
            if (body == null) return null;
            JSONObject object = new JSONObject(body);
            return stringField(object, "syncedLyrics");
        } catch (Throwable t) {
            return null;
        }
    }

    private String lrclibSearch(Song song) {
        try {
            String url = "https://lrclib.net/api/search?artist_name=" + encode(song.artist)
                    + "&track_name=" + encode(song.title);
            String body = httpGet(url);
            if (body == null) return null;
            JSONArray array = new JSONArray(body);
            String best = null;
            double bestDiff = Double.MAX_VALUE;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String lyrics = stringField(item, "syncedLyrics");
                if (!LrcParser.looksLikeLrc(lyrics)) continue;
                double duration = item.optDouble("duration", -1);
                double diff = 0;
                if (song.durationMs > 0 && duration > 0) {
                    diff = Math.abs(duration - song.durationMs / 1000.0);
                }
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = lyrics;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private String lrcCx(Song song) {
        try {
            String url = "https://api.lrc.cx/lyrics?artist=" + encode(song.artist)
                    + "&title=" + encode(song.title);
            String body = httpGet(url);
            if (body == null) return null;
            String trimmed = body.trim();
            if (trimmed.startsWith("{") || (trimmed.startsWith("[") && trimmed.contains("\":"))) {
                String fromJson = extractLyricsFromJson(trimmed);
                if (fromJson != null) return fromJson;
            }
            if (LrcParser.looksLikeLrc(trimmed)) return trimmed;
            return extractLyricsFromJson(trimmed);
        } catch (Throwable t) {
            return null;
        }
    }

    private String extractLyricsFromJson(String body) {
        try {
            Object root = new JSONTokener(body).nextValue();
            return findLyrics(root, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private String findLyrics(Object node, int depth) {
        if (node == null || depth > 6) return null;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String[] keys = {"syncedLyrics", "lyrics", "lrc", "lyric", "text"};
            for (String key : keys) {
                Object value = obj.opt(key);
                if (value instanceof String && LrcParser.looksLikeLrc((String) value)) {
                    return (String) value;
                }
            }
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String found = findLyrics(obj.opt(it.next()), depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findLyrics(array.opt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String stringField(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private String httpGet(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "SimpleKaraoke/1.0 (Android; local)");
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return LyricStore.decode(out.toByteArray());
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ------------------------------------------------------------------ 通知

    private void notifyScanStarted() {
        for (Listener l : listeners) l.onScanStarted();
    }

    private void notifySongsChanged() {
        List<Song> snapshot = songs;
        for (Listener l : listeners) l.onSongsChanged(snapshot);
    }

    private void notifyLyrics(Song song) {
        for (Listener l : listeners) l.onLyricsUpdated(song);
    }

    private static final Comparator<Song> COMPARATOR = (a, b) -> {
        int c = a.pinyinArtist.compareTo(b.pinyinArtist);
        if (c != 0) return c;
        c = a.pinyinTitle.compareTo(b.pinyinTitle);
        if (c != 0) return c;
        return a.displayName().compareTo(b.displayName());
    };
}
