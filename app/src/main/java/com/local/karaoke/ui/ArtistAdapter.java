package com.local.karaoke.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.local.karaoke.R;
import com.local.karaoke.data.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ArtistAdapter extends RecyclerView.Adapter<ArtistAdapter.Holder> {

    public static class ArtistGroup {
        public final String name;
        public final String pinyin;
        public final List<Song> songs;

        public ArtistGroup(String name, String pinyin, List<Song> songs) {
            this.name = name;
            this.pinyin = pinyin;
            this.songs = songs;
        }
    }

    public interface OnArtistClick {
        void onClick(ArtistGroup group);
    }

    private final List<ArtistGroup> data = new ArrayList<>();
    private final OnArtistClick click;

    public ArtistAdapter(OnArtistClick click) {
        this.click = click;
    }

    public void setGroups(List<ArtistGroup> groups) {
        data.clear();
        if (groups != null) data.addAll(groups);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_artist, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ArtistGroup group = data.get(position);
        holder.name.setText(group.name);
        holder.count.setText(group.songs.size() + " 首歌曲");

        String letter;
        String pinyin = group.pinyin;
        if (pinyin != null && !pinyin.isEmpty()) {
            letter = pinyin.substring(0, 1).toUpperCase(Locale.ROOT);
        } else if (!group.name.isEmpty()) {
            letter = group.name.substring(0, 1).toUpperCase(Locale.ROOT);
        } else {
            letter = "#";
        }
        holder.letter.setText(letter);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) click.onClick(data.get(pos));
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView letter;
        final TextView name;
        final TextView count;

        Holder(@NonNull View itemView) {
            super(itemView);
            letter = itemView.findViewById(R.id.artist_letter);
            name = itemView.findViewById(R.id.artist_name);
            count = itemView.findViewById(R.id.artist_count);
        }
    }
}
