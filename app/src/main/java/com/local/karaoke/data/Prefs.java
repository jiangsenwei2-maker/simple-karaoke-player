package com.local.karaoke.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 仅保存用户自定义的扫描目录（单一项配置）。
 */
public final class Prefs {

    private static final String FILE = "karaoke_prefs";
    private static final String KEY_TREE_URI = "tree_uri";

    private Prefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getTreeUri(Context context) {
        return sp(context).getString(KEY_TREE_URI, null);
    }

    public static void setTreeUri(Context context, String uri) {
        sp(context).edit().putString(KEY_TREE_URI, uri).apply();
    }
}
