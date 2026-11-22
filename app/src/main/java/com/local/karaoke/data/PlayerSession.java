package com.local.karaoke.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前播放队列（曲库页 -> 播放页传参）。
 */
public final class PlayerSession {

    private static List<Song> queue = Collections.emptyList();
    private static int index = 0;

    private PlayerSession() {
    }

    public static void set(List<Song> songs, int startIndex) {
        queue = new ArrayList<>(songs);
        index = startIndex;
    }

    public static List<Song> getQueue() {
        return queue;
    }

    public static int getIndex() {
        return index;
    }

    public static void setIndex(int i) {
        index = i;
    }
}
