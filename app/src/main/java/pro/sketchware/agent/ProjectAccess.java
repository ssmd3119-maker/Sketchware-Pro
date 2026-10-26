package pro.sketchware.agent;

import android.content.Context;

import androidx.annotation.Nullable;

import com.besome.sketch.beans.ComponentBean;
import com.besome.sketch.beans.EventBean;
import com.besome.sketch.beans.HistoryViewBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ProjectLibraryBean;
import com.besome.sketch.beans.ViewBean;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import a.a.a.Ox;
import a.a.a.cC;
import a.a.a.eC;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.oq;
import a.a.a.uq;
import a.a.a.wq;
import a.a.a.yB;
import a.a.a.yq;
import mod.hilal.saif.components.ComponentsHandler;
import pro.sketchware.activities.editor.view.EventCodeBridge;
import pro.sketchware.managers.inject.InjectRootLayoutManager;
import pro.sketchware.tools.ViewBeanParser;
import pro.sketchware.utility.relativelayout.CircularDependencyDetector;

/**
 * What the AI agent can see and change in one project.
 * <p>
 * Plain files are read and written inside the project's data folder ({@code .sketchware/data/<sc_id>/}); the
 * generated sources of the last build ({@code .sketchware/mysc/<sc_id>/}) are readable under {@code generated/}.
 * The encrypted logic and view data is never touched directly: activities' Java goes through the Direct Java
 * editor's conversion to blocks, and layouts through the Direct XML editor's parser.
 */
public class ProjectAccess {

    /** Prefix of paths that point into the last build's generated sources (read-only). */
    public static final String GENERATED_PREFIX = "generated/";
    /** Sketchware's encrypted project data at the root of the data folder. */
    private static final Set<String> PROTECTED_FILES = Set.of("file", "library", "logic", "resource", "view");
    private static final int MAX_READ_CHARS = 200_000;
    private static final int MAX_LIST_ENTRIES = 600;
    private static final int MAX_SEARCH_MATCHES = 200;

    public static class AccessException extends Exception {
        public AccessException(String message) {
            super(message);
        }
    }

    private final Context context;
    private final String scId;
    private final File root;
    private final File generated;
    /** Whether a change needs the design screen to reload its views and events. */
    private volatile boolean projectDataChanged;

    public ProjectAccess(Context context, String scId) {
        this.context = context.getApplicationContext();
        this.scId = scId;
        root = new File(wq.b(scId));
        generated = new File(wq.d(scId));
    }

    public String getScId() {
        return scId;
    }

    public boolean isProjectDataChanged() {
        return projectDataChanged;
    }

    // region Project

    public String describeProject() {
        StringBuilder out = new StringBuilder();
        HashMap<String, Object> metadata = lC.b(scId);
        if (metadata != null) {
            out.append("App name: ").append(yB.c(metadata, "my_app_name")).append('\n');
            out.append("Package: ").append(yB.c(metadata, "my_sc_pkg_name")).append('\n');
            out.append("Version: ").append(yB.c(metadata, "sc_ver_name"))
                    .append(" (").append(yB.c(metadata, "sc_ver_code")).append(")\n");
        }
        out.append("\nActivities (Java via read_activity_java, layout via read_layout_xml):\n");
        for (ProjectFileBean file : jC.b(scId).b()) {
            out.append("- ").append(file.getJavaName().isEmpty() ? "(no Java)" : file.getJavaName())
                    .append("  layout ").append(file.getXmlName())
                    .append("  type ").append(typeName(file.fileType)).append('\n');
        }
        ArrayList<ProjectFileBean> customViews = jC.b(scId).c();
        if (customViews != null && !customViews.isEmpty()) {
            out.append("\nCustom views and drawers (layout only):\n");
            for (ProjectFileBean file : customViews) {
                out.append("- ").append(file.getXmlName()).append("  type ").append(typeName(file.fileType)).append('\n');
            }
        }
        out.append("\nProject folder (paths for the file tools):\n");
        String[] children = root.list();
        if (children != null) {
            Arrays.sort(children);
            for (String child : children) {
                if (PROTECTED_FILES.contains(child)) continue;
                out.append("- ").append(child).append(new File(root, child).isDirectory() ? "/" : "").append('\n');
            }
        }
        out.append(generated.isDirectory() ? "- generated/ (read-only sources of the last build)\n"
                : "- generated/ doesn't exist yet: the project hasn't been built\n");
        return out.toString();
    }

    private static String typeName(int fileType) {
        return switch (fileType) {
            case ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY -> "activity";
            case ProjectFileBean.PROJECT_FILE_TYPE_CUSTOM_VIEW -> "custom view";
            case ProjectFileBean.PROJECT_FILE_TYPE_DRAWER -> "drawer";
            case ProjectFileBean.PROJECT_FILE_TYPE_FRAGMENT -> "fragment";
            case ProjectFileBean.PROJECT_FILE_TYPE_SHEET -> "bottom sheet";
            case ProjectFileBean.PROJECT_FILE_TYPE_DIALOG_FRAGMENT -> "dialog fragment";
            default -> String.valueOf(fileType);
        };
    }

    // endregion

    // region Files

    /**
     * @param write Whether the file is going to be changed.
     * @return The file a tool path points to.
     */
    File resolve(String path, boolean write) throws AccessException {
        String clean = path == null ? "" : path.trim().replace('\\', '/');
        while (clean.startsWith("./")) clean = clean.substring(2);
        if (clean.startsWith("/")) {
            throw new AccessException("Use a path relative to the project folder, e.g. files/java/MyHelper.java");
        }
        for (String part : clean.split("/")) {
            if (part.equals("..")) throw new AccessException("Paths can't contain \"..\"");
        }
        File base = root;
        String relative = clean;
        if (clean.equals("generated") || clean.startsWith(GENERATED_PREFIX)) {
            if (write) {
                throw new AccessException("generated/ holds build output and is recreated by every build; change the"
                        + " project's own files, activities or layouts instead.");
            }
            base = generated;
            relative = clean.equals("generated") ? "" : clean.substring(GENERATED_PREFIX.length());
        } else {
            String first = clean.contains("/") ? clean.substring(0, clean.indexOf('/')) : clean;
            if (PROTECTED_FILES.contains(first)) {
                throw new AccessException("\"" + first + "\" is Sketchware's encrypted project data. Use read_activity_java,"
                        + " edit_activity_java, read_layout_xml or edit_layout_xml instead.");
            }
        }
        return relative.isEmpty() ? base : new File(base, relative);
    }

    public String displayPath(File file) {
        String path = file.getAbsolutePath();
        String generatedPath = generated.getAbsolutePath();
        if (path.equals(generatedPath)) return "generated";
        if (path.startsWith(generatedPath + File.separator)) {
            return GENERATED_PREFIX + path.substring(generatedPath.length() + 1);
        }
        String rootPath = root.getAbsolutePath();
        if (path.equals(rootPath)) return ".";
        return path.startsWith(rootPath + File.separator) ? path.substring(rootPath.length() + 1) : path;
    }

    public String listFiles(String path, boolean recursive) throws AccessException {
        File dir = resolve(path, false);
        if (!dir.exists()) throw new AccessException(displayPath(dir) + " doesn't exist");
        if (!dir.isDirectory()) throw new AccessException(displayPath(dir) + " is a file; use read_file");
        StringBuilder out = new StringBuilder();
        int count = 0;
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(dir);
        while (!queue.isEmpty()) {
            File current = queue.poll();
            File[] children = current.listFiles();
            if (children == null) continue;
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                if (current.equals(root) && PROTECTED_FILES.contains(child.getName())) continue;
                if (++count > MAX_LIST_ENTRIES) {
                    out.append("... more entries not shown; list a subfolder\n");
                    return out.toString();
                }
                String display = displayPath(child);
                if (child.isDirectory()) {
                    out.append(display).append("/\n");
                    if (recursive) queue.add(child);
                } else {
                    out.append(display).append("  (").append(child.length()).append(" bytes)\n");
                }
            }
        }
        if (dir.equals(root)) {
            out.append(generated.isDirectory() ? "generated/  (read-only build output)\n" : "");
        }
        return count == 0 ? displayPath(dir) + " is empty" : out.toString();
    }

    public String readFile(String path, int offset, int limit) throws AccessException, IOException {
        File file = resolve(path, false);
        if (!file.isFile()) {
            throw new AccessException(displayPath(file) + (file.isDirectory() ? " is a folder; use list_files" : " doesn't exist"));
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (looksBinary(bytes)) {
            throw new AccessException(displayPath(file) + " is a binary file (" + bytes.length + " bytes) and can't be read as text");
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        int start = Math.max(0, offset - 1);
        int end = limit > 0 ? Math.min(lines.length, start + limit) : lines.length;
        StringBuilder out = new StringBuilder();
        int i = start;
        for (; i < end; i++) {
            if (out.length() + lines[i].length() > MAX_READ_CHARS) break;
            out.append(lines[i]).append('\n');
        }
        if (start >= lines.length && lines.length > 0) {
            return "The file has only " + lines.length + " lines.";
        }
        if (i < lines.length) {
            out.append("\n[Showing lines ").append(start + 1).append('-').append(i).append(" of ").append(lines.length)
                    .append(". Read more with offset=").append(i + 1).append(".]");
        }
        return out.toString();
    }

    @Nullable
    public String readTextIfExists(String path) throws AccessException, IOException {
        File file = resolve(path, false);
        return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
    }

    public String writeFile(String path, String content) throws AccessException, IOException {
        File file = resolve(path, true);
        if (file.isDirectory()) throw new AccessException(displayPath(file) + " is a folder");
        boolean existed = file.exists();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Can't create " + displayPath(parent));
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return (existed ? "Updated " : "Created ") + displayPath(file) + " (" + lineCount(content) + " lines)";
    }

    public String editFile(String path, String oldString, String newString, boolean replaceAll) throws AccessException, IOException {
        File file = resolve(path, true);
        if (!file.isFile()) throw new AccessException(displayPath(file) + " doesn't exist; use write_file to create it");
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String updated = replace(content, oldString, newString, replaceAll, displayPath(file));
        Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
        return "Edited " + displayPath(file);
    }

    public String delete(String path) throws AccessException, IOException {
        File file = resolve(path, true);
        if (file.equals(root)) throw new AccessException("Can't delete the whole project folder");
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) throw new AccessException(displayPath(file) + " doesn't exist");
        String display = displayPath(file);
        deleteRecursively(file);
        return "Deleted " + display;
    }

    public String search(String regex, String path, @Nullable String fileNameFilter) throws AccessException, IOException {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new AccessException("Invalid regular expression: " + e.getDescription());
        }
        File start = resolve(path, false);
        if (!start.exists()) throw new AccessException(displayPath(start) + " doesn't exist");
        String filter = fileNameFilter == null ? null : fileNameFilter.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int matches = 0;
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            File current = queue.poll();
            if (current.isDirectory()) {
                File[] children = current.listFiles();
                if (children == null) continue;
                Arrays.sort(children);
                for (File child : children) {
                    if (current.equals(root) && PROTECTED_FILES.contains(child.getName())) continue;
                    if (!Files.isSymbolicLink(child.toPath())) queue.add(child);
                }
                continue;
            }
            if (filter != null && !current.getName().toLowerCase(Locale.ROOT).contains(filter)) continue;
            if (current.length() > 2_000_000) continue;
            byte[] bytes = Files.readAllBytes(current.toPath());
            if (looksBinary(bytes)) continue;
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = pattern.matcher(lines[i]);
                if (!matcher.find()) continue;
                String line = lines[i].length() > 300 ? lines[i].substring(0, 300) + "..." : lines[i];
                out.append(displayPath(current)).append(':').append(i + 1).append(": ").append(line.trim()).append('\n');
                if (++matches >= MAX_SEARCH_MATCHES) {
                    out.append("... more matches not shown; narrow the search\n");
                    return out.toString();
                }
            }
        }
        return matches == 0 ? "No matches" : out.toString();
    }

    // endregion

    // region Activities

    private ProjectFileBean findActivity(String name) throws AccessException {
        String wanted = name == null ? "" : name.trim();
        if (!wanted.endsWith(".java")) wanted = wanted + ".java";
        ArrayList<String> available = new ArrayList<>();
        for (ProjectFileBean file : jC.b(scId).b()) {
            if (file.getJavaName().isEmpty()) continue;
            if (file.getJavaName().equals(wanted)) return file;
            available.add(file.getJavaName());
        }
        throw new AccessException("No activity " + wanted + ". Activities: " + String.join(", ", available));
    }

    public String readActivityJava(String activity) throws AccessException {
        ProjectFileBean file = findActivity(activity);
        String code = new EventCodeBridge(scId, file).generate(new yq(context, scId));
        if (code == null || code.isEmpty()) throw new AccessException("Couldn't generate the Java of " + file.getJavaName());
        return normalizeLineEndings(code);
    }

    /**
     * Saves an activity's new Java like the Direct Java editor: events become blocks, new methods More Blocks,
     * new fields variables, new imports project imports.
     */
    public String writeActivityJava(String activity, String code) throws AccessException {
        ProjectFileBean file = findActivity(activity);
        EventCodeBridge bridge = new EventCodeBridge(scId, file);
        String generatedCode = normalizeLineEndings(bridge.generate(new yq(context, scId)));
        EventCodeBridge.Plan plan = bridge.plan(generatedCode, normalizeLineEndings(code), false);
        if (!plan.hasChanges()) return "No changes to save. " + String.join(" ", plan.warnings());
        bridge.apply(plan, context);
        projectDataChanged = true;
        StringBuilder out = new StringBuilder("Saved ").append(file.getJavaName()).append(": ").append(plan.summary());
        for (String warning : plan.warnings()) out.append("\nNote: ").append(warning);
        return out.toString();
    }

    public String editActivityJava(String activity, String oldString, String newString, boolean replaceAll) throws AccessException {
        String code = readActivityJava(activity);
        return writeActivityJava(activity, replace(code, oldString, newString, replaceAll, findActivity(activity).getJavaName()));
    }

    // endregion

    // region Events and components

    /** One event that exists or can be added. */
    private record EventChoice(int eventType, int targetType, String targetId, String eventName) {
    }

    /**
     * @return Every event this activity can have, like the Add Event screen lists them.
     */
    private List<EventChoice> eventChoices(ProjectFileBean file) {
        eC dataManager = jC.a(scId);
        ArrayList<EventChoice> choices = new ArrayList<>();
        for (String event : oq.getAllActivityEvents()) {
            if (event == null || event.isEmpty()) continue;
            choices.add(new EventChoice(EventBean.EVENT_TYPE_ACTIVITY, 0, event, event));
        }
        ArrayList<ViewBean> views = new ArrayList<>();
        ArrayList<ViewBean> layoutViews = dataManager.d(file.getXmlName());
        if (layoutViews != null) views.addAll(layoutViews);
        ViewBean fab = file.hasActivityOption(ProjectFileBean.OPTION_ACTIVITY_FAB) ? dataManager.h(file.getXmlName()) : null;
        if (fab != null) views.add(fab);
        for (ViewBean view : views) {
            for (String event : oq.getEventsForClass(view.getClassInfo())) {
                if (event == null || event.isEmpty()) continue;
                if (event.equals("onBindCustomView") && (view.customView == null || view.customView.isEmpty()
                        || view.customView.equals("none"))) continue;
                choices.add(new EventChoice(EventBean.EVENT_TYPE_VIEW, view.type, view.id, event));
            }
        }
        ArrayList<ComponentBean> components = dataManager.e(file.getJavaName());
        if (components != null) {
            for (ComponentBean component : components) {
                for (String event : oq.getComponentEventsForClass(component.getClassInfo())) {
                    if (event == null || event.isEmpty()) continue;
                    choices.add(new EventChoice(EventBean.EVENT_TYPE_COMPONENT, component.type, component.componentId, event));
                }
            }
        }
        return choices;
    }

    private Set<String> existingEventKeys(String javaName) {
        Set<String> keys = new HashSet<>();
        ArrayList<EventBean> events = jC.a(scId).g(javaName);
        if (events != null) {
            for (EventBean event : events) keys.add(event.targetId + "_" + event.eventName);
        }
        return keys;
    }

    public String listEvents(String activity) throws AccessException {
        ProjectFileBean file = findActivity(activity);
        Set<String> existing = existingEventKeys(file.getJavaName());
        StringBuilder added = new StringBuilder();
        LinkedHashMap<String, ArrayList<String>> addable = new LinkedHashMap<>();
        for (EventChoice choice : eventChoices(file)) {
            String target = choice.eventType() == EventBean.EVENT_TYPE_ACTIVITY ? "activity" : choice.targetId();
            if (existing.contains(choice.targetId() + "_" + choice.eventName())) {
                added.append("- ").append(target).append(": ").append(choice.eventName()).append('\n');
            } else {
                addable.computeIfAbsent(target, key -> new ArrayList<>()).add(choice.eventName());
            }
        }
        StringBuilder out = new StringBuilder("Events of ").append(file.getJavaName()).append(" (their code is in")
                .append(" read_activity_java's \"//#region sw:<target>_<event>\" sections):\n");
        out.append(added.length() == 0 ? "(none)\n" : added);
        out.append("\nEvents that can be added with add_event (target: events):\n");
        for (var entry : addable.entrySet()) {
            out.append("- ").append(entry.getKey()).append(": ").append(String.join(", ", entry.getValue())).append('\n');
        }
        return out.toString();
    }

    /**
     * Adds an event like the Add Event screen and saves its code as blocks.
     *
     * @param target "activity" for activity events, or a widget's or component's id.
     */
    public String addEvent(String activity, String target, String eventName, String code) throws AccessException {
        ProjectFileBean file = findActivity(activity);
        String wantedTarget = target == null ? "" : target.trim();
        String wantedEvent = eventName == null ? "" : eventName.trim();
        EventChoice match = null;
        ArrayList<String> targetEvents = new ArrayList<>();
        for (EventChoice choice : eventChoices(file)) {
            boolean sameTarget = choice.eventType() == EventBean.EVENT_TYPE_ACTIVITY
                    ? wantedTarget.equals("activity") || wantedTarget.equals(choice.targetId())
                    : wantedTarget.equals(choice.targetId());
            if (!sameTarget) continue;
            targetEvents.add(choice.eventName());
            if (choice.eventName().equals(wantedEvent)) match = choice;
        }
        if (match == null) {
            if (targetEvents.isEmpty()) {
                throw new AccessException("\"" + wantedTarget + "\" isn't \"activity\" or a widget or component of "
                        + file.getJavaName() + ". Call list_events to see the targets.");
            }
            throw new AccessException(wantedTarget + " has no event \"" + wantedEvent + "\". Its events: " + String.join(", ", targetEvents));
        }
        String key = match.targetId() + "_" + match.eventName();
        if (existingEventKeys(file.getJavaName()).contains(key)) {
            throw new AccessException("The event " + key + " already exists. Change its code in the \"//#region sw:" + key
                    + "\" section with edit_activity_java.");
        }
        EventCodeBridge bridge = new EventCodeBridge(scId, file);
        bridge.apply(bridge.planNewEvent(match.eventType(), match.targetType(), match.targetId(), match.eventName(),
                code == null ? "" : code), context);
        projectDataChanged = true;
        return "Added the event " + key + " to " + file.getJavaName() + ". Read its \"//#region sw:" + key
                + "\" section with read_activity_java to check the code and the parameters the event provides.";
    }

    /** Component types offered when adding components, like the Add Component sheet. */
    private static List<ComponentBean> componentTypes() {
        ArrayList<ComponentBean> types = new ArrayList<>();
        int[] builtIn = {ComponentBean.COMPONENT_TYPE_INTENT, ComponentBean.COMPONENT_TYPE_SHAREDPREF,
                ComponentBean.COMPONENT_TYPE_FILE_PICKER, ComponentBean.COMPONENT_TYPE_CALENDAR,
                ComponentBean.COMPONENT_TYPE_VIBRATOR, ComponentBean.COMPONENT_TYPE_TIMERTASK,
                ComponentBean.COMPONENT_TYPE_DIALOG, ComponentBean.COMPONENT_TYPE_MEDIAPLAYER,
                ComponentBean.COMPONENT_TYPE_SOUNDPOOL, ComponentBean.COMPONENT_TYPE_OBJECTANIMATOR,
                ComponentBean.COMPONENT_TYPE_CAMERA, ComponentBean.COMPONENT_TYPE_GYROSCOPE,
                ComponentBean.COMPONENT_TYPE_TEXT_TO_SPEECH, ComponentBean.COMPONENT_TYPE_SPEECH_TO_TEXT,
                ComponentBean.COMPONENT_TYPE_REQUEST_NETWORK, ComponentBean.COMPONENT_TYPE_BLUETOOTH_CONNECT,
                ComponentBean.COMPONENT_TYPE_LOCATION_MANAGER, ComponentBean.COMPONENT_TYPE_PROGRESS_DIALOG,
                ComponentBean.COMPONENT_TYPE_DATE_PICKER_DIALOG, ComponentBean.COMPONENT_TYPE_TIME_PICKER_DIALOG,
                ComponentBean.COMPONENT_TYPE_NOTIFICATION, ComponentBean.COMPONENT_TYPE_FRAGMENT_ADAPTER,
                ComponentBean.COMPONENT_TYPE_INTERSTITIAL_AD, ComponentBean.COMPONENT_TYPE_REWARDED_VIDEO_AD,
                ComponentBean.COMPONENT_TYPE_FIREBASE, ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH,
                ComponentBean.COMPONENT_TYPE_FIREBASE_STORAGE, ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH_PHONE,
                ComponentBean.COMPONENT_TYPE_FIREBASE_CLOUD_MESSAGE, ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH_GOOGLE_LOGIN};
        for (int type : builtIn) types.add(new ComponentBean(type));
        ComponentsHandler.add(types);
        return types;
    }

    private String componentName(int type) {
        try {
            return ComponentBean.getComponentName(context, type);
        } catch (RuntimeException e) {
            return "type " + type;
        }
    }

    public String listComponents(String activity) throws AccessException {
        ProjectFileBean file = findActivity(activity);
        StringBuilder out = new StringBuilder("Components of ").append(file.getJavaName()).append(":\n");
        ArrayList<ComponentBean> components = jC.a(scId).e(file.getJavaName());
        if (components == null || components.isEmpty()) {
            out.append("(none)\n");
        } else {
            for (ComponentBean component : components) {
                out.append("- ").append(component.componentId).append(": ").append(componentName(component.type));
                if (component.param1 != null && !component.param1.isEmpty()) out.append(" (").append(component.param1).append(')');
                out.append('\n');
            }
        }
        out.append("\nTypes add_component accepts: ");
        ArrayList<String> names = new ArrayList<>();
        for (ComponentBean type : componentTypes()) names.add(componentName(type.type));
        return out.append(String.join(", ", names)).append('\n').toString();
    }

    /**
     * Adds a component like the Add Component sheet, with the same name rules and library requirements.
     *
     * @param parameter File name for SharedPreferences, path for Firebase DB/Storage/Auth, MIME type for FilePicker.
     */
    public String addComponent(String activity, String typeName, String name, @Nullable String parameter) throws AccessException {
        ProjectFileBean file = findActivity(activity);
        String wanted = typeName == null ? "" : typeName.replace(" ", "").toLowerCase(Locale.ROOT);
        Integer type = null;
        ArrayList<String> names = new ArrayList<>();
        for (ComponentBean candidate : componentTypes()) {
            String candidateName = componentName(candidate.type);
            names.add(candidateName);
            if (candidateName.replace(" ", "").toLowerCase(Locale.ROOT).equals(wanted)
                    || ComponentBean.getComponentTypeName(candidate.type).toLowerCase(Locale.ROOT).equals(wanted)
                    || String.valueOf(candidate.type).equals(wanted)) {
                type = candidate.type;
            }
        }
        if (type == null) throw new AccessException("Unknown component type \"" + typeName + "\". Types: " + String.join(", ", names));

        String id = name == null ? "" : name.trim();
        if (!id.matches("[a-zA-Z][a-zA-Z0-9_]*")) {
            throw new AccessException("The component name must start with a letter and contain only letters, digits and _");
        }
        eC dataManager = jC.a(scId);
        if (Arrays.asList(uq.b).contains(id) || Arrays.asList(uq.a()).contains(id)) {
            throw new AccessException("\"" + id + "\" is a reserved name; choose another");
        }
        if (dataManager.a(file).contains(id)) {
            throw new AccessException("\"" + id + "\" is already used by a widget, variable, list or component of " + file.getJavaName());
        }
        String param = parameter == null ? "" : parameter.trim();
        switch (type) {
            case ComponentBean.COMPONENT_TYPE_SHAREDPREF -> {
                if (param.isEmpty() || param.length() > 20) {
                    throw new AccessException("SharedPreferences needs a file name of 1 to 20 characters in \"parameter\"");
                }
            }
            case ComponentBean.COMPONENT_TYPE_FIREBASE, ComponentBean.COMPONENT_TYPE_FIREBASE_STORAGE -> {
                if (param.length() > 100) throw new AccessException("The Firebase path can be at most 100 characters");
                if (ProjectLibraryBean.LIB_USE_N.equals(jC.c(scId).d().useYn)) {
                    throw new AccessException("Enable Firebase in the project's Library Manager first; the user has to do that.");
                }
            }
            case ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH -> {
                ProjectLibraryBean firebase = jC.c(scId).d();
                if (ProjectLibraryBean.LIB_USE_N.equals(firebase.useYn) || firebase.reserved2 == null || firebase.reserved2.trim().isEmpty()) {
                    throw new AccessException("Set up Firebase (with its API key) in the project's Library Manager first; the user has to do that.");
                }
            }
            case ComponentBean.COMPONENT_TYPE_FRAGMENT_ADAPTER -> {
                if (ProjectLibraryBean.LIB_USE_N.equals(jC.c(scId).c().useYn)) {
                    throw new AccessException("Enable AppCompat and Design in the project's Library Manager first; the user has to do that.");
                }
            }
            case ComponentBean.COMPONENT_TYPE_INTERSTITIAL_AD, ComponentBean.COMPONENT_TYPE_REWARDED_VIDEO_AD -> {
                if (ProjectLibraryBean.LIB_USE_N.equals(jC.c(scId).b().useYn)) {
                    throw new AccessException("Set up AdMob in the project's Library Manager first; the user has to do that.");
                }
            }
            case ComponentBean.COMPONENT_TYPE_FILE_PICKER -> {
                if (param.isEmpty() || param.length() > 50) {
                    throw new AccessException("FilePicker needs a MIME type such as \"image/*\" in \"parameter\"");
                }
            }
            default -> {
            }
        }
        synchronized (dataManager) {
            switch (type) {
                case ComponentBean.COMPONENT_TYPE_SHAREDPREF, ComponentBean.COMPONENT_TYPE_FIREBASE,
                     ComponentBean.COMPONENT_TYPE_FIREBASE_STORAGE, ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH,
                     ComponentBean.COMPONENT_TYPE_FILE_PICKER -> dataManager.a(file.getJavaName(), type, id, param);
                default -> dataManager.a(file.getJavaName(), type, id);
            }
            dataManager.k();
        }
        projectDataChanged = true;
        return "Added the " + componentName(type) + " component \"" + id + "\" to " + file.getJavaName()
                + ". Its events are now listed by list_events.";
    }

    // endregion

    // region Layouts

    private ProjectFileBean findLayout(String name) throws AccessException {
        String wanted = name == null ? "" : name.trim();
        if (wanted.startsWith("layout/")) wanted = wanted.substring("layout/".length());
        if (!wanted.endsWith(".xml")) wanted = wanted + ".xml";
        ArrayList<String> available = new ArrayList<>();
        ArrayList<ProjectFileBean> files = new ArrayList<>(jC.b(scId).b());
        ArrayList<ProjectFileBean> customViews = jC.b(scId).c();
        if (customViews != null) files.addAll(customViews);
        for (ProjectFileBean file : files) {
            if (file.getXmlName().equals(wanted)) return file;
            available.add(file.getXmlName());
        }
        throw new AccessException("No layout " + wanted + ". Layouts: " + String.join(", ", available));
    }

    public String readLayoutXml(String layout) throws AccessException {
        ProjectFileBean file = findLayout(layout);
        String xmlName = file.getXmlName();
        eC dataManager = jC.a(scId);
        Ox generator = new Ox(new yq(context, scId).N, file);
        generator.setExcludeAppCompat(true);
        generator.a(eC.a(dataManager.d(xmlName)), dataManager.h(xmlName));
        return generator.b();
    }

    /**
     * Replaces a layout's views like the Direct XML editor, keeping undo history in the design editor.
     */
    public String writeLayoutXml(String layout, String xml) throws AccessException {
        ProjectFileBean file = findLayout(layout);
        String xmlName = file.getXmlName();
        ArrayList<ViewBean> views;
        ViewBeanParser parser;
        try {
            parser = new ViewBeanParser(xml);
            parser.setSkipRoot(true);
            views = parser.parse();
        } catch (Exception e) {
            throw new AccessException("The XML isn't valid: " + e.getMessage());
        }
        for (ViewBean view : views) {
            CircularDependencyDetector detector = new CircularDependencyDetector(views, view);
            for (String attribute : view.parentAttributes.keySet()) {
                if (!detector.isLegalAttribute(view.parentAttributes.get(attribute), attribute)) {
                    throw new AccessException("Circular RelativeLayout dependency in \"" + view.name + "\" ("
                            + attribute + "). Resolve it and try again.");
                }
            }
        }
        new InjectRootLayoutManager(scId).set(xmlName, InjectRootLayoutManager.toRoot(parser.getRootAttributes()));
        HistoryViewBean history = new HistoryViewBean();
        history.actionOverride(views, jC.a(scId).d(xmlName));
        cC historyManager = cC.c(scId);
        if (!historyManager.c.containsKey(xmlName)) historyManager.e(xmlName);
        historyManager.a(xmlName);
        historyManager.a(xmlName, history);
        jC.a(scId).c.put(xmlName, views);
        projectDataChanged = true;
        return "Saved " + xmlName + " (" + views.size() + (views.size() == 1 ? " view)" : " views)");
    }

    public String editLayoutXml(String layout, String oldString, String newString, boolean replaceAll) throws AccessException {
        String xml = readLayoutXml(layout);
        return writeLayoutXml(layout, replace(xml, oldString, newString, replaceAll, findLayout(layout).getXmlName()));
    }

    // endregion

    // region Helpers

    static String replace(String content, String oldString, String newString, boolean replaceAll, String name) throws AccessException {
        if (oldString == null || oldString.isEmpty()) throw new AccessException("old_string can't be empty");
        if (oldString.equals(newString)) throw new AccessException("old_string and new_string are the same");
        String text = normalizeLineEndings(content);
        String target = normalizeLineEndings(oldString);
        int first = text.indexOf(target);
        if (first < 0) {
            throw new AccessException("old_string wasn't found in " + name + ". Read it again and copy the text exactly,"
                    + " including indentation.");
        }
        String replacement = normalizeLineEndings(newString);
        if (replaceAll) return text.replace(target, replacement);
        if (text.indexOf(target, first + 1) >= 0) {
            throw new AccessException("old_string occurs more than once in " + name + ". Include more surrounding lines"
                    + " so it's unique, or set replace_all.");
        }
        return text.substring(0, first) + replacement + text.substring(first + target.length());
    }

    static String normalizeLineEndings(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static int lineCount(String text) {
        if (text.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        return text.endsWith("\n") ? lines - 1 : lines;
    }

    private static boolean looksBinary(byte[] bytes) {
        int check = Math.min(bytes.length, 8000);
        for (int i = 0; i < check; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) throw new IOException("Can't delete " + file);
    }

    // endregion
}
