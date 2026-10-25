package pro.sketchware.activities.editor.view;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;

import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
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
    private static final int MENU_CONVERT_TO_BLOCKS = 4;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ViewCodeEditorBinding binding;
    private CodeEditor editor;

    private String sc_id;
    private EventCodeBridge bridge;

    /**
     * The text as generated, used to detect unsaved changes. {@code null} while loading.
     */
    private String generatedCode;

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
        binding.note.setText("Each \"//#region sw:\" section is one event; saving converts its code to real blocks"
                + " (code without a matching block stays as \"add source directly\")."
                + " Add your own methods, fields and imports anywhere, or in the javaEditor sections."
                + " Rename or delete a More Block's method to rename or delete the More Block.");
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
                // The generator writes \r\n; keep one line ending so unchanged code never counts as edited.
                generatedCode = normalizeLineEndings(result);
                editor.setText(generatedCode);
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
        menu.add(Menu.NONE, MENU_CONVERT_TO_BLOCKS, Menu.NONE, "Convert code to blocks");
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
            case MENU_CONVERT_TO_BLOCKS -> {
                if (generatedCode != null) save(false, true);
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
        save(finishAfter, false);
    }

    /**
     * @param convertAll Also convert events whose code didn't change but still use
     *                   "add source directly" blocks.
     */
    private void save(boolean finishAfter, boolean convertAll) {
        EventCodeBridge.Plan plan = bridge.plan(generatedCode, editorText(), convertAll);

        ArrayList<String> warnings = new ArrayList<>();
        if (!plan.deletedMoreBlocks.isEmpty()) {
            warnings.add("These More Blocks will be deleted because their methods were removed: "
                    + String.join(", ", plan.deletedMoreBlocks) + ". Remove any remaining calls to them.");
        }
        if (plan.discardedChanges > 0) {
            warnings.add(plan.discardedChanges + (plan.discardedChanges == 1 ? " change" : " changes")
                    + " to generated code (for example widget fields, listeners or generated methods) can't be"
                    + " stored and will come back. Change those in the Design or Logic editor instead.");
        }
        if (!plan.unmatchedEvents.isEmpty()) {
            warnings.add("Event markers were removed for " + String.join(", ", plan.unmatchedEvents)
                    + ", so those events keep their blocks.");
        }

        if (warnings.isEmpty()) {
            applyAndReload(plan, finishAfter);
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setIcon(R.drawable.ic_warning_96dp)
                    .setTitle("Before saving")
                    .setMessage(String.join("\n\n", warnings))
                    .setPositiveButton(Helper.getResString(R.string.common_word_save), (d, which) -> applyAndReload(plan, finishAfter))
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .show();
        }
    }

    private void applyAndReload(EventCodeBridge.Plan plan, boolean finishAfter) {
        if (!plan.hasChanges()) {
            SketchwareUtil.toast(plan.summary());
            if (finishAfter) finish();
            return;
        }
        int line = editor.getCursor().getLeftLine();
        editor.setEditable(false);
        k();
        executor.execute(() -> {
            Exception error = null;
            try {
                bridge.apply(plan, getApplicationContext());
            } catch (Exception e) {
                error = e;
            }
            Exception failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                h();
                if (failure != null) {
                    editor.setEditable(true);
                    SketchwareUtil.toastError("Failed to save: " + failure);
                    return;
                }
                setResult(RESULT_OK);
                SketchwareUtil.toast(plan.summary());
                if (finishAfter) {
                    finish();
                } else {
                    loadCode(line);
                }
            });
        });
    }

    private boolean isContentModified() {
        return generatedCode != null && !generatedCode.equals(editorText());
    }

    private String editorText() {
        return normalizeLineEndings(editor.getText().toString());
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
