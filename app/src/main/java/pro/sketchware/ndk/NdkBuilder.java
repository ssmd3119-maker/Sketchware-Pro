package pro.sketchware.ndk;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import pro.sketchware.utility.FilePathUtil;

/**
 * Builds a project's C/C++ sources ({@code files/jni/Android.mk}) with the installed NDK's {@code ndk-build}.
 * <p>
 * Objects and libraries go to {@code .sketchware/data/<sc_id>/ndk/}, so later builds only recompile what
 * changed. The libraries ({@code libs/<abi>/lib*.so}) are packaged into the APK by the project builder.
 */
public class NdkBuilder {

    public interface LogListener {
        void onLine(String line);
    }

    /** ABIs built when the project has no Application.mk. */
    public static final String DEFAULT_ABIS = "armeabi-v7a arm64-v8a";
    public static final String DEFAULT_PLATFORM = "android-21";

    private final Context context;
    private final String scId;
    private final FilePathUtil paths = new FilePathUtil();
    private volatile Process process;

    public NdkBuilder(Context context, String scId) {
        this.context = context.getApplicationContext();
        this.scId = scId;
    }

    public File getJniDirectory() {
        return new File(paths.getPathJni(scId));
    }

    public File getLibrariesDirectory() {
        return new File(paths.getPathNdkOutput(scId), "libs");
    }

    public File getObjectsDirectory() {
        return new File(paths.getPathNdkOutput(scId), "obj");
    }

    /**
     * @return Whether the project has C/C++ sources to build.
     */
    public boolean hasSources() {
        return new File(getJniDirectory(), "Android.mk").isFile();
    }

    /**
     * Runs ndk-build.
     *
     * @return The full output of ndk-build.
     * @throws NdkBuildException If the NDK isn't installed or the build fails; its message has the output.
     */
    public String build(@Nullable LogListener listener) throws NdkBuildException {
        File ndkBuild = NdkManager.findNdkBuild(context);
        if (ndkBuild == null) {
            throw new NdkBuildException("This project has C/C++ code (files/jni/Android.mk), but no NDK is installed.\n"
                    + "Install one in Settings > NDK manager, or remove the project's jni sources.");
        }
        File jni = getJniDirectory();
        File libs = getLibrariesDirectory();
        File objs = getObjectsDirectory();
        try {
            // Old libraries of ABIs no longer built would otherwise end up in the APK.
            NdkManager.deleteRecursively(libs);
        } catch (IOException e) {
            throw new NdkBuildException("Can't clean " + libs + ": " + e.getMessage());
        }
        objs.mkdirs();
        libs.mkdirs();
        ndkBuild.setExecutable(true, false);

        List<String> command = command(ndkBuild);
        command.add("NDK_PROJECT_PATH=" + jni.getParent());
        command.add("APP_BUILD_SCRIPT=" + new File(jni, "Android.mk").getAbsolutePath());
        File applicationMk = new File(jni, "Application.mk");
        if (applicationMk.isFile()) {
            command.add("NDK_APPLICATION_MK=" + applicationMk.getAbsolutePath());
        } else {
            command.add("APP_ABI=" + DEFAULT_ABIS);
            command.add("APP_PLATFORM=" + DEFAULT_PLATFORM);
        }
        command.add("NDK_OUT=" + objs.getAbsolutePath());
        command.add("NDK_LIBS_OUT=" + libs.getAbsolutePath());
        command.add("-j" + Math.max(1, Runtime.getRuntime().availableProcessors()));

        StringBuilder output = new StringBuilder();
        int exitCode;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(jni)
                    .redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.put("TMPDIR", context.getCacheDir().getAbsolutePath());
            environment.put("HOME", context.getFilesDir().getAbsolutePath());
            environment.put("NDK_ROOT", ndkBuild.getParent());
            process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (listener != null) listener.onLine(line);
                }
            }
            exitCode = process.waitFor();
        } catch (IOException e) {
            throw new NdkBuildException("Couldn't run " + ndkBuild + ": " + e.getMessage() + "\n" + output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NdkBuildException("NDK build was cancelled.\n" + output);
        } finally {
            process = null;
        }
        if (exitCode != 0) {
            throw new NdkBuildException("NDK build failed (exit code " + exitCode + "):\n" + output);
        }
        return output.toString();
    }

    /**
     * Stops a running build.
     */
    public void cancel() {
        Process running = process;
        if (running != null) running.destroy();
    }

    /**
     * Deletes the build output, so the next build compiles everything.
     */
    public void clean() throws IOException {
        NdkManager.deleteRecursively(new File(paths.getPathNdkOutput(scId)));
    }

    /**
     * Runs ndk-build without arguments that build anything, to check the NDK works.
     */
    public static String version(Context context) throws IOException, InterruptedException {
        File ndkBuild = NdkManager.findNdkBuild(context);
        if (ndkBuild == null) throw new IOException("No NDK is installed.");
        ndkBuild.setExecutable(true, false);
        List<String> command = command(ndkBuild);
        command.add("--version");
        Process process = new ProcessBuilder(command).directory(context.getCacheDir()).redirectErrorStream(true).start();
        String output = NdkManager.readAll(process.getInputStream());
        process.waitFor();
        return output;
    }

    /**
     * ndk-build is usually a shell script; scripts are run through the system shell, as files in app
     * storage may not have a usable interpreter line.
     */
    private static List<String> command(File ndkBuild) {
        ArrayList<String> command = new ArrayList<>();
        if (!isElf(ndkBuild)) command.add("/system/bin/sh");
        command.add(ndkBuild.getAbsolutePath());
        return command;
    }

    private static boolean isElf(File file) {
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            return in.read(magic) == 4 && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    public static class NdkBuildException extends Exception {
        public NdkBuildException(String message) {
            super(message);
        }
    }
}
