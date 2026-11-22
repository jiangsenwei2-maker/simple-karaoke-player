package com.local.karaoke.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.local.karaoke.R;
import com.local.karaoke.data.LibraryRepository;
import com.local.karaoke.data.LyricCandidate;
import com.local.karaoke.data.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动搜索并选择歌词版本（像选字幕一样切换）。
 */
public class LyricSearchActivity extends AppCompatActivity {

    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_DOC_ID = "doc_id";

    private LibraryRepository repo;
    private Song song;
    private EditText editQuery;
    private TextView textStatus;
    private LyricCandidateAdapter adapter;
    private AlertDialog progressDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyric_search);

        repo = LibraryRepository.get(this);

        String artist = getIntent().getStringExtra(EXTRA_ARTIST);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String docId = getIntent().getStringExtra(EXTRA_DOC_ID);
        song = findSong(docId);

        editQuery = findViewById(R.id.edit_query);
        textStatus = findViewById(R.id.text_status);
        RecyclerView list = findViewById(R.id.list_candidates);
        ImageButton btnBack = findViewById(R.id.btn_back);
        ImageButton btnSearch = findViewById(R.id.btn_search);

        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LyricCandidateAdapter(this::onPickCandidate);
        list.setAdapter(adapter);

        String initial = (artist == null ? "" : artist) + " " + (title == null ? "" : title);
        initial = initial.trim();
        editQuery.setText(initial);
        editQuery.setSelection(editQuery.getText().length());
        editQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });

        btnBack.setOnClickListener(v -> finish());
        btnSearch.setOnClickListener(v -> doSearch());

        if (!initial.isEmpty()) {
            textStatus.setText(R.string.lyric_search_loading);
            doSearch();
        }
    }

    private Song findSong(String docId) {
        if (docId == null) return null;
        for (Song s : repo.getSongs()) {
            if (docId.equals(s.docId)) return s;
        }
        return null;
    }

    /** 把用户输入拆成 歌手 / 歌名。 */
    private String[] splitQuery(String q) {
        if (q == null) return new String[]{"", ""};
        q = q.trim();
        // 优先 "歌手 - 歌名"
        int idx = q.indexOf(" - ");
        if (idx > 0) return new String[]{q.substring(0, idx).trim(), q.substring(idx + 3).trim()};
        idx = q.indexOf('-');
        if (idx > 0) return new String[]{q.substring(0, idx).trim(), q.substring(idx + 1).trim()};
        // 否则按第一个空格拆
        idx = q.indexOf(' ');
        if (idx > 0) return new String[]{q.substring(0, idx).trim(), q.substring(idx + 1).trim()};
        return new String[]{"", q};
    }

    private void doSearch() {
        String q = editQuery.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, R.string.search_lyrics_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] parts = splitQuery(q);
        textStatus.setText(R.string.lyric_search_loading);
        textStatus.setVisibility(View.VISIBLE);
        adapter.setData(new ArrayList<>());
        repo.searchLyrics(parts[0], parts[1], candidates -> {
            textStatus.setVisibility(candidates.isEmpty() ? View.VISIBLE : View.GONE);
            textStatus.setText(candidates.isEmpty()
                    ? R.string.lyric_search_empty : R.string.lyric_search_idle);
            adapter.setData(candidates);
        });
    }

    private void onPickCandidate(LyricCandidate c) {
        showLoading(getString(R.string.lyric_applying));
        repo.loadCandidateLyric(c, lrc -> {
            dismissLoading();
            if (TextUtils.isEmpty(lrc)) {
                Toast.makeText(this, R.string.lyric_apply_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            showPreview(c, lrc);
        });
    }

    private void showPreview(LyricCandidate c, String lrc) {
        // 取带时间轴的行，去掉时间标签作为预览
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String line : lrc.split("\n")) {
            String t = line.replaceFirst("^\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]\\s*", "");
            if (!t.trim().isEmpty()) {
                sb.append(t.trim()).append('\n');
                if (++shown >= 12) break;
            }
        }
        ScrollView sv = new ScrollView(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        TextView tv = new TextView(this);
        tv.setText(sb.toString().trim());
        tv.setTextColor(0xFFF5F7FF);
        tv.setTextSize(15f);
        tv.setLineSpacing(0, 1.2f);
        sv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle(R.string.lyric_preview_title)
                .setMessage(c.display() + "\n来源：" + c.source)
                .setView(sv)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.lyric_apply, (d, w) -> applyLyric(c))
                .show();
    }

    private void applyLyric(LyricCandidate c) {
        if (song == null) {
            Toast.makeText(this, R.string.lyric_apply_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        showLoading(getString(R.string.lyric_applying));
        repo.applyLyric(song, c, (success, message) -> {
            dismissLoading();
            CharSequence msg = success
                    ? getString(R.string.lyric_applied)
                    : (message == null ? getString(R.string.lyric_apply_failed) : message);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            if (success) finish();
        });
    }

    private void showLoading(String msg) {
        dismissLoading();
        ProgressBar pb = new ProgressBar(this);
        progressDialog = new AlertDialog.Builder(this)
                .setMessage(msg)
                .setView(pb)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void dismissLoading() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }
}
