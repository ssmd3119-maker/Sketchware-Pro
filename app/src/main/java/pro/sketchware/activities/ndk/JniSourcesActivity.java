package pro.sketchware.activities.ndk;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.pranav.filepicker.FilePickerCallback;
import dev.pranav.filepicker.FilePickerDialogFragment;
import dev.pranav.filepicker.FilePickerOptions;
import mod.hey.studios.code.SrcCodeEditor;
import pro.sketchware.R;
import pro.sketchware.databinding.ActivityJniSourcesBinding;
import pro.sketchware.databinding.ItemJniFileBinding;
import pro.sketchware.ndk.NdkBuilder;
import pro.sketchware.ndk.NdkManager;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.SketchwareUtil;

/**
 * Manages a project's C/C++ sources ({@code files/jni}) and builds them with the NDK.
 */
public class JniSourcesActivity extends BaseAppCompatActivity {

    private static final int MENU_BUILD = 1;
    private static final int MENU_CLEAN = 2;
    private static final int MENU_NDK_MANAGER = 3;
    private static final int MENU_USAGE = 4;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityJniSourcesBinding binding;
    private String scId;
    private String packageName;
    private NdkBuilder ndkBuilder;
    private File root;
    private File current;
    private final FilesAdapter adapter = new FilesAdapter();

    private final OnBackPressedCallback upCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            openDirectory(current.getParentFile());
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        binding = ActivityJniSourcesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        scId = getIntent().getStringExtra("sc_id");
        packageName = getIntent().getStringExtra("pkgName");
        if (packageName == null || packageName.isEmpty()) packageName = "com.my.newproject";
        ndkBuilder = new NdkBuilder(this, scId);
        root = ndkBuilder.getJniDirectory();
        root.mkdirs();

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, upCallback);

        binding.filesList.setAdapter(adapter);
        binding.ndkCard.setOnClickListener(v -> startActivity(new Intent(this, NdkManagerActivity.class)));
        binding.newButton.setOnClickListener(this::showNewMenu);
        binding.createSampleButton.setOnClickListener(v -> createSample());

        openDirectory(root);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshNdkStatus();
        if (current != null) openDirectory(current);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ndkBuilder.cancel();
        executor.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_BUILD, Menu.NONE, "Build now")
                .setIcon(R.drawable.ic_mtrl_terminal)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_USAGE, Menu.NONE, "How to use from Java");
        menu.add(Menu.NONE, MENU_CLEAN, Menu.NONE, "Clean build output");
        menu.add(Menu.NONE, MENU_NDK_MANAGER, Menu.NONE, "NDK manager");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case MENU_BUILD -> buildNow();
            case MENU_CLEAN -> clean();
            case MENU_NDK_MANAGER -> startActivity(new Intent(this, NdkManagerActivity.class));
            case MENU_USAGE -> showUsage();
            default -> {
                return super.onOptionsItemSelected(item);
            }
        }
        return true;
    }

    private void refreshNdkStatus() {
        File ndkBuild = NdkManager.findNdkBuild(this);
        if (ndkBuild != null) {
            File install = NdkManager.getInstallation(this);
            binding.ndkTitle.setText("NDK installed");
            binding.ndkDetails.setText((install != null ? install.getName() : "") + " · builds run on every project build");
            binding.ndkIcon.setImageResource(R.drawable.ic_mtrl_check);
        } else {
            binding.ndkTitle.setText("NDK not installed");
            binding.ndkDetails.setText("Tap to install one; projects with C/C++ code need it to build");
            binding.ndkIcon.setImageResource(R.drawable.ic_mtrl_terminal);
        }
    }

    // region Files

    private void openDirectory(File directory) {
        if (directory == null || !isInside(directory)) directory = root;
        current = directory;
        upCallback.setEnabled(!current.equals(root));
        String relative = root.toURI().relativize(current.toURI()).getPath();
        binding.toolbar.setSubtitle("jni/" + relative);

        File[] children = current.listFiles();
        ArrayList<File> files = new ArrayList<>(children == null ? List.of() : Arrays.asList(children));
        files.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        adapter.setFiles(files);
        boolean emptyProject = current.equals(root) && files.isEmpty();
        binding.emptyLayout.setVisibility(emptyProject ? View.VISIBLE : View.GONE);
    }

    private boolean isInside(File file) {
        return file.getAbsolutePath().startsWith(root.getAbsolutePath());
    }

    private void showNewMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "New file");
        menu.getMenu().add(0, 1, 1, "New folder");
        menu.getMenu().add(0, 2, 2, "Import files");
        menu.getMenu().add(0, 3, 3, "Create sample");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0 -> askName("New file", "e.g. native-lib.cpp", "", name -> {
                    File file = new File(current, name);
                    if (file.exists()) {
                        SketchwareUtil.toastError(name + " already exists");
                        return;
                    }
                    FileUtil.writeFile(file.getAbsolutePath(), "");
                    openDirectory(current);
                    edit(file);
                });
                case 1 -> askName("New folder", "e.g. include", "", name -> {
                    new File(current, name).mkdirs();
                    openDirectory(current);
                });
                case 2 -> importFiles();
                case 3 -> createSample();
            }
            return true;
        });
        menu.show();
    }

    private void importFiles() {
        FilePickerOptions options = new FilePickerOptions();
        options.setTitle("Import C/C++ files");
        options.setMultipleSelection(true);
        FilePickerCallback callback = new FilePickerCallback() {
            @Override
            public void onFilesSelected(@NotNull List<? extends File> files) {
                for (File file : files) {
                    File target = new File(current, file.getName());
                    try {
                        if (file.isDirectory()) {
                            FileUtil.copyDirectory(file, target);
                        } else {
                            FileUtil.copyFile(file.getAbsolutePath(), target.getAbsolutePath());
                        }
                    } catch (IOException e) {
                        SketchwareUtil.toastError("Can't import " + file.getName() + ": " + e.getMessage());
                    }
                }
                openDirectory(current);
            }
        };
        new FilePickerDialogFragment(options, callback).show(getSupportFragmentManager(), "jni_import");
    }

    private void edit(File file) {
        Intent intent = new Intent(this, SrcCodeEditor.class);
        intent.putExtra("title", file.getName());
        intent.putExtra("content", file.getAbsolutePath());
        startActivity(intent);
    }

    private void showFileMenu(View anchor, File file) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (!file.isDirectory()) menu.getMenu().add(0, 0, 0, "Edit");
        menu.getMenu().add(0, 1, 1, "Rename");
        menu.getMenu().add(0, 2, 2, "Delete");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0 -> edit(file);
                case 1 -> askName("Rename", null, file.getName(), name -> {
                    File target = new File(file.getParentFile(), name);
                    if (target.exists() || !file.renameTo(target)) {
                        SketchwareUtil.toastError("Can't rename to " + name);
                    }
                    openDirectory(current);
                });
                case 2 -> new MaterialAlertDialogBuilder(this)
                        .setTitle("Delete " + file.getName() + "?")
                        .setMessage(file.isDirectory() ? "The folder and everything in it will be deleted." : "This can't be undone.")
                        .setPositiveButton(R.string.common_word_delete, (d, w) -> {
                            FileUtil.deleteFile(file.getAbsolutePath());
                            openDirectory(current);
                        })
                        .setNegativeButton(R.string.common_word_cancel, null)
                        .show();
            }
            return true;
        });
        menu.show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void askName(String title, String hint, String initial, NameCallback callback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(initial.length());
        if (hint != null) input.setHint(hint);
        FrameLayout container = new FrameLayout(this);
        int padding = SketchwareUtil.dpToPx(20);
        container.setPadding(padding, SketchwareUtil.dpToPx(8), padding, 0);
        container.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(R.string.common_word_ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) {
                        SketchwareUtil.toastError("Enter a valid name");
                        return;
                    }
                    callback.onName(name);
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    // endregion

    // region Sample and usage

    private String jniClassPath() {
        return packageName.replace('_', '\u0001').replace(".", "_").replace("\u0001", "_1") + "_MainActivity";
    }

    private void createSample() {
        File androidMk = new File(root, "Android.mk");
        if (androidMk.exists()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Replace Android.mk?")
                    .setMessage("The sample replaces Android.mk, Application.mk and native-lib.c.")
                    .setPositiveButton("Replace", (d, w) -> writeSample())
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .show();
        } else {
            writeSample();
        }
    }

    private void writeSample() {
        FileUtil.writeFile(new File(root, "Android.mk").getAbsolutePath(), """
                LOCAL_PATH := $(call my-dir)

                include $(CLEAR_VARS)
                LOCAL_MODULE    := native-lib
                LOCAL_SRC_FILES := native-lib.c
                LOCAL_LDLIBS    := -llog
                include $(BUILD_SHARED_LIBRARY)
                """);
        FileUtil.writeFile(new File(root, "Application.mk").getAbsolutePath(),
                "APP_ABI := " + NdkBuilder.DEFAULT_ABIS + "\n"
                        + "APP_PLATFORM := " + NdkBuilder.DEFAULT_PLATFORM + "\n");
        FileUtil.writeFile(new File(root, "native-lib.c").getAbsolutePath(), """
                #include <jni.h>

                // Called from MainActivity: public native String stringFromJNI();
                JNIEXPORT jstring JNICALL
                Java_%s_stringFromJNI(JNIEnv *env, jobject thiz) {
                    return (*env)->NewStringUTF(env, "Hello from C");
                }
                """.formatted(jniClassPath()));
        openDirectory(root);
        showUsage();
    }

    private void showUsage() {
        String usage = """
                The libraries built here are added to your APK as lib<module>.so.

                In MainActivity (e.g. with the Direct Java editor), load the library and declare its functions:

                static {
                    System.loadLibrary("native-lib");
                }

                public native String stringFromJNI();

                Then call stringFromJNI() from your blocks or code.
                C function names must be Java_<package with _>_<Class>_<method>, e.g.
                Java_%s_stringFromJNI""".formatted(jniClassPath());
        showText("Using C/C++ from Java", usage);
    }

    // endregion

    // region Build

    private void buildNow() {
        if (!ndkBuilder.hasSources()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("No Android.mk")
                    .setMessage("Add an Android.mk to the jni folder, or create the sample to start.")
                    .setPositiveButton("Create sample", (d, w) -> createSample())
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .show();
            return;
        }
        if (!NdkManager.isInstalled(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("NDK not installed")
                    .setMessage("Install an NDK first to build C/C++ code.")
                    .setPositiveButton("NDK manager", (d, w) -> startActivity(new Intent(this, NdkManagerActivity.class)))
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .show();
            return;
        }

        TextView log = logView("Starting ndk-build...\n");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Building C/C++...")
                .setView(scroll)
                .setCancelable(false)
                .setNegativeButton(R.string.common_word_cancel, (d, w) -> ndkBuilder.cancel())
                .show();
        long started = System.currentTimeMillis();
        executor.execute(() -> {
            String message;
            boolean success;
            try {
                ndkBuilder.build(line -> runOnUiThread(() -> {
                    log.append(line + "\n");
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                }));
                success = true;
                message = describeLibraries((System.currentTimeMillis() - started) / 1000.0);
            } catch (NdkBuilder.NdkBuildException e) {
                success = false;
                message = e.getMessage();
            }
            boolean ok = success;
            String result = message;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                dialog.dismiss();
                if (ok) {
                    showText("Build succeeded", log.getText() + "\n" + result);
                } else {
                    showText("Build failed", result);
                }
            });
        });
    }

    private String describeLibraries(double seconds) {
        StringBuilder sb = new StringBuilder(String.format("Finished in %.1f s. Libraries:\n", seconds));
        File[] abis = ndkBuilder.getLibrariesDirectory().listFiles(File::isDirectory);
        if (abis == null || abis.length == 0) return sb.append("(none were built — check LOCAL_MODULE and BUILD_SHARED_LIBRARY)").toString();
        for (File abi : abis) {
            File[] libs = abi.listFiles();
            if (libs == null) continue;
            for (File lib : libs) {
                sb.append("• ").append(abi.getName()).append('/').append(lib.getName())
                        .append(" (").append(Formatter.formatShortFileSize(this, lib.length())).append(")\n");
            }
        }
        return sb.toString();
    }

    private void clean() {
        executor.execute(() -> {
            try {
                ndkBuilder.clean();
                runOnUiThread(() -> SketchwareUtil.toast("Build output deleted; the next build compiles everything"));
            } catch (Exception e) {
                runOnUiThread(() -> SketchwareUtil.toastError(String.valueOf(e.getMessage())));
            }
        });
    }

    private TextView logView(String text) {
        TextView log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextIsSelectable(true);
        int padding = SketchwareUtil.dpToPx(20);
        log.setPadding(padding, SketchwareUtil.dpToPx(8), padding, 0);
        log.setText(text);
        return log;
    }

    private void showText(String title, String text) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView(text));
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(R.string.common_word_ok, null)
                .show();
    }

    // endregion

    private class FilesAdapter extends RecyclerView.Adapter<FilesAdapter.Holder> {
        private final ArrayList<File> files = new ArrayList<>();

        void setFiles(List<File> newFiles) {
            files.clear();
            files.addAll(newFiles);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemJniFileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            File file = files.get(position);
            holder.binding.name.setText(file.getName());
            if (file.isDirectory()) {
                String[] children = file.list();
                holder.binding.icon.setImageResource(R.drawable.ic_mtrl_folder);
                holder.binding.details.setText((children == null ? 0 : children.length) + " items");
            } else {
                holder.binding.icon.setImageResource(isBuildScript(file) ? R.drawable.ic_mtrl_terminal : R.drawable.ic_mtrl_code);
                holder.binding.details.setText(Formatter.formatShortFileSize(JniSourcesActivity.this, file.length())
                        + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(file.lastModified())));
            }
            holder.itemView.setOnClickListener(v -> {
                if (file.isDirectory()) {
                    openDirectory(file);
                } else {
                    edit(file);
                }
            });
            holder.binding.more.setOnClickListener(v -> showFileMenu(v, file));
        }

        private boolean isBuildScript(File file) {
            return file.getName().endsWith(".mk") || file.getName().equals("CMakeLists.txt");
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ItemJniFileBinding binding;

            Holder(ItemJniFileBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
