package com.local.karaoke.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.local.karaoke.R;
import com.local.karaoke.data.Song;

import java.util.ArrayList;
import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.Holder> {

    public interface OnSongAction {
        void onAction(Song song);
    }

    private final List<Song> data = new ArrayList<>();
    private final OnSongAction clickAction;
    private final OnSongAction renameAction;

    public SongAdapter(OnSongAction clickAction, OnSongAction renameAction) {
        this.clickAction = clickAction;
        this.renameAction = renameAction;
    }

    public void setSongs(List<Song> songs) {
        data.clear();
        if (songs != null) data.addAll(songs);
        notifyDataSetChanged();
    }

    public List<Song> getSongs() {
        return data;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Song song = data.get(position);
        holder.title.setText(song.displayName());

        String state = holder.itemView.getContext().getString(
                song.hasLrc ? R.string.lyrics_ready : R.string.lyrics_pending);
        if (song.durationMs > 0) {
            holder.subtitle.setText(song.durationText() + "  ·  " + state);
        } else {
            holder.subtitle.setText(state);
        }
        holder.subtitle.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                song.hasLrc ? R.color.text_secondary : R.color.text_dim));

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) clickAction.onAction(data.get(pos));
        });
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) renameAction.onAction(data.get(pos));
            return true;
        });
        holder.rename.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) renameAction.onAction(data.get(pos));
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final ImageButton rename;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.song_title);
            subtitle = itemView.findViewById(R.id.song_subtitle);
            rename = itemView.findViewById(R.id.btn_rename);
        }
    }
}
