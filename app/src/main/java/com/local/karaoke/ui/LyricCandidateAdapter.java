package com.local.karaoke.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.local.karaoke.R;
import com.local.karaoke.data.LyricCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LyricCandidateAdapter extends RecyclerView.Adapter<LyricCandidateAdapter.VH> {

    public interface OnPick {
        void onPick(LyricCandidate candidate);
    }

    private final List<LyricCandidate> data = new ArrayList<>();
    private final OnPick onPick;

    public LyricCandidateAdapter(OnPick onPick) {
        this.onPick = onPick;
    }

    public void setData(List<LyricCandidate> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_lyric_candidate, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LyricCandidate c = data.get(position);
        h.title.setText(c.display());
        String dur = c.durationMs > 0
                ? String.format(Locale.US, "%d:%02d", (c.durationMs / 1000) / 60, (c.durationMs / 1000) % 60)
                : "--:--";
        h.subtitle.setText(dur + "  ·  " + c.source);
        h.itemView.setOnClickListener(v -> {
            if (onPick != null) onPick.onPick(c);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;

        VH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.cand_title);
            subtitle = v.findViewById(R.id.cand_subtitle);
        }
    }
}
