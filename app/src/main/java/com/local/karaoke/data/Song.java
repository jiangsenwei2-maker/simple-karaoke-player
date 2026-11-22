package com.local.karaoke.data;

import android.net.Uri;

import com.local.karaoke.util.PinyinUtil;

import java.util.Locale;

/**
 * 单曲模型：歌手、歌名、时长、LRC 状态、拼音索引。
 */
public class Song {

    public Uri uri;
    public String docId = "";
    public String fileName = "";
    public String artist = "";
    public String title = "";
    public long durationMs = 0L;

    public volatile boolean hasLrc = false;
    public Uri lrcUri;
    public String cachedLrcPath;

    public String dirId = "";
    public Uri dirUri;

    public String pinyinArtist = "";
    public String pinyinTitle = "";
    public String initialArtist = "";
    public String initialTitle = "";

    public void updatePinyin() {
        pinyinArtist = PinyinUtil.full(artist);
        pinyinTitle = PinyinUtil.full(title);
        initialArtist = PinyinUtil.initials(artist);
        initialTitle = PinyinUtil.initials(title);
    }

    public String baseName() {
        return artist + "-" + title;
    }

    public String displayName() {
        return artist + " - " + title;
    }

    public String durationText() {
        if (durationMs <= 0) return "--:--";
        long total = durationMs / 1000;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    /** 中文 / 拼音全拼 / 拼音首字母模糊匹配。 */
    public boolean matches(String rawQuery) {
        String q = normalize(rawQuery);
        if (q.isEmpty()) return true;
        return normalize(artist).contains(q)
                || normalize(title).contains(q)
                || pinyinArtist.contains(q)
                || pinyinTitle.contains(q)
                || initialArtist.contains(q)
                || initialTitle.contains(q);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "");
    }
}
