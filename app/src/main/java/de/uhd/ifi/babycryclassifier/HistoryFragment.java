package de.uhd.ifi.babycryclassifier;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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

        adapter = new HistoryAdapter(requireContext(), record ->
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.delete_confirm_title))
                        .setMessage(getString(R.string.delete_confirm_message))
                        .setPositiveButton(getString(R.string.btn_delete), (d, w) ->
                                CryRepository.getInstance(requireContext()).deleteById(record.id))
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
        );
        recyclerView.setAdapter(adapter);

        CryRepository.getInstance(requireContext())
                .getRecentCries()
                .observe(getViewLifecycleOwner(), cries -> {
                    adapter.setCries(cries);
                    boolean empty = cries == null || cries.isEmpty();
                    emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
                    recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                });

        view.findViewById(R.id.clearHistoryButton).setOnClickListener(v ->
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.clear_all_confirm_title))
                        .setMessage(getString(R.string.clear_all_confirm_message))
                        .setPositiveButton(getString(R.string.btn_delete), (d, w) ->
                                CryRepository.getInstance(requireContext()).deleteAll())
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show());
    }

    interface OnDeleteListener { void onDelete(CryRecord record); }

    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

        private List<CryRecord> cries = new ArrayList<>();
        private final Context context;
        private final OnDeleteListener deleteListener;
        private static final SimpleDateFormat DATE_FMT =
                new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.getDefault());

        HistoryAdapter(Context context, OnDeleteListener deleteListener) {
            this.context        = context;
            this.deleteListener = deleteListener;
        }

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
            holder.top1Text.setText(localiseLabel(context, r.top1Label) + " — " + r.top1Percent + "%");
            holder.top2Text.setText(context.getString(R.string.also_possible,
                    localiseLabel(context, r.top2Label), r.top2Percent));

            int dotColor = colorForLabel(r.top1Label);
            holder.classColorDot.setBackgroundColor(dotColor);
            holder.top1Text.setTextColor(dotColor);

            if (r.userLabel != null && !r.userLabel.equals("unsure")) {
                String conf = r.labelConfidence != null
                        ? " (" + localiseConfidence(context, r.labelConfidence) + ")" : "";
                holder.feedbackText.setText(
                        context.getString(R.string.parent_said_label,
                                localiseLabel(context, r.userLabel)) + conf);
                holder.feedbackText.setVisibility(View.VISIBLE);
            } else if ("unsure".equals(r.userLabel)) {
                holder.feedbackText.setText(context.getString(R.string.parent_not_sure));
                holder.feedbackText.setVisibility(View.VISIBLE);
            } else {
                holder.feedbackText.setText(context.getString(R.string.awaiting_feedback));
                holder.feedbackText.setVisibility(View.VISIBLE);
            }

            holder.deleteButton.setOnClickListener(v -> deleteListener.onDelete(r));
        }

        static String localiseLabel(Context ctx, String dbLabel) {
            if (dbLabel == null) return "";
            switch (dbLabel) {
                case "Hunger":       return ctx.getString(R.string.label_hunger);
                case "Need to burp": return ctx.getString(R.string.label_burp);
                case "Discomfort":   return ctx.getString(R.string.label_discomfort);
                case "Belly pain":   return ctx.getString(R.string.label_belly_pain);
                case "Tiredness":    return ctx.getString(R.string.label_tiredness);
                default:             return dbLabel;
            }
        }

        static String localiseConfidence(Context ctx, String conf) {
            if (conf == null) return "";
            switch (conf) {
                case "high":   return ctx.getString(R.string.conf_high);
                case "medium": return ctx.getString(R.string.conf_medium);
                case "low":    return ctx.getString(R.string.conf_low_label);
                default:       return conf;
            }
        }

        private int colorForLabel(String label) {
            if (label == null) return android.graphics.Color.parseColor("#888888");
            switch (label) {
                case "Hunger":       return android.graphics.Color.parseColor("#D97070");
                case "Need to burp": return android.graphics.Color.parseColor("#CC8833");
                case "Discomfort":   return android.graphics.Color.parseColor("#2A9478");
                case "Belly pain":   return android.graphics.Color.parseColor("#5A8A2A");
                case "Tiredness":    return android.graphics.Color.parseColor("#3A7ABE");
                default:             return android.graphics.Color.parseColor("#888888");
            }
        }

        @Override
        public int getItemCount() { return cries.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView timestampText, top1Text, top2Text, feedbackText;
            ImageButton deleteButton;
            View classColorDot;

            ViewHolder(View v) {
                super(v);
                timestampText = v.findViewById(R.id.timestampText);
                top1Text      = v.findViewById(R.id.historyTop1Text);
                top2Text      = v.findViewById(R.id.historyTop2Text);
                feedbackText  = v.findViewById(R.id.historyFeedbackText);
                deleteButton  = v.findViewById(R.id.deleteButton);
                classColorDot = v.findViewById(R.id.classColorDot);
            }
        }
    }
}