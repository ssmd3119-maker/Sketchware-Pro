package pro.sketchware.activities.editor.view;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;

import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import a.a.a.jC;
import a.a.a.yq;
import io.github.rosemoe.sora.widget.CodeEditor;
import mod.hey.studios.util.Helper;
import pro.sketchware.R;
import pro.sketchware.databinding.ViewCodeEditorBinding;
import pro.sketchware.utility.EditorUtils;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.SketchwareUtil;

/**
 * Lets the user edit an activity's Java source directly.
 * <p>
 * Saving writes the code to /Internal storage/.sketchware/data/&lt;sc_id&gt;/files/java/&lt;ActivityName&gt;.java.
 * {@link yq} skips generating an activity when a file with that name exists there, and
 * {@link a.a.a.ProjectBuilder} compiles that directory, so the saved file replaces the generated code.
 * Reverting deletes the file so the activity is generated from blocks again.
 */
public class JavaCodeEditorActivity extends BaseAppCompatActivity {

    private static final int MENU_UNDO = 0;
    private static final int MENU_REDO = 1;
    private static final int MENU_SAVE = 2;
    private static final int MENU_REVERT = 3;
    private static final int MENU_RELOAD_SCHEME = 4;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ViewCodeEditorBinding binding;
    private CodeEditor editor;

    private String sc_id;
    private String javaName;
    private File overrideFile;

    /**
     * The text last loaded or saved, used to detect unsaved changes. {@code null} while loading.
     */
    private String savedContent;
    private boolean isOverridden;

    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (isContentModified()) {
                new MaterialAlertDialogBuilder(JavaCodeEditorActivity.this)
                        .setIcon(R.drawable.ic_warning_96dp)
                        .setTitle(Helper.getResString(R.string.common_word_warning))
                        .setMessage(Helper.getResString(R.string.src_code_editor_unsaved_changes_dialog_warning_message))
                        .setPositiveButton(Helper.getResString(R.string.common_word_save), (d, which) -> {
                            if (save()) finish();
                        })
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
        javaName = getIntent().getStringExtra("title");
        overrideFile = new File(new FilePathUtil().getPathJava(sc_id), javaName);

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

        binding.close.setOnClickListener(v -> binding.noteCard.setVisibility(View.GONE));

        if (overrideFile.exists()) {
            setLoadedContent(FileUtil.readFile(overrideFile.getAbsolutePath()), true);
        } else {
            loadGeneratedCode();
        }
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

    private void loadGeneratedCode() {
        savedContent = null;
        editor.setEditable(false);
        k();
        executor.execute(() -> {
            String code;
            try {
                code = new yq(getApplicationContext(), sc_id).getFileSrc(javaName, jC.b(sc_id), jC.a(sc_id), jC.c(sc_id));
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
                setLoadedContent(result, false);
            });
        });
    }

    private void setLoadedContent(String code, boolean overridden) {
        savedContent = code;
        isOverridden = overridden;
        editor.setText(code);
        editor.setEditable(true);
        updateNote();
        invalidateOptionsMenu();
    }

    private void updateNote() {
        binding.noteCard.setVisibility(View.VISIBLE);
        if (isOverridden) {
            binding.note.setText("This activity uses your custom Java. Blocks and events from the Logic editor"
                    + " are ignored until you revert to generated code.");
        } else {
            binding.note.setText("Showing generated code. Saving replaces it, and later changes from blocks"
                    + " won't apply until you revert.");
        }
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
        menu.add(Menu.NONE, MENU_REVERT, Menu.NONE, "Revert to generated code").setVisible(isOverridden);
        menu.add(Menu.NONE, MENU_RELOAD_SCHEME, Menu.NONE, "Reload color schemes");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case MENU_UNDO -> editor.undo();
            case MENU_REDO -> editor.redo();
            case MENU_SAVE -> {
                if (savedContent == null) return true;
                if (isContentModified() || !isOverridden) {
                    if (save()) SketchwareUtil.toast("Saved");
                } else {
                    SketchwareUtil.toast("No changes to save");
                }
            }
            case MENU_REVERT -> confirmRevert();
            case MENU_RELOAD_SCHEME -> EditorUtils.loadJavaConfig(editor);
            default -> {
                return super.onOptionsItemSelected(item);
            }
        }
        return true;
    }

    private boolean save() {
        try {
            String code = editor.getText().toString();
            FileUtil.makeDir(overrideFile.getParent());
            FileUtil.writeFile(overrideFile.getAbsolutePath(), code);
            if (!code.equals(FileUtil.readFile(overrideFile.getAbsolutePath()))) {
                SketchwareUtil.toastError("Failed to save " + overrideFile.getAbsolutePath());
                return false;
            }
            savedContent = code;
            if (!isOverridden) {
                isOverridden = true;
                updateNote();
                invalidateOptionsMenu();
            }
            setResult(RESULT_OK);
            return true;
        } catch (Exception e) {
            SketchwareUtil.toastError(e.toString());
            return false;
        }
    }

    private void confirmRevert() {
        new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_warning_96dp)
                .setTitle("Revert to generated code?")
                .setMessage("Your custom " + javaName + " will be deleted and the activity will be generated from blocks again.")
                .setPositiveButton("Revert", (d, which) -> {
                    FileUtil.deleteFile(overrideFile.getAbsolutePath());
                    isOverridden = false;
                    setResult(RESULT_OK);
                    loadGeneratedCode();
                })
                .setNegativeButton(Helper.getResString(R.string.common_word_cancel), null)
                .show();
    }

    private boolean isContentModified() {
        return savedContent != null && !savedContent.equals(editor.getText().toString());
    }
}
