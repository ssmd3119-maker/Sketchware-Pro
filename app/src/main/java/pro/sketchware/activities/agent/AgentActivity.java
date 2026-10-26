package pro.sketchware.activities.agent;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.noties.markwon.Markwon;
import pro.sketchware.R;
import pro.sketchware.agent.AgentSession;
import pro.sketchware.agent.AgentSettings;
import pro.sketchware.databinding.ActivityAgentBinding;
import pro.sketchware.databinding.DialogAgentSettingsBinding;
import pro.sketchware.databinding.ItemAgentMessageBinding;

/**
 * Chat with an AI agent (Claude) that reads and changes the open project: its activities' Java (saved as
 * blocks), layouts and files.
 */
public class AgentActivity extends BaseAppCompatActivity implements AgentSession.Listener {

    private static final int MENU_NEW_CHAT = 1;
    private static final int MENU_SETTINGS = 2;

    private ActivityAgentBinding binding;
    private AgentSession session;
    private AgentSettings settings;
    private Markwon markwon;
    private final MessageAdapter adapter = new MessageAdapter();
    private boolean approvalShowing;

    private final OnBackPressedCallback stopOnBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            new MaterialAlertDialogBuilder(AgentActivity.this)
                    .setTitle("Stop the agent?")
                    .setMessage("The agent is still working. Leaving stops it; changes it already made are kept.")
                    .setPositiveButton("Stop and leave", (d, w) -> {
                        session.stop();
                        finish();
                    })
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .show();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        binding = ActivityAgentBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String scId = getIntent().getStringExtra("sc_id");
        settings = new AgentSettings(this);
        session = AgentSession.get(this, scId);
        markwon = Markwon.create(this);

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, stopOnBack);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        binding.messages.setLayoutManager(layoutManager);
        binding.messages.setAdapter(adapter);
        binding.messages.setItemAnimator(null);

        binding.emptyText.setText("For example:\n• Add a button to main.xml that shows a toast\n"
                + "• My last build failed, fix it\n• Explain what MainActivity does\n• Create a helper class for saving notes");
        binding.setupButton.setOnClickListener(v -> showSettings());
        binding.sendButton.setOnClickListener(v -> {
            if (session.isRunning()) {
                session.stop();
                return;
            }
            if (!settings.hasApiKey()) {
                showSettings();
                return;
            }
            String text = String.valueOf(binding.input.getText()).trim();
            if (text.isEmpty()) return;
            binding.input.setText("");
            session.send(text);
        });
        onChanged();
    }

    @Override
    protected void onStart() {
        super.onStart();
        session.setListener(this);
        onChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        session.setListener(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Changes made while the design screen thinks nothing changed could be overwritten there.
        if (isFinishing() && session.isRunning()) session.stop();
    }

    @Override
    public void finish() {
        if (session != null && session.isProjectDataChanged()) setResult(RESULT_OK);
        super.finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_NEW_CHAT, Menu.NONE, "New chat")
                .setIcon(R.drawable.ic_mtrl_add)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, "Agent settings")
                .setIcon(R.drawable.ic_mtrl_settings)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_NEW_CHAT) {
            if (session.items.isEmpty()) return true;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Start a new chat?")
                    .setMessage("The agent forgets this conversation. Changes it made to the project are kept.")
                    .setPositiveButton("New chat", (d, w) -> {
                        boolean changed = session.isProjectDataChanged();
                        session.setListener(null);
                        session = AgentSession.reset(this, getIntent().getStringExtra("sc_id"));
                        if (changed) setResult(RESULT_OK);
                        session.setListener(this);
                        onChanged();
                    })
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .show();
            return true;
        } else if (item.getItemId() == MENU_SETTINGS) {
            showSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // region Session

    @Override
    public void onChanged() {
        if (isDestroyed()) return;
        boolean wasAtBottom = !binding.messages.canScrollVertically(1);
        adapter.update(snapshot());
        boolean running = session.isRunning();
        stopOnBack.setEnabled(running);
        binding.sendButton.setIconResource(running ? R.drawable.ic_mtrl_stop : R.drawable.ic_mtrl_send);
        binding.sendButton.setContentDescription(running ? "Stop" : "Send");
        binding.emptyState.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        binding.setupButton.setVisibility(settings.hasApiKey() ? View.GONE : View.VISIBLE);
        getSupportActionBar().setSubtitle(AgentSettings.modelName(settings.getModel()) + (running ? " · working…" : ""));
        if (wasAtBottom && adapter.getItemCount() > 0) binding.messages.scrollToPosition(adapter.getItemCount() - 1);
    }

    @Override
    public void onApprovalNeeded(AgentSession.ApprovalRequest request) {
        if (approvalShowing || isDestroyed()) return;
        approvalShowing = true;
        TextView preview = new TextView(this);
        preview.setText(request.preview);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextSize(12);
        preview.setTextIsSelectable(true);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        preview.setPadding(padding, padding / 2, padding, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(preview);

        boolean[] answered = {false};
        new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_mtrl_auto_awesome)
                .setTitle(request.title + "?")
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton("Allow", (d, w) -> {
                    answered[0] = true;
                    request.answer(true);
                })
                .setNegativeButton("Decline", (d, w) -> {
                    answered[0] = true;
                    request.answer(false);
                })
                .setOnDismissListener(d -> {
                    approvalShowing = false;
                    // Dismissed by leaving the screen: the session asks again when the screen comes back.
                    if (!answered[0] && isFinishing()) request.answer(false);
                })
                .show();
    }

    private List<Row> snapshot() {
        ArrayList<Row> rows = new ArrayList<>();
        synchronized (session.items) {
            for (AgentSession.ChatItem item : session.items) {
                rows.add(new Row(item, item.type, item.text.toString(), item.detail, item.status, item.expanded));
            }
        }
        return rows;
    }

    // endregion

    private void showSettings() {
        DialogAgentSettingsBinding dialog = DialogAgentSettingsBinding.inflate(LayoutInflater.from(this));
        dialog.apiKey.setText(settings.getApiKey());
        dialog.baseUrl.setText(settings.getBaseUrl());
        dialog.model.setSimpleItems(AgentSettings.MODEL_NAMES);
        dialog.model.setText(AgentSettings.modelName(settings.getModel()), false);
        dialog.effort.setSimpleItems(AgentSettings.EFFORTS);
        dialog.effort.setText(settings.getEffort(), false);
        dialog.autoApprove.setChecked(settings.isAutoApprove());

        new MaterialAlertDialogBuilder(this)
                .setTitle("Agent settings")
                .setView(dialog.getRoot())
                .setPositiveButton(R.string.common_word_save, (d, w) -> {
                    String model = AgentSettings.modelId(String.valueOf(dialog.model.getText()));
                    String effort = String.valueOf(dialog.effort.getText());
                    if (!Arrays.asList(AgentSettings.EFFORTS).contains(effort)) effort = "high";
                    settings.save(String.valueOf(dialog.apiKey.getText()), String.valueOf(dialog.baseUrl.getText()), model, effort, dialog.autoApprove.isChecked());
                    onChanged();
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private record Row(AgentSession.ChatItem item, AgentSession.ChatItem.Type type, String text, String detail,
                       AgentSession.ChatItem.Status status, boolean expanded) {
    }

    private class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.Holder> {
        private List<Row> rows = new ArrayList<>();

        void update(List<Row> newRows) {
            List<Row> old = rows;
            rows = newRows;
            if (newRows.size() < old.size()) {
                notifyDataSetChanged();
                return;
            }
            for (int i = 0; i < old.size(); i++) {
                if (!old.get(i).equals(newRows.get(i))) notifyItemChanged(i);
            }
            if (newRows.size() > old.size()) notifyItemRangeInserted(old.size(), newRows.size() - old.size());
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemAgentMessageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(rows.get(position));
        }

        class Holder extends RecyclerView.ViewHolder {
            private final ItemAgentMessageBinding item;

            Holder(ItemAgentMessageBinding item) {
                super(item.getRoot());
                this.item = item;
            }

            void bind(Row row) {
                View card = item.card;
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) card.getLayoutParams();
                params.gravity = row.type() == AgentSession.ChatItem.Type.USER ? Gravity.END : Gravity.START;
                params.width = row.type() == AgentSession.ChatItem.Type.ASSISTANT ? ViewGroup.LayoutParams.MATCH_PARENT
                        : ViewGroup.LayoutParams.WRAP_CONTENT;
                card.setLayoutParams(params);
                item.card.setOnClickListener(null);
                item.card.setClickable(false);
                item.body.setMaxLines(Integer.MAX_VALUE);
                item.body.setEllipsize(null);
                item.body.setTypeface(Typeface.DEFAULT);
                item.detail.setVisibility(View.GONE);
                item.progress.setVisibility(View.GONE);
                item.header.setVisibility(View.GONE);
                item.body.setVisibility(View.VISIBLE);
                int surface = MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceContainerHigh);
                int onSurface = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurface);
                int muted = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurfaceVariant);
                item.body.setTextColor(onSurface);

                switch (row.type()) {
                    case USER -> {
                        item.card.setCardBackgroundColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimaryContainer));
                        item.body.setTextColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnPrimaryContainer));
                        item.body.setText(row.text());
                    }
                    case ASSISTANT -> {
                        item.card.setCardBackgroundColor(surface);
                        markwon.setMarkdown(item.body, row.text());
                    }
                    case THINKING -> {
                        item.card.setCardBackgroundColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceContainerLow));
                        item.header.setVisibility(View.VISIBLE);
                        item.icon.setImageResource(R.drawable.ic_mtrl_auto_awesome);
                        item.icon.setImageTintList(ColorStateList.valueOf(muted));
                        item.title.setText("Thinking");
                        item.title.setTextColor(muted);
                        item.body.setTextColor(muted);
                        item.body.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
                        item.body.setText(row.text().trim());
                        if (!row.expanded()) {
                            item.body.setMaxLines(3);
                            item.body.setEllipsize(TextUtils.TruncateAt.END);
                        }
                        toggleOnClick(row);
                    }
                    case TOOL -> {
                        item.card.setCardBackgroundColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceContainerLow));
                        item.header.setVisibility(View.VISIBLE);
                        item.body.setVisibility(View.GONE);
                        item.title.setText(row.text());
                        item.title.setTextColor(onSurface);
                        int icon;
                        int tint;
                        switch (row.status()) {
                            case RUNNING -> {
                                icon = R.drawable.ic_mtrl_code;
                                tint = muted;
                                item.progress.setVisibility(View.VISIBLE);
                            }
                            case FAILED -> {
                                icon = R.drawable.ic_warning_96dp;
                                tint = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorError);
                            }
                            case DECLINED -> {
                                icon = R.drawable.ic_mtrl_stop;
                                tint = muted;
                            }
                            default -> {
                                icon = R.drawable.ic_mtrl_check;
                                tint = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary);
                            }
                        }
                        item.icon.setImageResource(icon);
                        item.icon.setImageTintList(ColorStateList.valueOf(tint));
                        if (row.detail() != null && row.expanded()) {
                            item.detail.setVisibility(View.VISIBLE);
                            item.detail.setText(row.detail().length() > 20000 ? row.detail().substring(0, 20000) + "\n…" : row.detail());
                            item.detail.setTextColor(muted);
                        }
                        if (row.detail() != null) toggleOnClick(row);
                    }
                    case ERROR -> {
                        item.card.setCardBackgroundColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorErrorContainer));
                        item.body.setTextColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnErrorContainer));
                        item.body.setText(row.text());
                    }
                    case NOTICE -> {
                        item.card.setCardBackgroundColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceContainerLow));
                        item.body.setTextColor(muted);
                        item.body.setText(row.text());
                    }
                }
            }

            private void toggleOnClick(Row row) {
                item.card.setOnClickListener(v -> {
                    row.item().expanded = !row.item().expanded;
                    onChanged();
                });
            }
        }
    }
}
