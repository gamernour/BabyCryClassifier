package de.uhd.ifi.babycryclassifier;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryFragment extends Fragment {

    private RecyclerView recyclerView;
    private HistoryAdapter adapter;
    private TextView emptyText;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        emptyText    = view.findViewById(R.id.emptyText);
        recyclerView = view.findViewById(R.id.historyRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new HistoryAdapter();
        recyclerView.setAdapter(adapter);

        // Observe database — auto-updates when new cries arrive
        CryRepository.getInstance(requireContext())
                .getRecentCries()
                .observe(getViewLifecycleOwner(), cries -> {
                    adapter.setCries(cries);
                    boolean empty = cries == null || cries.isEmpty();
                    emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
                    recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                });

        view.findViewById(R.id.clearHistoryButton).setOnClickListener(v ->
                CryRepository.getInstance(requireContext()).deleteAll());
    }


    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

        private List<CryRecord> cries = new ArrayList<>();
        private static final SimpleDateFormat DATE_FMT =
                new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.getDefault());

        void setCries(List<CryRecord> newCries) {
            this.cries = newCries != null ? newCries : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_cry_history, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            CryRecord r = cries.get(position);
            holder.timestampText.setText(DATE_FMT.format(new Date(r.timestamp)));
            holder.top1Text.setText(r.top1Label + " — " + r.top1Percent + "%");
            holder.top2Text.setText("Also possible: " + r.top2Label
                    + " (" + r.top2Percent + "%)");
        }

        @Override
        public int getItemCount() { return cries.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView timestampText, top1Text, top2Text;
            ViewHolder(View v) {
                super(v);
                timestampText = v.findViewById(R.id.timestampText);
                top1Text      = v.findViewById(R.id.historyTop1Text);
                top2Text      = v.findViewById(R.id.historyTop2Text);
            }
        }
    }
}