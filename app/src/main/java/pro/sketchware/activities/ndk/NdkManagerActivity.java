package pro.sketchware.activities.ndk;

import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;

import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.pranav.filepicker.FilePickerCallback;
import dev.pranav.filepicker.FilePickerDialogFragment;
import dev.pranav.filepicker.FilePickerOptions;
import mod.hey.studios.util.Helper;
import pro.sketchware.R;
import pro.sketchware.databinding.ActivityNdkManagerBinding;
import pro.sketchware.ndk.NdkBuilder;
import pro.sketchware.ndk.NdkManager;
import pro.sketchware.utility.SketchwareUtil;

/**
 * Installs, tests and removes the on-device NDK used to build projects' C/C++ code.
 */
public class NdkManagerActivity extends BaseAppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityNdkManagerBinding binding;
    private boolean busy;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        binding = ActivityNdkManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(Helper.getBackPressedClickListener(this));

        binding.helpText.setText("Projects build the C/C++ code in their jni folder (drawer > C/C++ (NDK)) with this NDK."
                + " Its libraries are packaged into the APK automatically.\n\n"
                + "Install an NDK package that runs on Android, for example AIDE's \"ndksupport\" package for your"
                + " device (.tar.gz, .tgz, .tar or .zip). It must contain an ndk-build program.\n\n"
                + "This device's ABI: " + NdkManager.getDeviceAbi());

        binding.installButton.setOnClickListener(v -> pickArchive());
        binding.testButton.setOnClickListener(v -> test());
        binding.uninstallButton.setOnClickListener(v -> confirmUninstall());
        refresh();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void refresh() {
        File install = NdkManager.getInstallation(this);
        File ndkBuild = NdkManager.findNdkBuild(this);
        boolean installed = ndkBuild != null;
        binding.statusTitle.setText(installed ? "NDK installed" : "NDK not installed");
        binding.statusIcon.setImageResource(installed ? R.drawable.ic_mtrl_check : R.drawable.ic_mtrl_terminal);
        if (installed) {
            binding.statusDetails.setText(install.getName() + "\n" + ndkBuild.getAbsolutePath());
            executor.execute(() -> {
                String size = Formatter.formatShortFileSize(this, NdkManager.sizeOf(install));
                runOnUiThread(() -> {
                    if (!isDestroyed() && !busy) {
                        binding.statusDetails.setText(install.getName() + " · " + size + "\n" + ndkBuild.getAbsolutePath());
                    }
                });
            });
        } else {
            binding.statusDetails.setText("Install an NDK package to build C/C++ code.");
        }
        binding.installButton.setText(installed ? "Reinstall from file" : "Install from file");
        binding.testButton.setVisibility(installed ? View.VISIBLE : View.GONE);
        binding.uninstallButton.setVisibility(installed ? View.VISIBLE : View.GONE);
        setBusy(false, null);
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        binding.progressLayout.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.progressText.setText(message);
        binding.installButton.setEnabled(!busy);
        binding.testButton.setEnabled(!busy);
        binding.uninstallButton.setEnabled(!busy);
    }

    private void pickArchive() {
        FilePickerOptions options = new FilePickerOptions();
        options.setTitle("Select an NDK package");
        options.setExtensions(new String[]{"gz", "tgz", "tar", "zip"});
        FilePickerCallback callback = new FilePickerCallback() {
            @Override
            public void onFilesSelected(@NotNull List<? extends File> files) {
                if (!files.isEmpty()) install(files.get(0));
            }
        };
        new FilePickerDialogFragment(options, callback).show(getSupportFragmentManager(), "ndk_picker");
    }

    private void install(File archive) {
        setBusy(true, "Preparing " + archive.getName() + "...");
        executor.execute(() -> {
            String error = null;
            try {
                long[] lastUpdate = {0};
                NdkManager.install(this, archive, entry -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate[0] < 100) return;
                    lastUpdate[0] = now;
                    runOnUiThread(() -> binding.progressText.setText(entry));
                });
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                refresh();
                if (failure == null) {
                    SketchwareUtil.toast("NDK installed");
                } else {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Installation failed")
                            .setMessage(failure)
                            .setPositiveButton(R.string.common_word_ok, null)
                            .show();
                }
            });
        });
    }

    private void test() {
        setBusy(true, "Running ndk-build --version...");
        executor.execute(() -> {
            String output;
            boolean ok;
            try {
                output = NdkBuilder.version(this).trim();
                ok = !output.isEmpty();
            } catch (Exception e) {
                output = e.getMessage() == null ? e.toString() : e.getMessage();
                ok = false;
            }
            String result = output.isEmpty() ? "ndk-build printed nothing." : output;
            boolean works = ok;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setBusy(false, null);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(works ? "ndk-build runs" : "ndk-build didn't run")
                        .setMessage(result)
                        .setPositiveButton(R.string.common_word_ok, null)
                        .show();
            });
        });
    }

    private void confirmUninstall() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Uninstall NDK?")
                .setMessage("Projects with C/C++ code can't be built until an NDK is installed again.")
                .setPositiveButton("Uninstall", (d, w) -> {
                    setBusy(true, "Removing NDK...");
                    executor.execute(() -> {
                        String error = null;
                        try {
                            NdkManager.uninstall(this);
                        } catch (Exception e) {
                            error = e.getMessage();
                        }
                        String failure = error;
                        runOnUiThread(() -> {
                            if (isDestroyed()) return;
                            refresh();
                            if (failure != null) SketchwareUtil.toastError(failure);
                        });
                    });
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }
}
