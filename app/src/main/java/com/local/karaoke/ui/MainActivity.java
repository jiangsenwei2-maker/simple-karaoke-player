package com.local.karaoke.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.local.karaoke.R;
import com.local.karaoke.data.LibraryRepository;
import com.local.karaoke.data.PlayerSession;
import com.local.karaoke.data.Prefs;
import com.local.karaoke.data.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements LibraryRepository.Listener {

    private LibraryRepository repo;

    private RecyclerView list;
    private SongAdapter songAdapter;
    private ArtistAdapter artistAdapter;
    private EditText searchInput;
    private TextView tabAll;
    private TextView tabArtist;
    private TextView statusText;
    private TextView emptyTitle;
    private TextView emptyDesc;
    private View emptyView;
    private View scanProgress;
    private MaterialButton btnChooseFolder;

    private boolean artistMode = false;
    private String query = "";

    private final ActivityResultLauncher<Uri> folderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) applyFolder(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = LibraryRepository.get(this);
        setContentView(R.layout.activity_main);
        if (savedInstanceState != null) {
            query = savedInstanceState.getString("query", "");
            artistMode = savedInstanceState.getBoolean("artist_mode", false);
        }

        list = findViewById(R.id.song_list);
        searchInput = findViewById(R.id.search_input);
        tabAll = findViewById(R.id.tab_all);
        tabArtist = findViewById(R.id.tab_artist);
        statusText = findViewById(R.id.status_text);
        emptyView = findViewById(R.id.empty_view);
        emptyTitle = findViewById(R.id.empty_title);
        emptyDesc = findViewById(R.id.empty_desc);
        scanProgress = findViewById(R.id.scan_progress);
        btnChooseFolder = findViewById(R.id.btn_choose_folder);

        songAdapter = new SongAdapter(this::playSong, this::showRenameDialog);
        artistAdapter = new ArtistAdapter(this::openArtist);

        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(songAdapter);
        list.setHasFixedSize(false);

        tabAll.setSelected(!artistMode);
        tabArtist.setSelected(artistMode);
        tabAll.setOnClickListener(v -> switchMode(false));
        tabArtist.setOnClickListener(v -> switchMode(true));

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            if (Prefs.getTreeUri(this) == null) {
                folderLauncher.launch(null);
            } else {
                repo.scan();
            }
        });

        btnChooseFolder.setOnClickListener(v -> {
            if (Prefs.getTreeUri(this) == null) {
                folderLauncher.launch(null);
            } else {
                repo.scan();
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString();
                render();
            }
        });

        if (Prefs.getTreeUri(this) != null && !repo.hasScanned() && !repo.isScanning()) {
            repo.scan();
        }
        if (!query.isEmpty()) {
            searchInput.setText(query);
            if (searchInput.getText() != null) {
                searchInput.setSelection(searchInput.getText().length());
            }
        }
        render();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString("query", query);
        outState.putBoolean("artist_mode", artistMode);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        repo.addListener(this);
        render();
    }

    @Override
    protected void onStop() {
        repo.removeListener(this);
        super.onStop();
    }

    // ------------------------------------------------------------------ 仓库回调

    @Override
    public void onScanStarted() {
        render();
    }

    @Override
    public void onSongsChanged(List<Song> songs) {
        render();
    }

    @Override
    public void onLyricsUpdated(Song song) {
        songAdapter.notifyDataSetChanged();
        updateStatus(repo.getSongs(), currentVisibleCount());
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        boolean hasFolder = Prefs.getTreeUri(this) != null;
        boolean scanning = repo.isScanning();
        List<Song> all = repo.getSongs();

        List<Song> visible = new ArrayList<>();
        String q = query.trim();
        if (q.isEmpty()) {
            visible.addAll(all);
        } else {
            for (Song song : all) {
                if (song.matches(q)) visible.add(song);
            }
        }

        List<ArtistAdapter.ArtistGroup> groups = null;
        if (artistMode) {
            groups = buildGroups(visible);
            artistAdapter.setGroups(groups);
            if (list.getAdapter() != artistAdapter) list.setAdapter(artistAdapter);
        } else {
            songAdapter.setSongs(visible);
            if (list.getAdapter() != songAdapter) list.setAdapter(songAdapter);
        }

        updateStatus(all, artistMode ? (groups == null ? 0 : groups.size()) : visible.size());
        updateEmpty(hasFolder, scanning, all.isEmpty(), visible.isEmpty(), q.isEmpty());
        scanProgress.setVisibility(scanning ? View.VISIBLE : View.GONE);
    }

    private int currentVisibleCount() {
        return list.getAdapter() == artistAdapter ? artistAdapter.getItemCount() : songAdapter.getItemCount();
    }

    private void updateStatus(List<Song> all, int visibleCount) {
        if (repo.isScanning()) {
            statusText.setText(R.string.scanning);
            return;
        }
        if (Prefs.getTreeUri(this) == null) {
            statusText.setText(R.string.not_set);
            return;
        }
        String q = query.trim();
        if (!q.isEmpty()) {
            if (artistMode) {
                statusText.setText("找到 " + visibleCount + " 位歌手");
            } else {
                statusText.setText("找到 " + visibleCount + " 首");
            }
            return;
        }
        if (artistMode) {
            statusText.setText(visibleCount + " 位歌手 · 共 " + all.size() + " 首");
            return;
        }
        int lrc = 0;
        for (Song s : all) if (s.hasLrc) lrc++;
        statusText.setText("共 " + all.size() + " 首 · 歌词 " + lrc + " 首");
    }

    private void updateEmpty(boolean hasFolder, boolean scanning, boolean libraryEmpty,
                             boolean visibleEmpty, boolean noQuery) {
        if (scanning) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        if (!hasFolder) {
            emptyView.setVisibility(View.VISIBLE);
            emptyTitle.setText(R.string.no_folder_title);
            emptyDesc.setText(R.string.no_folder_desc);
            btnChooseFolder.setVisibility(View.VISIBLE);
            btnChooseFolder.setText(R.string.choose_folder);
            return;
        }
        if (libraryEmpty) {
            emptyView.setVisibility(View.VISIBLE);
            emptyTitle.setText(R.string.empty_songs_title);
            emptyDesc.setText(R.string.empty_songs_desc);
            btnChooseFolder.setVisibility(View.VISIBLE);
            btnChooseFolder.setText(R.string.retry_scan);
            return;
        }
        if (visibleEmpty && noQuery) {
            emptyView.setVisibility(View.VISIBLE);
            emptyTitle.setText(R.string.empty_songs_title);
            emptyDesc.setText(R.string.empty_songs_desc);
            btnChooseFolder.setVisibility(View.VISIBLE);
            btnChooseFolder.setText(R.string.retry_scan);
            return;
        }
        if (visibleEmpty) {
            emptyView.setVisibility(View.VISIBLE);
            emptyTitle.setText(R.string.search_empty_title);
            emptyDesc.setText(R.string.search_empty_desc);
            btnChooseFolder.setVisibility(View.GONE);
            return;
        }
        emptyView.setVisibility(View.GONE);
    }

    private List<ArtistAdapter.ArtistGroup> buildGroups(List<Song> source) {
        Map<String, List<Song>> map = new LinkedHashMap<>();
        for (Song song : source) {
            List<Song> group = map.get(song.artist);
            if (group == null) {
                group = new ArrayList<>();
                map.put(song.artist, group);
            }
            group.add(song);
        }
        List<ArtistAdapter.ArtistGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<Song>> entry : map.entrySet()) {
            List<Song> group = entry.getValue();
            String pinyin = group.isEmpty() ? "" : group.get(0).pinyinArtist;
            groups.add(new ArtistAdapter.ArtistGroup(entry.getKey(), pinyin, group));
        }
        groups.sort((a, b) -> a.pinyin.compareTo(b.pinyin));
        return groups;
    }

    private void switchMode(boolean artist) {
        artistMode = artist;
        tabAll.setSelected(!artist);
        tabArtist.setSelected(artist);
        render();
    }

    // ------------------------------------------------------------------ 交互

    private void applyFolder(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }
        Prefs.setTreeUri(this, uri.toString());
        repo.scan();
        render();
    }

    private void playSong(Song song) {
        List<Song> queue = songAdapter.getSongs();
        int index = queue.indexOf(song);
        List<Song> playQueue;
        if (index < 0) {
            playQueue = Collections.singletonList(song);
            index = 0;
        } else {
            playQueue = queue;
        }
        PlayerSession.set(playQueue, index);
        startActivity(new Intent(this, PlayerActivity.class));
    }

    private void openArtist(ArtistAdapter.ArtistGroup group) {
        List<Song> songs = group.songs;
        String[] titles = new String[songs.size()];
        for (int i = 0; i < songs.size(); i++) {
            titles[i] = songs.get(i).title;
        }
        new MaterialAlertDialogBuilder(this, R.style.Theme_Karaoke_Dialog)
                .setTitle(group.name)
                .setItems(titles, (dialog, which) -> {
                    PlayerSession.set(songs, which);
                    startActivity(new Intent(this, PlayerActivity.class));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showRenameDialog(Song song) {
        View view = getLayoutInflater().inflate(R.layout.dialog_rename, null, false);
        TextInputLayout layoutArtist = view.findViewById(R.id.layout_artist);
        TextInputLayout layoutSong = view.findViewById(R.id.layout_song);
        TextInputEditText inputArtist = view.findViewById(R.id.input_artist);
        TextInputEditText inputSong = view.findViewById(R.id.input_song);

        inputArtist.setText(song.artist);
        inputSong.setText(song.title);
        if (inputArtist.getText() != null) inputArtist.setSelection(inputArtist.getText().length());

        AlertDialog dialog = new MaterialAlertDialogBuilder(this, R.style.Theme_Karaoke_Dialog)
                .setTitle(R.string.dialog_rename_title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String artist = textOf(inputArtist).trim();
                    String title = textOf(inputSong).trim();
                    layoutArtist.setError(null);
                    layoutSong.setError(null);

                    if (artist.isEmpty()) {
                        layoutArtist.setError(getString(R.string.artist_error_empty));
                        return;
                    }
                    if (artist.contains("-")) {
                        layoutArtist.setError(getString(R.string.artist_error_dash));
                        return;
                    }
                    if (containsIllegal(artist)) {
                        layoutArtist.setError(getString(R.string.artist_error_illegal));
                        return;
                    }
                    if (title.isEmpty()) {
                        layoutSong.setError(getString(R.string.song_error_empty));
                        return;
                    }
                    if (containsIllegal(title)) {
                        layoutSong.setError(getString(R.string.song_error_illegal));
                        return;
                    }

                    dialog.dismiss();
                    Toast.makeText(this, R.string.renaming, Toast.LENGTH_SHORT).show();
                    repo.renameSong(song, artist, title, (success, message) -> {
                        String text = success
                                ? getString(R.string.rename_success)
                                : (message != null ? message : getString(R.string.rename_failed));
                        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
                    });
                }));
        dialog.show();
    }

    private static String textOf(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }

    private static boolean containsIllegal(String name) {
        String illegal = "/\\:*?\"<>|\n\r";
        for (int i = 0; i < illegal.length(); i++) {
            if (name.indexOf(illegal.charAt(i)) >= 0) return true;
        }
        return false;
    }
}
