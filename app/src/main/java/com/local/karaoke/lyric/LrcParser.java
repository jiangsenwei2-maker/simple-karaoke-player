package com.local.karaoke.lyric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标准 LRC 解析：支持一行多时间轴、[offset:] 偏移、毫秒/百分秒。
 */
public final class LrcParser {

    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_TAG = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    private LrcParser() {
    }

    public static List<LyricLine> parse(String raw) {
        List<LyricLine> result = new ArrayList<>();
        if (raw == null) return result;

        String text = raw.replace("\uFEFF", "");
        long offset = 0L;
        Matcher om = OFFSET.matcher(text);
        if (om.find()) {
            try {
                offset = Long.parseLong(om.group(1));
            } catch (NumberFormatException ignored) {
            }
        }

        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            List<Long> times = new ArrayList<>();
            int pos = 0;
            while (pos < line.length()) {
                Matcher m = TIME_TAG.matcher(line);
                m.region(pos, line.length());
                if (!m.lookingAt()) break;
                long minute = parseLong(m.group(1));
                long second = parseLong(m.group(2));
                long ms = parseFraction(m.group(3));
                times.add(minute * 60_000L + second * 1000L + ms);
                pos = m.end();
            }
            if (times.isEmpty()) continue;

            String lyric = line.substring(pos).trim();
            if (lyric.isEmpty()) continue;

            for (long t : times) {
                long finalTime = Math.max(0L, t + offset);
                result.add(new LyricLine(finalTime, lyric));
            }
        }

        Collections.sort(result, Comparator.comparingLong(o -> o.timeMs));
        return result;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long parseFraction(String fraction) {
        if (fraction == null || fraction.isEmpty()) return 0L;
        switch (fraction.length()) {
            case 1:
                return parseLong(fraction) * 100L;
            case 2:
                return parseLong(fraction) * 10L;
            default:
                return parseLong(fraction.substring(0, 3));
        }
    }

    public static boolean looksLikeLrc(String content) {
        return content != null && TIME_TAG.matcher(content).find();
    }
}
