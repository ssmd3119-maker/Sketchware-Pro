package pro.sketchware.activities.editor.view;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;

import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import a.a.a.jC;
import a.a.a.yq;
import io.github.rosemoe.sora.widget.CodeEditor;
import mod.hey.studios.util.Helper;
import pro.sketchware.R;
import pro.sketchware.databinding.ViewCodeEditorBinding;
import pro.sketchware.utility.EditorUtils;
import pro.sketchware.utility.SketchwareUtil;

/**
 * Edits an activity's Java and converts it back to blocks.
 * <p>
 * The code of every event is shown between {@code //#region sw:<event>} and {@code //#endregion} markers.
 * Saving stores each changed section in its event as an "add source directly" block, see
 * {@link EventCodeBridge}. Nothing is written to disk here; the project is saved by the Design screen
 * like changes made in the Logic editor.
 */
public class JavaCodeEditorActivity extends BaseAppCompatActivity {

    private static final int MENU_UNDO = 0;
    private static final int MENU_REDO = 1;
    private static final int MENU_SAVE = 2;
    private static final int MENU_RELOAD_SCHEME = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ViewCodeEditorBinding binding;
    private CodeEditor editor;

    private String sc_id;
    private EventCodeBridge bridge;

    /**
     * The text as generated, used to detect unsaved changes. {@code null} while loading.
     */
    private String generatedCode;
    private LinkedHashMap<String, String> generatedSections;
    private String generatedSkeleton;

    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (isContentModified()) {
                new MaterialAlertDialogBuilder(JavaCodeEditorActivity.this)
                        .setIcon(R.drawable.ic_warning_96dp)
                        .setTitle(Helper.getResString(R.string.common_word_warning))
                        .setMessage(Helper.getResString(R.string.src_code_editor_unsaved_changes_dialog_warning_message))
                        .setPositiveButton(Helper.getResString(R.string.common_word_save), (d, which) -> save(true))
                        .setNeutralButton(Helper.getResString(R.string.common_word_exit), (d, which) -> finish())
                        .setNegativeButton(Helper.getResString(R.string.common_word_cancel), null)
                        .show();
            } else {
                finish();
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        binding = ViewCodeEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        sc_id = savedInstanceState == null ? getIntent().getStringExtra("sc_id") : savedInstanceState.getString("sc_id");
        String javaName = getIntent().getStringExtra("title");
        ProjectFileBean projectFile = null;
        for (ProjectFileBean file : jC.b(sc_id).b()) {
            if (javaName != null && javaName.equals(file.getJavaName())) {
                projectFile = file;
                break;
            }
        }
        if (projectFile == null) {
            SketchwareUtil.toastError("Activity " + javaName + " not found");
            finish();
            return;
        }
        bridge = new EventCodeBridge(sc_id, projectFile);

        getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
        setSupportActionBar(binding.toolbar);
        getSupportActionBar().setTitle("Java Editor");
        getSupportActionBar().setSubtitle(javaName);
        binding.toolbar.setNavigationOnClickListener(v -> onBackPressedCallback.handleOnBackPressed());

        editor = binding.editor;
        editor.setTypefaceText(EditorUtils.getTypeface(this));
        editor.setTextSize(14);
        EditorUtils.loadJavaConfig(editor);
        editor.setEditable(false);

        binding.noteCard.setVisibility(View.VISIBLE);
        binding.note.setText("Edit code between \"//#region sw:\" and \"//#endregion\" markers; each section is one event."
                + " Saving turns changed sections back into blocks.");
        binding.close.setOnClickListener(v -> binding.noteCard.setVisibility(View.GONE));

        loadCode(0);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString("sc_id", sc_id);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /**
     * Generates the activity's Java from its current blocks.
     *
     * @param line Line to move the cursor to once loaded.
     */
    private void loadCode(int line) {
        generatedCode = null;
        editor.setEditable(false);
        k();
        executor.execute(() -> {
            String code;
            try {
                code = bridge.generate(new yq(getApplicationContext(), sc_id));
            } catch (Exception e) {
                code = "";
            }
            String result = code;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                h();
                if (result.isEmpty()) {
                    SketchwareUtil.toastError("Failed to generate source.");
                    finish();
                    return;
                }
                generatedCode = result;
                generatedSections = EventCodeBridge.parseSections(result);
                generatedSkeleton = EventCodeBridge.skeleton(result);
                editor.setText(result);
                editor.setEditable(true);
                int lastLine = editor.getText().getLineCount() - 1;
                editor.setSelection(Math.min(line, lastLine), 0);
            });
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_UNDO, Menu.NONE, "Undo")
                .setIcon(AppCompatResources.getDrawable(this, R.drawable.ic_mtrl_undo))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_REDO, Menu.NONE, "Redo")
                .setIcon(AppCompatResources.getDrawable(this, R.drawable.ic_mtrl_redo))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, "Save")
                .setIcon(AppCompatResources.getDrawable(this, R.drawable.ic_mtrl_save))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_RELOAD_SCHEME, Menu.NONE, "Reload color schemes");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case MENU_UNDO -> editor.undo();
            case MENU_REDO -> editor.redo();
            case MENU_SAVE -> {
                if (generatedCode == null) return true;
                if (isContentModified()) {
                    save(false);
                } else {
                    SketchwareUtil.toast("No changes to save");
                }
            }
            case MENU_RELOAD_SCHEME -> EditorUtils.loadJavaConfig(editor);
            default -> {
                return super.onOptionsItemSelected(item);
            }
        }
        return true;
    }

    /**
     * Converts changed sections to blocks.
     *
     * @param finishAfter Close the editor once saved, otherwise reload the code from the new blocks.
     */
    private void save(boolean finishAfter) {
        String code = editor.getText().toString();
        LinkedHashMap<String, String> sections = EventCodeBridge.parseSections(code);

        LinkedHashMap<String, String> changed = new LinkedHashMap<>();
        ArrayList<String> unmatched = new ArrayList<>();
        for (Map.Entry<String, String> entry : generatedSections.entrySet()) {
            String key = entry.getKey();
            String body = sections.get(key);
            if (body == null) {
                // Markers were lost while editing. More Blocks can still be found by their method name;
                // other events keep their blocks.
                body = EventCodeBridge.findMoreBlockBody(code, key);
                if (body == null) {
                    unmatched.add(key);
                    continue;
                }
            }
            String newBody = EventCodeBridge.normalizeBody(body);
            if (!newBody.equals(EventCodeBridge.normalizeBody(entry.getValue()))) {
                changed.put(key, newBody);
            }
        }

        if (!EventCodeBridge.skeleton(code).equals(generatedSkeleton)) {
            new MaterialAlertDialogBuilder(this)
                    .setIcon(R.drawable.ic_warning_96dp)
                    .setTitle("Changes outside events")
                    .setMessage("Some changes are outside the event sections (for example imports, fields or"
                            + " generated methods). Those parts are generated from your project and can't be"
                            + " turned into blocks, so they will be discarded.\n\n"
                            + "Tip: put code that needs to run inside an event section, or use an"
                            + " \"add source directly\" block in the Import event for imports.")
                    .setPositiveButton("Save events", (d, which) -> applyAndReload(changed, unmatched, finishAfter))
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .show();
            return;
        }
        applyAndReload(changed, unmatched, finishAfter);
    }

    private void applyAndReload(Map<String, String> changed, List<String> unmatched, boolean finishAfter) {
        if (!changed.isEmpty()) {
            bridge.applyChanges(changed);
            setResult(RESULT_OK);
        }
        String message = changed.isEmpty() ? "No event changes to save"
                : "Saved " + changed.size() + (changed.size() == 1 ? " event" : " events") + " as blocks";
        if (!unmatched.isEmpty()) {
            message += "\nKept blocks unchanged for " + String.join(", ", unmatched) + " (its markers were removed)";
        }
        SketchwareUtil.toast(message, unmatched.isEmpty() ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG);
        if (finishAfter) {
            finish();
        } else {
            loadCode(editor.getCursor().getLeftLine());
        }
    }

    private boolean isContentModified() {
        return generatedCode != null && !generatedCode.equals(editor.getText().toString());
    }
}
