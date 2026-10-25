package pro.sketchware.ndk;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs and locates an on-device NDK, such as AIDE's {@code ndksupport-<abi>-<version>.tar.gz} package,
 * which contains an {@code ndk-build} that runs on Android.
 * <p>
 * The NDK lives in the app's internal storage ({@code files/ndk/}), because its tools have to be executable.
 * Only one NDK is installed at a time.
 */
public final class NdkManager {

    public interface ProgressListener {
        void onProgress(String entry);
    }

    private static final String INSTALLED_MARKER = ".installed";
    private static final int MAX_SEARCH_DEPTH = 6;

    private NdkManager() {
    }

    public static File getInstallRoot(Context context) {
        return new File(context.getFilesDir(), "ndk");
    }

    /**
     * @return The installed NDK's folder, or {@code null} if none is installed.
     */
    @Nullable
    public static File getInstallation(Context context) {
        File[] installs = getInstallRoot(context).listFiles(File::isDirectory);
        if (installs == null) return null;
        for (File install : installs) {
            if (new File(install, INSTALLED_MARKER).exists()) return install;
        }
        return null;
    }

    public static boolean isInstalled(Context context) {
        return findNdkBuild(context) != null;
    }

    /**
     * Finds the {@code ndk-build} program inside the installed NDK, wherever the package placed it
     * (e.g. {@code android-ndk-aide/ndk-build}).
     */
    @Nullable
    public static File findNdkBuild(Context context) {
        File install = getInstallation(context);
        return install == null ? null : findNdkBuildIn(install);
    }

    @Nullable
    private static File findNdkBuildIn(File install) {
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(install);
        depths.add(0);
        while (!queue.isEmpty()) {
            File dir = queue.poll();
            int depth = depths.poll();
            File candidate = new File(dir, "ndk-build");
            if (candidate.isFile()) return candidate;
            if (depth >= MAX_SEARCH_DEPTH) continue;
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isDirectory() && !Files.isSymbolicLink(child.toPath())) {
                    queue.add(child);
                    depths.add(depth + 1);
                }
            }
        }
        return null;
    }

    /**
     * @return The ABI of this device, e.g. {@code arm64-v8a}. On-device NDK packages are made per ABI.
     */
    public static String getDeviceAbi() {
        return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
    }

    /**
     * Installs an NDK package, replacing the installed one.
     *
     * @param archive A {@code .tar.gz}, {@code .tgz}, {@code .tar} or {@code .zip} file.
     */
    public static void install(Context context, File archive, @Nullable ProgressListener listener) throws IOException {
        File root = getInstallRoot(context);
        String installName = installName(archive.getName());
        // Extract next to the current NDK first, so a broken archive doesn't remove a working NDK.
        File staging = new File(root, ".installing-" + installName);
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Can't create " + staging);
        }

        String name = archive.getName().toLowerCase();
        try {
            if (name.endsWith(".zip")) {
                extractZip(archive, staging, listener);
            } else {
                try (InputStream in = new BufferedInputStream(new FileInputStream(archive))) {
                    InputStream tar = name.endsWith(".tar") ? in : new GZIPInputStream(in, 65536);
                    extractTar(tar, staging, listener);
                }
            }
            fixScriptInterpreters(staging);
            if (findNdkBuildIn(staging) == null) {
                throw new IOException("This archive doesn't contain an ndk-build program, so it isn't an on-device NDK package.");
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursively(staging);
            throw e;
        }

        // Replace the previous NDK.
        File[] previous = root.listFiles();
        if (previous != null) {
            for (File old : previous) {
                if (!old.equals(staging)) deleteRecursively(old);
            }
        }
        File target = new File(root, installName);
        if (!staging.renameTo(target)) {
            deleteRecursively(staging);
            throw new IOException("Can't move the NDK to " + target);
        }
        if (!new File(target, INSTALLED_MARKER).createNewFile()) {
            throw new IOException("Can't finish the installation in " + target);
        }
    }

    public static void uninstall(Context context) throws IOException {
        deleteRecursively(getInstallRoot(context));
    }

    private static String installName(String archiveName) {
        String name = archiveName.replaceAll("(?i)(\\.tar\\.gz|\\.tgz|\\.tar|\\.zip)$", "");
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return name.isEmpty() ? "ndk" : name;
    }

    /**
     * NDKs made for Linux or Termux start their scripts with {@code #!/bin/sh}, {@code #!/bin/bash} or
     * {@code #!/usr/bin/env bash}, which don't exist on Android. Point those scripts at the system shell.
     */
    static void fixScriptInterpreters(File dir) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) {
                fixScriptInterpreters(child);
            } else if (child.length() > 2 && child.length() < 1024 * 1024) {
                fixScriptInterpreter(child);
            }
        }
    }

    private static void fixScriptInterpreter(File file) throws IOException {
        byte[] content;
        try (InputStream in = new FileInputStream(file)) {
            byte[] start = new byte[2];
            if (!readFully(in, start) || start[0] != '#' || start[1] != '!') return;
        }
        content = Files.readAllBytes(file.toPath());
        int end = 0;
        while (end < content.length && content[end] != '\n') end++;
        String line = new String(content, 2, end - 2, StandardCharsets.UTF_8).trim();
        String[] parts = line.split("\\s+");
        String interpreter = parts[0];
        if (interpreter.isEmpty() || new File(interpreter).exists()) return;
        String program = interpreter.endsWith("/env") && parts.length > 1 ? parts[1] : interpreter.substring(interpreter.lastIndexOf('/') + 1);
        if (!program.equals("sh") && !program.equals("bash") && !program.equals("dash")) return;

        boolean executable = file.canExecute();
        byte[] shebang = "#!/system/bin/sh".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(shebang);
            out.write(content, end, content.length - end);
        }
        if (executable) file.setExecutable(true, false);
    }

    // region Archives

    /**
     * Extracts a tar stream: regular files keep their executable bit, symbolic and hard links are recreated.
     * Supports ustar, GNU long names and pax path headers.
     */
    static void extractTar(InputStream in, File target, @Nullable ProgressListener listener) throws IOException {
        byte[] header = new byte[512];
        byte[] buffer = new byte[65536];
        String longName = null;
        String longLink = null;
        while (true) {
            if (!readFully(in, header)) break;
            if (isZeroBlock(header)) break;

            String name = string(header, 0, 100);
            String prefix = string(header, 345, 155);
            if (!prefix.isEmpty() && "ustar".equals(string(header, 257, 5))) name = prefix + "/" + name;
            long size = number(header, 124, 12);
            int mode = (int) number(header, 100, 8);
            char type = (char) header[156];
            String link = string(header, 157, 100);

            if (type == 'L' || type == 'K' || type == 'x') {
                byte[] data = readEntry(in, size);
                if (type == 'L') {
                    longName = string(data, 0, data.length);
                } else if (type == 'K') {
                    longLink = string(data, 0, data.length);
                } else {
                    String paxPath = paxValue(data, "path");
                    String paxLink = paxValue(data, "linkpath");
                    if (paxPath != null) longName = paxPath;
                    if (paxLink != null) longLink = paxLink;
                }
                continue;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }
            if (longLink != null) {
                link = longLink;
                longLink = null;
            }

            File out = safeFile(target, name);
            if (out == null) {
                skip(in, size);
                continue;
            }
            if (listener != null) listener.onProgress(name);

            switch (type) {
                case '5' -> mkdirs(out);
                case '2' -> {
                    mkdirs(out.getParentFile());
                    out.delete();
                    try {
                        Os.symlink(link, out.getAbsolutePath());
                    } catch (ErrnoException e) {
                        throw new IOException("Can't create link " + name + " -> " + link, e);
                    }
                }
                case '1' -> {
                    File existing = safeFile(target, link);
                    if (existing == null) break;
                    mkdirs(out.getParentFile());
                    out.delete();
                    try {
                        Os.link(existing.getAbsolutePath(), out.getAbsolutePath());
                    } catch (ErrnoException e) {
                        // Some storage doesn't support hard links; a copy works the same for the NDK.
                        copy(existing, out);
                    }
                }
                case '0', '\0', '7' -> {
                    mkdirs(out.getParentFile());
                    try (OutputStream os = new FileOutputStream(out)) {
                        long remaining = size;
                        while (remaining > 0) {
                            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (read < 0) throw new IOException("Unexpected end of archive in " + name);
                            os.write(buffer, 0, read);
                            remaining -= read;
                        }
                    }
                    skip(in, padding(size));
                    if ((mode & 0111) != 0) out.setExecutable(true, false);
                    continue;
                }
                default -> {
                    // Devices, FIFOs etc. aren't needed.
                }
            }
            skip(in, size + padding(size));
        }
    }

    private static void extractZip(File archive, File target, @Nullable ProgressListener listener) throws IOException {
        byte[] buffer = new byte[65536];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = safeFile(target, entry.getName());
                if (out == null) continue;
                if (listener != null) listener.onProgress(entry.getName());
                if (entry.isDirectory()) {
                    mkdirs(out);
                    continue;
                }
                mkdirs(out.getParentFile());
                try (OutputStream os = new FileOutputStream(out)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) os.write(buffer, 0, read);
                }
                // ZIP files don't carry Unix permissions here; the NDK's tools must be executable.
                out.setExecutable(true, false);
            }
        }
    }

    /**
     * @return The file for an archive entry inside {@code target}, or {@code null} if the entry would
     * end up outside of it (e.g. {@code ../x}) or is the archive's root.
     */
    @Nullable
    static File safeFile(File target, String entryName) {
        String name = entryName.replace('\\', '/');
        while (name.startsWith("/")) name = name.substring(1);
        while (name.startsWith("./")) name = name.substring(2);
        if (name.isEmpty() || name.equals(".")) return null;
        for (String part : name.split("/")) {
            if (part.equals("..")) return null;
        }
        return new File(target, name);
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                if (offset == 0) return false;
                throw new IOException("Unexpected end of archive");
            }
            offset += read;
        }
        return true;
    }

    private static byte[] readEntry(InputStream in, long size) throws IOException {
        if (size > 16 * 1024 * 1024) throw new IOException("Archive header too large");
        byte[] data = new byte[(int) size];
        if (size > 0 && !readFully(in, data)) throw new IOException("Unexpected end of archive");
        skip(in, padding(size));
        return data;
    }

    private static void skip(InputStream in, long count) throws IOException {
        byte[] buffer = new byte[8192];
        while (count > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, count));
            if (read < 0) throw new IOException("Unexpected end of archive");
            count -= read;
        }
    }

    private static long padding(long size) {
        return (512 - (size % 512)) % 512;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String string(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < data.length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    /**
     * Reads an octal number, or a base-256 one as GNU tar writes large sizes.
     */
    private static long number(byte[] data, int offset, int length) {
        if ((data[offset] & 0x80) != 0) {
            long value = data[offset] & 0x7f;
            for (int i = 1; i < length; i++) value = (value << 8) | (data[offset + i] & 0xff);
            return value;
        }
        String text = string(data, offset, length).trim();
        return text.isEmpty() ? 0 : Long.parseLong(text, 8);
    }

    @Nullable
    private static String paxValue(byte[] data, String key) {
        String records = new String(data, StandardCharsets.UTF_8);
        for (String record : records.split("\n")) {
            int space = record.indexOf(' ');
            int equals = record.indexOf('=');
            if (space < 0 || equals < space) continue;
            if (record.substring(space + 1, equals).equals(key)) return record.substring(equals + 1);
        }
        return null;
    }

    private static void mkdirs(@Nullable File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir);
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
        to.setExecutable(from.canExecute(), false);
    }

    // endregion

    /**
     * Deletes a folder without following symbolic links, which NDK packages contain.
     */
    public static void deleteRecursively(@NonNull File file) throws IOException {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return;
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) throw new IOException("Can't delete " + file);
    }

    /**
     * @return Total size of a folder in bytes, not following symbolic links.
     */
    public static long sizeOf(File file) {
        if (Files.isSymbolicLink(file.toPath())) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) total += sizeOf(child);
        }
        return total;
    }

    /**
     * Reads a process's output while it runs.
     */
    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
