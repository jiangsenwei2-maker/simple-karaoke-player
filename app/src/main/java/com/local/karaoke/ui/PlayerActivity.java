package com.local.karaoke.ui;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.local.karaoke.R;
import com.local.karaoke.data.LibraryRepository;
import com.local.karaoke.data.PlayerSession;
import com.local.karaoke.data.Song;
import com.local.karaoke.lyric.LrcParser;
import com.local.karaoke.lyric.LyricLine;
import com.local.karaoke.lyric.LyricStore;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 播放页：背景图轮播（cover 铺满）、居中单行歌词、播放控制。
 */
public class PlayerActivity extends AppCompatActivity {

    private static final long SLIDE_INTERVAL_MS = 5000L;

    private LibraryRepository repo;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler slideshow = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private List<Song> queue = Collections.emptyList();
    private int index = 0;
    private Song current;

    private MediaPlayer player;
    private boolean prepared = false;
    private boolean playDesired = true;
    private boolean userDragging = false;
    private boolean destroyed = false;

    private List<LyricLine> lyrics = Collections.emptyList();
    private int lyricIndex = -2;

    private List<Uri> images = Collections.emptyList();
    private int imageIndex = 0;
    private int bgGeneration = 0;
    private boolean showingA = true;
    private int viewWidth = 1080;
    private int viewHeight = 1920;

    private ImageView bgA;
    private ImageView bgB;
    private TextView titleView;
    private TextView lyricView;
    private TextView lyricState;
    private TextView timeCurrent;
    private TextView timeTotal;
    private SeekBar seekBar;
    private ImageButton playButton;

    private final LibraryRepository.Listener repoListener = new LibraryRepository.Listener() {
        @Override
        public void onLyricsUpdated(Song song) {
            if (current != null && song.docId.equals(current.docId) && song.hasLrc) {
                loadLyrics();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = LibraryRepository.get(this);
        if (PlayerSession.getQueue().isEmpty()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        queue = PlayerSession.getQueue();
        index = Math.max(0, Math.min(PlayerSession.getIndex(), queue.size() - 1));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        viewWidth = dm.widthPixels;
        viewHeight = dm.heightPixels;

        bgA = findViewById(R.id.bg_image_a);
        bgB = findViewById(R.id.bg_image_b);
        titleView = findViewById(R.id.song_title);
        lyricView = findViewById(R.id.lyric_text);
        lyricState = findViewById(R.id.lyric_state);
        timeCurrent = findViewById(R.id.time_current);
        timeTotal = findViewById(R.id.time_total);
        seekBar = findViewById(R.id.seek_bar);
        playButton = findViewById(R.id.btn_play);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        playButton.setOnClickListener(v -> togglePlay());
        findViewById(R.id.btn_prev).setOnClickListener(v -> move(-1));
        findViewById(R.id.btn_next).setOnClickListener(v -> move(1));
        lyricState.setOnClickListener(v -> openLyricSearch());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long duration = current == null ? 0 : current.durationMs;
                if (player != null && prepared) {
                    try {
                        duration = player.getDuration();
                    } catch (Throwable ignored) {
                    }
                }
                long preview = (long) (duration * (progress / 1000.0));
                timeCurrent.setText(format(preview));
                updateLyric(preview, true);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userDragging = false;
                if (player != null && prepared) {
                    try {
                        long duration = player.getDuration();
                        player.seekTo((int) (duration * (bar.getProgress() / 1000.0)));
                        updateLyric(player.getCurrentPosition(), true);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        ui.post(tick);
        playCurrent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        repo.addListener(repoListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
        if (!images.isEmpty()) {
            slideshow.removeCallbacks(slideRunnable);
            slideshow.postDelayed(slideRunnable, SLIDE_INTERVAL_MS);
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        slideshow.removeCallbacksAndMessages(null);
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
        }
        playDesired = false;
        updatePlayIcon();
        super.onPause();
    }

    @Override
    protected void onStop() {
        repo.removeListener(repoListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        slideshow.removeCallbacksAndMessages(null);
        releasePlayer();
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        viewWidth = dm.widthPixels;
        viewHeight = dm.heightPixels;
        if (current != null) {
            setupBackground();
        }
    }

    // ------------------------------------------------------------------ 播放控制

    private void playCurrent() {
        current = queue.get(index);
        PlayerSession.setIndex(index);

        titleView.setText(current.displayName());
        lyrics = Collections.emptyList();
        lyricIndex = -2;
        lyricView.setAlpha(0.6f);
        lyricView.setText(R.string.no_lyrics);
        setLyricState(current.hasLrc);
        seekBar.setProgress(0);
        timeCurrent.setText("00:00");
        timeTotal.setText(format(current.durationMs));

        setupBackground();
        loadLyrics();
        startPlayback();
    }

    private void setLyricState(boolean ready) {
        lyricState.setText(ready ? R.string.lyrics_ready : R.string.lyrics_pending);
        lyricState.setTextColor(ContextCompat.getColor(this,
                ready ? R.color.accent2 : R.color.text_dim));
    }

    private void startPlayback() {
        releasePlayer();
        playDesired = true;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(this, current.uri);
            mp.setOnPreparedListener(p -> {
                if (destroyed) return;
                prepared = true;
                timeTotal.setText(format(p.getDuration()));
                p.start();
                playDesired = true;
                updatePlayIcon();
            });
            mp.setOnCompletionListener(p -> move(1));
            mp.setOnErrorListener((p, what, extra) -> {
                prepared = false;
                playDesired = false;
                updatePlayIcon();
                Toast.makeText(PlayerActivity.this,
                        "无法播放：" + current.displayName(), Toast.LENGTH_SHORT).show();
                return true;
            });
            mp.prepareAsync();
            player = mp;
            updatePlayIcon();
        } catch (Throwable t) {
            Toast.makeText(this, "无法播放：" + current.displayName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try {
                player.reset();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    private void togglePlay() {
        if (player == null || !prepared) {
            startPlayback();
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                playDesired = false;
            } else {
                player.start();
                playDesired = true;
            }
            updatePlayIcon();
        } catch (Throwable ignored) {
        }
    }

    private void updatePlayIcon() {
        if (playButton == null) return;
        playButton.setImageResource(playDesired ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void move(int delta) {
        if (queue.isEmpty()) return;
        index = (index + delta + queue.size()) % queue.size();
        playCurrent();
    }

    private void openLyricSearch() {
        Song song = current;
        if (song == null) {
            Toast.makeText(this, R.string.lyric_apply_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, LyricSearchActivity.class);
        intent.putExtra(LyricSearchActivity.EXTRA_ARTIST, song.artist);
        intent.putExtra(LyricSearchActivity.EXTRA_TITLE, song.title);
        intent.putExtra(LyricSearchActivity.EXTRA_DOC_ID, song.docId);
        startActivity(intent);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (player != null && prepared) {
                try {
                    int position = player.getCurrentPosition();
                    int duration = player.getDuration();
                    if (duration > 0) {
                        timeTotal.setText(format(duration));
                        if (!userDragging) {
                            seekBar.setProgress((int) (position * 1000L / duration));
                        }
                    }
                    timeCurrent.setText(format(position));
                    updateLyric(position, false);
                } catch (Throwable ignored) {
                }
            }
            ui.postDelayed(this, 250);
        }
    };

    // ------------------------------------------------------------------ 歌词

    private void loadLyrics() {
        final Song song = current;
        if (song == null) return;
        bg.execute(() -> {
            String raw = LyricStore.read(this, song);
            final List<LyricLine> parsed = raw == null
                    ? Collections.emptyList() : LrcParser.parse(raw);
            ui.post(() -> {
                if (destroyed || current != song) return;
                lyrics = parsed;
                lyricIndex = -2;
                if (parsed.isEmpty()) {
                    lyricView.setAlpha(0.6f);
                    lyricView.setText(R.string.no_lyrics);
                    setLyricState(false);
                } else {
                    lyricView.setAlpha(1f);
                    setLyricState(true);
                    updateLyric(0, true);
                }
            });
        });
    }

    private void updateLyric(long position, boolean force) {
        if (lyrics.isEmpty()) return;
        int found = -1;
        for (int i = 0; i < lyrics.size(); i++) {
            if (lyrics.get(i).timeMs <= position + 60L) {
                found = i;
            } else {
                break;
            }
        }
        if (found < 0) {
            if (force || lyricIndex != -1) {
                lyricIndex = -1;
                lyricView.setAlpha(0.55f);
                lyricView.setText("♪");
            }
            return;
        }
        if (!force && found == lyricIndex) return;
        lyricIndex = found;
        lyricView.setAlpha(1f);
        lyricView.setText(lyrics.get(found).text);
    }

    // ------------------------------------------------------------------ 背景轮播

    private void setupBackground() {
        slideshow.removeCallbacksAndMessages(null);
        bgGeneration++;
        images = repo.getImagesForDir(current.dirId);
        if (images == null) images = Collections.emptyList();

        if (images.isEmpty()) {
            bgB.setImageDrawable(null);
            bgB.setAlpha(0f);
            bgA.setImageResource(R.drawable.default_landscape);
            bgA.setAlpha(1f);
            showingA = true;
            return;
        }

        imageIndex = 0;
        showingA = true;
        bgB.setImageDrawable(null);
        bgB.setAlpha(0f);
        loadFirstImage(images.get(0));
        slideshow.postDelayed(slideRunnable, SLIDE_INTERVAL_MS);
    }

    private void loadFirstImage(Uri uri) {
        final int generation = ++bgGeneration;
        bg.execute(() -> {
            final Bitmap bitmap = decode(uri);
            ui.post(() -> {
                if (destroyed || generation != bgGeneration) return;
                if (bitmap != null) {
                    bgA.setImageBitmap(bitmap);
                    bgA.setAlpha(1f);
                } else {
                    bgA.setImageResource(R.drawable.default_landscape);
                    bgA.setAlpha(1f);
                }
            });
        });
    }

    private final Runnable slideRunnable = new Runnable() {
        @Override
        public void run() {
            if (images.isEmpty() || destroyed) return;
            imageIndex = (imageIndex + 1) % images.size();
            final Uri uri = images.get(imageIndex);
            final boolean fromA = showingA;
            final ImageView target = fromA ? bgB : bgA;
            final ImageView old = fromA ? bgA : bgB;
            final int generation = ++bgGeneration;

            bg.execute(() -> {
                final Bitmap bitmap = decode(uri);
                ui.post(() -> {
                    if (destroyed || generation != bgGeneration) return;
                    if (bitmap == null) {
                        slideshow.postDelayed(slideRunnable, SLIDE_INTERVAL_MS);
                        return;
                    }
                    target.setImageBitmap(bitmap);
                    target.setAlpha(0f);
                    target.animate().alpha(1f).setDuration(800).start();
                    old.animate().alpha(0f).setDuration(800)
                            .withEndAction(() -> old.setImageDrawable(null))
                            .start();
                    showingA = !fromA;
                    slideshow.postDelayed(slideRunnable, SLIDE_INTERVAL_MS);
                });
            });
        }
    };

    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= viewWidth
                    && bounds.outHeight / (sample * 2) >= viewHeight) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String format(long ms) {
        long total = Math.max(0, ms) / 1000L;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }
}
