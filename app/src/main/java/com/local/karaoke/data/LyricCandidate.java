package com.local.karaoke.data;

/**
 * 歌词搜索候选项：用于手动选择歌词版本（类似选字幕）。
 */
public class LyricCandidate {
    public String artist = "";
    public String title = "";
    public long durationMs = 0L;
    public String source = "";   // "LRCLIB" / "NetEase" / "lrc.cx"
    public String preview = "";  // 选中后填充的歌词预览（前几行）

    String lrc;                  // 搜索时已带回的歌词（LRCLIB），可能为 null
    long neteaseId = -1;         // NetEase 二次取词用
    String lrcCxQuery = null;    // lrc.cx 取词用（artist||title）

    public String display() {
        return artist + " - " + title;
    }
}
