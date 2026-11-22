package com.local.karaoke.lyric;

public final class LyricLine {

    public final long timeMs;
    public final String text;

    public LyricLine(long timeMs, String text) {
        this.timeMs = timeMs;
        this.text = text;
    }
}
