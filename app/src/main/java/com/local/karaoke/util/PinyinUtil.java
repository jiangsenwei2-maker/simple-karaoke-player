package com.local.karaoke.util;

import net.sourceforge.pinyin4j.PinyinHelper;

import java.util.Locale;

/**
 * 拼音工具：全拼与首字母，用于「拼音点歌」。
 */
public final class PinyinUtil {

    private PinyinUtil() {
    }

    public static String full(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                String py = pinyinOf((char) cp);
                if (py != null) sb.append(py);
            } else if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    public static String initials(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                String py = pinyinOf((char) cp);
                if (py != null && !py.isEmpty()) sb.append(py.charAt(0));
            } else if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static String pinyinOf(char c) {
        try {
            String[] array = PinyinHelper.toHanyuPinyinStringArray(c);
            if (array == null || array.length == 0) return null;
            String pinyin = array[0];
            if (pinyin == null) return null;
            StringBuilder sb = new StringBuilder(pinyin.length());
            for (int i = 0; i < pinyin.length(); i++) {
                char ch = pinyin.charAt(i);
                if (!Character.isDigit(ch)) {
                    sb.append(Character.toLowerCase(ch));
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isCjk(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF;
    }
}
