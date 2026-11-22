package com.local.karaoke.lyric;

import android.content.Context;
import android.net.Uri;

import com.local.karaoke.data.Song;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 歌词读取：优先歌曲同目录 LRC，其次 APP 内部缓存；兼容 UTF-8 / GBK。
 */
public final class LyricStore {

    private LyricStore() {
    }

    public static String read(Context context, Song song) {
        if (song == null) return null;
        if (song.lrcUri != null) {
            try (InputStream in = context.getContentResolver().openInputStream(song.lrcUri)) {
                if (in != null) return decode(readAll(in));
            } catch (Throwable ignored) {
                // 继续尝试缓存
            }
        }
        if (song.cachedLrcPath != null) {
            try (FileInputStream in = new FileInputStream(new File(song.cachedLrcPath))) {
                return decode(readAll(in));
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static String decode(byte[] bytes) {
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
            }
        }
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return utf8;
        } catch (CharacterCodingException e) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Throwable ignored) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static String readUri(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            return decode(readAll(in));
        } catch (Throwable t) {
            return null;
        }
    }
}
