package pro.sketchware.activities.editor.view;

import android.content.Context;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.besome.sketch.beans.BlockBean;
import com.besome.sketch.beans.EventBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ViewBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.Jx;
import a.a.a.Lx;
import a.a.a.eC;
import a.a.a.jC;
import a.a.a.kq;
import a.a.a.oq;
import a.a.a.xB;
import a.a.a.yq;
import dev.aldi.sayuti.block.ExtraBlockFile;
import mod.hey.studios.editor.manage.block.ExtraBlockInfo;
import mod.hey.studios.editor.manage.block.v2.BlockLoader;
import mod.hey.studios.project.ProjectSettings;
import mod.jbk.util.LogUtil;
import pro.sketchware.utility.CustomVariableUtil;
import mod.hey.studios.moreblock.ReturnMoreblockManager;

/**
 * Converts an activity's blocks to Java and edited Java back to blocks, without any extra files.
 * <p>
 * To know which part of the generated Java belongs to which event, the activity is generated once with
 * every event's blocks wrapped in two temporary "add source directly" blocks that emit
 * {@code //#region sw:<eventKey>} and {@code //#endregion sw:<eventKey>}. The project's blocks are restored
 * right after generating.
 * <p>
 * Saving ({@link #plan} then {@link #apply}):
 * <ul>
 *     <li>each event section whose code changed becomes one "add source directly" block in that event;</li>
 *     <li>code outside the events, i.e. extra imports and extra class members such as methods, fields and
 *     inner classes, is kept in two sections stored under {@link Jx#JAVA_EDITOR_IMPORTS_KEY} and
 *     {@link Jx#JAVA_EDITOR_MEMBERS_KEY}. New imports or members typed anywhere else move there;</li>
 *     <li>renaming a More Block's method renames the More Block and its callers, and deleting the method
 *     deletes the More Block.</li>
 * </ul>
 */
public class EventCodeBridge {

    private static final String BEGIN = "//#region sw:";
    private static final String END = "//#endregion sw:";
    private static final Pattern REGION = Pattern.compile(
            "^[ \\t]*" + Pattern.quote(BEGIN) + "(\\S+)[ \\t]*\\R(.*?)^[ \\t]*" + Pattern.quote(END) + "\\1[ \\t]*$",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern MARKER_LINE = Pattern.compile(
            "^[ \\t]*//#(?:end)?region sw:\\S*[ \\t]*\\R?", Pattern.MULTILINE);
    /** The end of a method declaration, right before its first section marker. */
    private static final Pattern DECLARATION_BEFORE_REGION = Pattern.compile(
            "\\b_([A-Za-z_$][\\w$]*)\\s*\\([^()]*\\)\\s*\\{\\s*\\z");
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final String MORE_BLOCK_SUFFIX = "_moreBlock";
    /** Header of a method that can be a More Block: return type, name without "_", parameters. */
    private static final Pattern MORE_BLOCK_METHOD = Pattern.compile(
            "(?:(?:public|private|protected|static|final|synchronized)\\s+)*([\\w$<>,\\s]+?)\\s+_([A-Za-z$][\\w$]*)\\s*\\(([^()]*)\\)\\s*\\{");
    private static final Pattern METHOD_PARAMETER = Pattern.compile("(?:final\\s+)?([\\w$<>,\\s]+?)\\s+([A-Za-z_$][\\w$]*)");
    private static final String EVENT_BODY_MARKER = "__swEventBody__";
    private static final Pattern LEADING_ANNOTATIONS = Pattern.compile("^(\\s*@\\w+(\\([^)]*\\))?\\s*)+");
    /** {@code id.setOn...Listener(new Type() { one method });} */
    private static final Pattern LISTENER = Pattern.compile(
            "(\\w+)\\.setOn\\w+Listener\\(\\s*new\\s+[\\w.<>?]+\\s*\\(\\s*\\)\\s*\\{(.*)\\}\\s*\\)\\s*;", Pattern.DOTALL);
    /** Activity events a lifecycle method can become; the others are part of the generated structure. */
    private static final Set<String> ACTIVITY_EVENT_NAMES = Set.of(
            "onBackPressed", "onPostCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy");
    /** Header of any method: return type, name, parameters. */
    private static final Pattern ORDINARY_METHOD = Pattern.compile(
            "(?:(?:public|private|protected|static|final|synchronized)\\s+)*([\\w$<>,\\s.\\[\\]]+?)\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^()]*)\\)\\s*\\{");
    /** A class type More Blocks can return as a view-like value, e.g. LinearLayout or GradientDrawable. */
    private static final Pattern CLASS_TYPE = Pattern.compile("[A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*");
    /** Methods whose names suggest they override or implement something, which a rename would break. */
    private static final Set<String> OVERRIDE_NAMES = Set.of("run", "call", "finish", "toString", "equals",
            "hashCode", "getView", "getCount", "getItem", "getItemId", "getItemCount", "compare", "apply");
    /** Java parameter types More Blocks support, to their spec. */
    private static final Map<String, String> MORE_BLOCK_PARAM_TYPES = Map.ofEntries(
            Map.entry("String", "%s"), Map.entry("double", "%d"), Map.entry("boolean", "%b"),
            Map.entry("HashMap<String,Object>", "%m.varMap"), Map.entry("ArrayList<Double>", "%m.listInt"),
            Map.entry("ArrayList<String>", "%m.listStr"), Map.entry("ArrayList<HashMap<String,Object>>", "%m.listMap"),
            Map.entry("View", "%m.view"), Map.entry("TextView", "%m.textview"), Map.entry("EditText", "%m.edittext"),
            Map.entry("ImageView", "%m.imageview"), Map.entry("Intent", "%m.intent"), Map.entry("Calendar", "%m.calendar"));
    private static final Pattern PLAIN_IMPORT = Pattern.compile("import\\s+([\\w.]+(?:\\.\\*)?)\\s*;");

    private static final String ASD_SPEC = "add source directly %s.inputOnly";
    private static final String ASD_OPCODE = "addSourceDirectly";
    /** Colour of the operator palette, where "add source directly" lives. */
    private static final int ASD_COLOR = 0xff5cb722;
    private static final int MARKER_BEGIN_ID = 2000000001;
    private static final int MARKER_END_ID = 2000000002;

    /**
     * Code that fragments' generated Java rewrites (see the end of {@link Jx#generateCode}), as
     * {rewritten, original}. Saved code is turned back so it isn't rewritten twice.
     */
    private static final String[][] FRAGMENT_REWRITES = {
            {"getContext().getApplicationContext()", "getApplicationContext()"},
            {"getActivity().getBaseContext()", "getBaseContext()"},
            {"getActivity().runOnUiThread(new", "runOnUiThread(new"},
            {"getActivity().getLayoutInflater()", "getLayoutInflater()"},
            {"getActivity().getSupportFragmentManager()", "getSupportFragmentManager()"},
    };

    private final String scId;
    private final ProjectFileBean projectFile;

    public EventCodeBridge(String scId, ProjectFileBean projectFile) {
        this.scId = scId;
        this.projectFile = projectFile;
    }

    /**
     * Generates the activity's Java with every event's code inside region markers.
     */
    public String generate(yq workspace) {
        eC dataManager = jC.a(scId);
        String javaName = projectFile.getJavaName();
        synchronized (dataManager) {
            HashMap<String, ArrayList<BlockBean>> original = dataManager.d.get(javaName);
            HashMap<String, ArrayList<BlockBean>> marked = new HashMap<>();
            if (original != null) marked.putAll(original);
            for (String key : collectEventKeys(dataManager, javaName, original)) {
                marked.put(key, wrapWithMarkers(key, marked.get(key)));
            }
            dataManager.d.put(javaName, marked);
            try {
                return workspace.getFileSrc(javaName, jC.b(scId), dataManager, jC.c(scId));
            } finally {
                if (original != null) {
                    dataManager.d.put(javaName, original);
                } else {
                    dataManager.d.remove(javaName);
                }
            }
        }
    }

    /**
     * What saving the edited code changes in the project.
     */
    public static final class Plan {
        /** Event key to new, normalized code. Empty code clears the event. */
        final LinkedHashMap<String, String> sections = new LinkedHashMap<>();
        /** More Block names (as stored, may include a return type) to their new method name. */
        final LinkedHashMap<String, String> renamedMoreBlocks = new LinkedHashMap<>();
        final ArrayList<String> deletedMoreBlocks = new ArrayList<>();
        /** Events whose markers are gone; they keep their blocks. */
        final ArrayList<String> unmatchedEvents = new ArrayList<>();
        /** Imports or members of generated code that were changed or removed; they come back on save. */
        int discardedChanges;
        int movedImports;
        int movedMembers;
        /** New fields, stored as custom variables (declaration without ";"). */
        final ArrayList<String> addedVariables = new ArrayList<>();
        /** New imports, stored as project imports (e.g. "java.util.Random"). */
        final ArrayList<String> addedImports = new ArrayList<>();
        /** Variables (including imports) whose declaration was deleted. */
        final ArrayList<Pair<Integer, String>> removedVariables = new ArrayList<>();
        /** Custom variables whose declaration changed, to their new declaration. */
        final LinkedHashMap<Pair<Integer, String>, String> changedVariables = new LinkedHashMap<>();
        /** New methods named {@code _name}, stored as More Blocks. */
        final ArrayList<NewMoreBlock> addedMoreBlocks = new ArrayList<>();
        /** Activity lifecycle methods and widget listeners typed in the editor, stored as events. */
        final ArrayList<NewEvent> addedEvents = new ArrayList<>();

        public boolean hasChanges() {
            return !sections.isEmpty() || !renamedMoreBlocks.isEmpty() || !deletedMoreBlocks.isEmpty()
                    || !addedVariables.isEmpty() || !addedImports.isEmpty() || !removedVariables.isEmpty()
                    || !changedVariables.isEmpty() || !addedMoreBlocks.isEmpty() || !addedEvents.isEmpty();
        }

        public String summary() {
            ArrayList<String> parts = new ArrayList<>();
            int events = 0;
            Set<String> newMoreBlockKeys = new HashSet<>();
            for (NewMoreBlock moreBlock : addedMoreBlocks) newMoreBlockKeys.add(moreBlock.name() + MORE_BLOCK_SUFFIX);
            for (NewEvent event : addedEvents) newMoreBlockKeys.add(event.key());
            for (String key : sections.keySet()) {
                if (!isJavaEditorKey(key) && !newMoreBlockKeys.contains(key)) events++;
            }
            if (events > 0) parts.add(events + (events == 1 ? " event" : " events") + " saved as blocks");
            if (sections.containsKey(Jx.JAVA_EDITOR_MEMBERS_KEY) || sections.containsKey(Jx.JAVA_EDITOR_IMPORTS_KEY)) {
                parts.add("custom code saved");
            }
            if (!renamedMoreBlocks.isEmpty()) parts.add(renamedMoreBlocks.size() + " More Block(s) renamed");
            if (!addedEvents.isEmpty()) parts.add(addedEvents.size() + " event(s) added");
            if (!addedMoreBlocks.isEmpty()) parts.add(addedMoreBlocks.size() + " More Block(s) added");
            if (!deletedMoreBlocks.isEmpty()) parts.add(deletedMoreBlocks.size() + " More Block(s) deleted");
            if (!addedVariables.isEmpty()) parts.add(addedVariables.size() + " variable(s) added");
            if (!changedVariables.isEmpty()) parts.add(changedVariables.size() + " variable(s) changed");
            if (!addedImports.isEmpty()) parts.add(addedImports.size() + " import(s) added");
            if (!removedVariables.isEmpty()) parts.add(removedVariables.size() + " variable(s)/import(s) removed");
            if (parts.isEmpty()) return "No changes to save";
            String summary = String.join(", ", parts);
            return Character.toUpperCase(summary.charAt(0)) + summary.substring(1);
        }
    }

    /**
     * A More Block to create from a method typed in the editor.
     *
     * @param name As stored, with the return type, e.g. {@code calc[double]}.
     * @param spec As stored, e.g. {@code calc[double] %d.a %d.b}.
     * @param body The method's code, with its parameters renamed to {@code _name} like More Blocks name them.
     */
    record NewMoreBlock(String name, String spec, String body) {
    }

    /**
     * An event to create from a lifecycle method or listener typed in the editor.
     *
     * @param body The code inside the event, without the code the generator adds around it.
     */
    record NewEvent(int eventType, int targetType, String targetId, String eventName, String body) {
        String key() {
            return targetId + "_" + eventName;
        }
    }

    /**
     * Works out how to store {@code edited}, given the {@code generated} code it started from.
     */
    public Plan plan(String generated, String edited) {
        return plan(generated, edited, false);
    }

    /**
     * @param convertUnchanged Also convert events whose code didn't change but that contain
     *                         "add source directly" blocks, e.g. from saving with an older version.
     */
    public Plan plan(String generated, String edited, boolean convertUnchanged) {
        Plan plan = new Plan();
        eC dataManager = jC.a(scId);
        String javaName = projectFile.getJavaName();

        LinkedHashMap<String, String> generatedSections = parseSections(generated);
        LinkedHashMap<String, Integer> editedRegionStarts = new LinkedHashMap<>();
        LinkedHashMap<String, String> editedSections = parseSections(edited, editedRegionStarts);

        // More Blocks: renamed, deleted, or markers lost.
        HashMap<String, String> moreBlockBodies = new HashMap<>();
        HashMap<String, String> renames = new HashMap<>(); // old method name -> new
        ArrayList<Pair<String, String>> moreBlocks = dataManager.i(javaName);
        if (moreBlocks != null) {
            for (Pair<String, String> moreBlock : moreBlocks) {
                String key = moreBlock.first + MORE_BLOCK_SUFFIX;
                if (!generatedSections.containsKey(key)) continue;
                String oldName = ReturnMoreblockManager.getMbName(moreBlock.first);
                Integer regionStart = editedRegionStarts.get(key);
                if (regionStart != null) {
                    Matcher declaration = DECLARATION_BEFORE_REGION.matcher(
                            edited.substring(Math.max(0, regionStart - 2000), regionStart));
                    if (declaration.find() && !declaration.group(1).equals(oldName)
                            && JAVA_IDENTIFIER.matcher(declaration.group(1)).matches()) {
                        renames.put(oldName, declaration.group(1));
                        plan.renamedMoreBlocks.put(moreBlock.first, declaration.group(1));
                    }
                } else {
                    String body = findMethodBody(edited, oldName);
                    if (body == null) {
                        plan.deletedMoreBlocks.add(moreBlock.first);
                    } else {
                        moreBlockBodies.put(key, body);
                    }
                }
            }
        }

        // Sections.
        for (Map.Entry<String, String> entry : generatedSections.entrySet()) {
            String key = entry.getKey();
            String body = editedSections.get(key);
            if (body == null) body = moreBlockBodies.get(key);
            if (body == null) {
                if (key.endsWith(MORE_BLOCK_SUFFIX) && plan.deletedMoreBlocks.contains(
                        key.substring(0, key.length() - MORE_BLOCK_SUFFIX.length()))) {
                    continue;
                }
                if (!isJavaEditorKey(key)) plan.unmatchedEvents.add(key);
                continue;
            }
            String newBody = normalizeBody(applyRenames(body, renames));
            if (!newBody.equals(normalizeBody(applyRenames(entry.getValue(), renames)))
                    || (convertUnchanged && !isJavaEditorKey(key) && hasSourceBlocks(dataManager, javaName, key))) {
                plan.sections.put(key, newBody);
            }
        }

        // Code added outside the sections.
        String generatedOutside = applyRenames(generated, renames);
        for (String deleted : plan.deletedMoreBlocks) {
            generatedOutside = removeMethod(generatedOutside, ReturnMoreblockManager.getMbName(deleted));
        }
        StringBuilder imports = new StringBuilder();
        StringBuilder members = new StringBuilder();
        ArrayList<Pair<Integer, String>> variables = dataManager.k(javaName);
        diffOutside(generatedOutside, applyRenames(edited, renames), plan, imports, members,
                variables == null ? new ArrayList<>() : variables);

        // Fields and imports become project variables and imports, like ones added in the Logic editor.
        // This also moves simple fields and imports out of the javaEditor sections.
        String importsBody = joinBodies(currentBody(Jx.JAVA_EDITOR_IMPORTS_KEY, plan, generatedSections, editedSections),
                imports.toString());
        String remainingImports = extractImports(importsBody, plan);
        if (!remainingImports.equals(normalizeBody(currentBody(Jx.JAVA_EDITOR_IMPORTS_KEY, null, generatedSections, editedSections)))) {
            plan.sections.put(Jx.JAVA_EDITOR_IMPORTS_KEY, remainingImports);
        }
        String membersBody = joinBodies(currentBody(Jx.JAVA_EDITOR_MEMBERS_KEY, plan, generatedSections, editedSections),
                members.toString());

        // Ordinary methods that More Blocks can represent become More Blocks too. More Block methods are
        // always named _name, so the method and every call to it are renamed.
        Set<String> takenNames = new HashSet<>();
        if (moreBlocks != null) {
            for (Pair<String, String> moreBlock : moreBlocks) takenNames.add(ReturnMoreblockManager.getMbName(moreBlock.first));
        }
        takenNames.addAll(renames.values());
        Map<String, String> methodRenames = convertibleMethods(membersBody, edited, takenNames);
        if (!methodRenames.isEmpty()) {
            membersBody = renameCalls(membersBody, methodRenames);
            for (String key : generatedSections.keySet()) {
                if (isJavaEditorKey(key) || plan.unmatchedEvents.contains(key)) continue;
                String body = plan.sections.get(key);
                if (body == null) {
                    String current = editedSections.containsKey(key) ? editedSections.get(key) : moreBlockBodies.get(key);
                    if (current == null) continue;
                    body = normalizeBody(applyRenames(current, renames));
                }
                String renamed = renameCalls(body, methodRenames);
                if (!renamed.equals(body)) plan.sections.put(key, renamed);
            }
        }
        Set<String> existingMoreBlocks = new HashSet<>();
        if (moreBlocks != null) {
            for (Pair<String, String> moreBlock : moreBlocks) {
                existingMoreBlocks.add(ReturnMoreblockManager.getMbName(moreBlock.first));
            }
        }
        existingMoreBlocks.addAll(renames.values());
        Set<String> existingEvents = new HashSet<>();
        ArrayList<EventBean> events = dataManager.g(javaName);
        if (events != null) {
            for (EventBean event : events) existingEvents.add(event.targetId + "_" + event.eventName);
        }
        String remainingMembers = extractFields(membersBody, plan, existingMoreBlocks, existingEvents);
        if (!remainingMembers.equals(normalizeBody(currentBody(Jx.JAVA_EDITOR_MEMBERS_KEY, null, generatedSections, editedSections)))) {
            plan.sections.put(Jx.JAVA_EDITOR_MEMBERS_KEY, remainingMembers);
        }

        // Widget listeners typed in an event become that widget's events.
        ArrayList<ViewBean> views = dataManager.d(projectFile.getXmlName());
        if (views != null && !views.isEmpty()) {
            for (Map.Entry<String, String> section : new ArrayList<>(plan.sections.entrySet())) {
                if (isJavaEditorKey(section.getKey())) continue;
                String remaining = extractListeners(section.getValue(), views, existingEvents, plan);
                if (!remaining.equals(section.getValue())) plan.sections.put(section.getKey(), remaining);
            }
        }
        return plan;
    }

    /**
     * Turns an Activity lifecycle method (e.g. {@code onResume}) into that Activity event, if the event
     * doesn't exist yet and the method matches how the event is generated.
     */
    @Nullable
    private static NewEvent activityEventOf(String method, Set<String> existingEvents, Plan plan) {
        Matcher header = ORDINARY_METHOD.matcher(LEADING_ANNOTATIONS.matcher(method).replaceFirst(""));
        if (!header.lookingAt()) return null;
        String name = header.group(2);
        if (!ACTIVITY_EVENT_NAMES.contains(name) || existingEvents.contains(name + "_" + name)) return null;
        for (NewEvent added : plan.addedEvents) {
            if (added.key().equals(name + "_" + name)) return null;
        }
        String body = eventBody(method, Lx.getEventCode(name, name, EVENT_BODY_MARKER));
        return body == null ? null : new NewEvent(EventBean.EVENT_TYPE_ACTIVITY, 0, name, name, normalizeBody(body));
    }

    /**
     * Moves statements like {@code button1.setOnClickListener(new View.OnClickListener() {...});} to new
     * events of that widget.
     *
     * @return The code without those statements.
     */
    private static String extractListeners(String code, List<ViewBean> views, Set<String> existingEvents, Plan plan) {
        StringBuilder remaining = new StringBuilder();
        int last = 0;
        for (int[] range : JavaToBlocks.statementRanges(code)) {
            String statement = code.substring(range[0], range[1]);
            NewEvent event = listenerEventOf(statement, views, existingEvents, plan);
            if (event == null) continue;
            plan.addedEvents.add(event);
            remaining.append(code, last, range[0]);
            last = range[1];
        }
        if (last == 0) return code;
        remaining.append(code.substring(last));
        return normalizeBody(remaining.toString());
    }

    @Nullable
    private static NewEvent listenerEventOf(String statement, List<ViewBean> views, Set<String> existingEvents, Plan plan) {
        Matcher listener = LISTENER.matcher(statement.trim());
        if (!listener.matches()) return null;
        String id = listener.group(1);
        String method = listener.group(2).trim();
        for (ViewBean view : views) {
            if (!id.equals(view.id)) continue;
            for (String eventName : oq.getEventsForClass(view.getClassInfo())) {
                String key = id + "_" + eventName;
                if (existingEvents.contains(key)) continue;
                boolean taken = false;
                for (NewEvent added : plan.addedEvents) taken |= added.key().equals(key);
                if (taken) continue;
                String body;
                try {
                    body = eventBody(method, Lx.getEventCode(id, eventName, EVENT_BODY_MARKER));
                } catch (RuntimeException e) {
                    continue;
                }
                if (body != null) {
                    return new NewEvent(EventBean.EVENT_TYPE_VIEW, view.type, id, eventName, normalizeBody(body));
                }
            }
        }
        return null;
    }

    /**
     * Matches a method against the code the generator writes for an event, ignoring annotations and
     * access modifiers before its return type.
     *
     * @param template The event's generated code with {@link #EVENT_BODY_MARKER} as its code.
     * @return The code inside the method, or {@code null} if the method isn't that event.
     */
    @Nullable
    static String eventBody(String method, String template) {
        int marker = template.indexOf(EVENT_BODY_MARKER);
        if (marker < 0) return null;
        List<JavaToBlocks.Token> prefix = headerTokens(template.substring(0, marker));
        List<JavaToBlocks.Token> suffix = codeTokens(template.substring(marker + EVENT_BODY_MARKER.length()));
        List<JavaToBlocks.Token> tokens = headerTokens(method);
        if (prefix.isEmpty() || tokens.size() < prefix.size() + suffix.size()) return null;
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).text.equals(tokens.get(i).text)) return null;
        }
        int suffixStart = tokens.size() - suffix.size();
        for (int i = 0; i < suffix.size(); i++) {
            if (!suffix.get(i).text.equals(tokens.get(suffixStart + i).text)) return null;
        }
        int bodyStart = tokens.get(prefix.size() - 1).end;
        int bodyEnd = suffixStart < tokens.size() ? tokens.get(suffixStart).start : method.length();
        return bodyStart <= bodyEnd ? method.substring(bodyStart, bodyEnd) : null;
    }

    private static List<JavaToBlocks.Token> codeTokens(String code) {
        ArrayList<JavaToBlocks.Token> tokens = new ArrayList<>();
        for (JavaToBlocks.Token token : JavaToBlocks.tokenize(code)) {
            if (!token.comment) tokens.add(token);
        }
        return tokens;
    }

    /**
     * Code tokens without leading annotations and access modifiers.
     */
    private static List<JavaToBlocks.Token> headerTokens(String code) {
        List<JavaToBlocks.Token> tokens = codeTokens(code);
        int start = 0;
        while (start < tokens.size()) {
            String text = tokens.get(start).text;
            if (text.equals("@") && start + 1 < tokens.size()) {
                start += 2;
            } else if (text.equals("public") || text.equals("protected") || text.equals("private")) {
                start++;
            } else {
                break;
            }
        }
        return tokens.subList(start, tokens.size());
    }

    /**
     * Moves plain imports to {@link Plan#addedImports}.
     *
     * @return The imports that have to stay in the javaEditor section, e.g. static imports.
     */
    private static String extractImports(String body, Plan plan) {
        StringBuilder remaining = new StringBuilder();
        for (String line : body.split("\n")) {
            Matcher matcher = PLAIN_IMPORT.matcher(line.trim());
            if (matcher.matches()) {
                if (!plan.addedImports.contains(matcher.group(1))) plan.addedImports.add(matcher.group(1));
            } else if (!line.trim().isEmpty()) {
                remaining.append(line).append('\n');
            }
        }
        return normalizeBody(remaining.toString());
    }

    /**
     * Moves single field declarations to {@link Plan#addedVariables} and {@code _name} methods to
     * {@link Plan#addedMoreBlocks}.
     *
     * @return The members that have to stay in the javaEditor section, e.g. methods.
     */
    private static String extractFields(String body, Plan plan, Set<String> existingMoreBlocks,
                                        Set<String> existingEvents) {
        if (body.isEmpty()) return "";
        Outline outline = outline("class C {\n" + body + "\n}");
        StringBuilder remaining = new StringBuilder();
        for (Map.Entry<String, String> member : outline.members.entrySet()) {
            String raw = outline.rawMembers.get(member.getKey());
            String declaration = fieldDeclaration(member.getValue());
            NewEvent event = declaration == null
                    ? activityEventOf(outline.codeMembers.get(member.getKey()), existingEvents, plan) : null;
            NewMoreBlock moreBlock = declaration == null && event == null
                    ? moreBlockOf(outline.codeMembers.get(member.getKey()), existingMoreBlocks, plan) : null;
            if (declaration != null) {
                if (!plan.addedVariables.contains(declaration)) plan.addedVariables.add(declaration);
            } else if (event != null) {
                plan.addedEvents.add(event);
            } else if (moreBlock != null) {
                plan.addedMoreBlocks.add(moreBlock);
            } else {
                remaining.append(raw).append("\n\n");
            }
        }
        return normalizeBody(remaining.toString());
    }

    /**
     * Turns a method named {@code _name} into a More Block, if More Blocks support its return type and
     * parameter types.
     */
    @Nullable
    private static NewMoreBlock moreBlockOf(String method, Set<String> existingMoreBlocks, Plan plan) {
        Matcher header = MORE_BLOCK_METHOD.matcher(method);
        if (!header.lookingAt()) return null;
        String name = header.group(2);
        if (existingMoreBlocks.contains(name)) return null;
        for (NewMoreBlock added : plan.addedMoreBlocks) {
            if (ReturnMoreblockManager.getMbName(added.name).equals(name)) return null;
        }
        String returnType = collapse(header.group(1));
        String storedType = switch (returnType) {
            case "void" -> "";
            case "String", "double", "boolean" -> returnType;
            case "HashMap<String, Object>", "HashMap<String,Object>" -> "a|Map";
            case "ArrayList<String>" -> "l|List String";
            case "ArrayList<HashMap<String, Object>>", "ArrayList<HashMap<String,Object>>" -> "l|List Map";
            default -> CLASS_TYPE.matcher(returnType).matches() ? "v|" + returnType : null;
        };
        if (storedType == null) return null;
        String storedName = storedType.isEmpty() ? name : name + "[" + storedType + "]";

        StringBuilder spec = new StringBuilder(storedName);
        HashMap<String, String> paramRenames = new HashMap<>();
        String params = header.group(3).trim();
        if (!params.isEmpty()) {
            for (String param : splitTopLevel(params)) {
                Matcher matcher = METHOD_PARAMETER.matcher(param.trim());
                if (!matcher.matches()) return null;
                String paramSpec = MORE_BLOCK_PARAM_TYPES.get(collapse(matcher.group(1)).replace(", ", ","));
                if (paramSpec == null) return null;
                String paramName = matcher.group(2);
                String specName = paramName.startsWith("_") ? paramName.substring(1) : paramName;
                if (!JAVA_IDENTIFIER.matcher(specName).matches()) return null;
                spec.append(' ').append(paramSpec).append('.').append(specName);
                if (!paramName.startsWith("_")) paramRenames.put(paramName, "_" + specName);
            }
        }

        int open = header.end() - 1;
        int close = findClosing(method, open, '{', '}');
        if (close < 0) return null;
        String body = method.substring(open + 1, close);
        if (!paramRenames.isEmpty()) body = renameIdentifiers(body, paramRenames);
        return new NewMoreBlock(storedName, spec.toString(), normalizeBody(body));
    }

    /**
     * Finds methods without the "_" prefix that can become More Blocks: their types are supported, their
     * name is unique and isn't a More Block yet, and it doesn't look like an override (e.g. onResume, run).
     *
     * @return Method name to its More Block method name ({@code _name}).
     */
    private static Map<String, String> convertibleMethods(String membersBody, String allCode, Set<String> takenNames) {
        LinkedHashMap<String, String> renames = new LinkedHashMap<>();
        if (membersBody.isEmpty()) return renames;
        Outline outline = outline("class C {\n" + membersBody + "\n}");
        HashMap<String, Integer> counts = new HashMap<>();
        HashMap<String, String> methods = new HashMap<>();
        for (Map.Entry<String, String> member : outline.members.entrySet()) {
            String code = outline.codeMembers.get(member.getKey());
            Matcher header = ORDINARY_METHOD.matcher(code);
            if (!header.lookingAt()) continue;
            String name = header.group(2);
            counts.merge(name, 1, Integer::sum);
            methods.put(name, code);
        }
        Plan scratch = new Plan();
        for (Map.Entry<String, String> method : methods.entrySet()) {
            String name = method.getKey();
            if (name.startsWith("_") || counts.get(name) > 1 || takenNames.contains(name)
                    || name.matches("on[A-Z].*") || OVERRIDE_NAMES.contains(name)
                    || allCode.contains("super." + name + "(")) {
                continue;
            }
            Map<String, String> rename = Map.of(name, "_" + name);
            if (moreBlockOf(renameCalls(method.getValue(), rename), takenNames, scratch) != null) {
                renames.put(name, "_" + name);
            }
        }
        return renames;
    }

    /**
     * Renames method calls and declarations ({@code name(}), leaving member calls ({@code x.name(}),
     * constructors, strings and comments alone.
     */
    static String renameCalls(String code, Map<String, String> renames) {
        ArrayList<JavaToBlocks.Token> tokens = new ArrayList<>();
        for (JavaToBlocks.Token token : JavaToBlocks.tokenize(code)) {
            if (!token.comment) tokens.add(token);
        }
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (int i = 0; i < tokens.size(); i++) {
            JavaToBlocks.Token token = tokens.get(i);
            String replacement = renames.get(token.text);
            if (replacement == null || i + 1 >= tokens.size() || !tokens.get(i + 1).text.equals("(")) continue;
            String previous = i > 0 ? tokens.get(i - 1).text : "";
            if (previous.equals(".") || previous.equals("new") || previous.equals("::")) continue;
            sb.append(code, last, token.start).append(replacement);
            last = token.end;
        }
        sb.append(code.substring(last));
        return sb.toString();
    }

    /**
     * Splits a parameter list at commas outside of generics.
     */
    private static List<String> splitTopLevel(String params) {
        ArrayList<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(params.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(params.substring(start));
        return parts;
    }

    /**
     * Renames identifiers in code, leaving member accesses ({@code x.name}), strings and comments alone.
     */
    private static String renameIdentifiers(String code, Map<String, String> renames) {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        String previous = null;
        for (JavaToBlocks.Token token : JavaToBlocks.tokenize(code)) {
            if (!token.comment) {
                String replacement = renames.get(token.text);
                if (replacement != null && !".".equals(previous)) {
                    sb.append(code, last, token.start).append(replacement);
                    last = token.end;
                }
                previous = token.text;
            }
        }
        sb.append(code.substring(last));
        return sb.toString();
    }

    /**
     * @return {@code member} without its ";" if it declares exactly one field, as a custom variable stores
     * it, e.g. {@code private TextView textview2}; otherwise {@code null}.
     */
    @Nullable
    static String fieldDeclaration(String member) {
        if (!member.endsWith(";") || member.startsWith("@") || member.contains("{") || member.contains("}")) {
            return null;
        }
        String declaration = member.substring(0, member.length() - 1).trim();
        int equals = declaration.indexOf('=');
        String head = (equals < 0 ? declaration : declaration.substring(0, equals)).replaceAll("<[^<>]*>", "")
                .replaceAll("<[^<>]*>", "");
        if (head.contains(",") || head.contains("(")) return null;
        String[] words = head.trim().split("\\s+");
        if (words.length < 2) return null;
        String name = words[words.length - 1].replace("[]", "");
        if (!JAVA_IDENTIFIER.matcher(name).matches()) return null;
        return CustomVariableUtil.getVariableName(declaration) != null ? declaration : null;
    }

    /**
     * @return Name of the field a project variable declares.
     */
    @Nullable
    private static String variableName(Pair<Integer, String> variable) {
        return switch (variable.first) {
            case 5, 6 -> CustomVariableUtil.getVariableName(variable.second);
            case 9 -> null;
            default -> variable.second;
        };
    }

    private static boolean hasSourceBlocks(eC dataManager, String javaName, String key) {
        ArrayList<BlockBean> blocks = dataManager.a(javaName, key);
        if (blocks == null) return false;
        for (BlockBean block : blocks) {
            if (block.opCode != null && (block.opCode.equals(ASD_OPCODE) || block.opCode.startsWith("asd"))) return true;
        }
        return false;
    }

    /**
     * Stores a {@link Plan} in the project's blocks. Changes are made in memory, like the Logic editor's;
     * the Design screen saves them with the project.
     * <p>
     * Event code is converted to real blocks where possible, see {@link JavaToBlocks}. This can take a
     * moment for large code, so call it off the main thread.
     */
    public void apply(Plan plan, Context context) {
        eC dataManager = jC.a(scId);
        String javaName = projectFile.getJavaName();
        synchronized (dataManager) {
            // Add new More Blocks first so the converter knows them, e.g. for calls to them.
            for (NewMoreBlock moreBlock : plan.addedMoreBlocks) {
                dataManager.a(javaName, moreBlock.name(), moreBlock.spec());
            }
            for (NewEvent event : plan.addedEvents) {
                dataManager.a(javaName, new EventBean(event.eventType(), event.targetType(), event.targetId(), event.eventName()));
            }
        }
        JavaToBlocks converter = null;
        boolean convertsCode = !plan.addedMoreBlocks.isEmpty() || !plan.addedEvents.isEmpty();
        for (String key : plan.sections.keySet()) {
            if (!isJavaEditorKey(key)) convertsCode = true;
        }
        if (convertsCode) converter = createConverter(context);
        for (NewMoreBlock moreBlock : plan.addedMoreBlocks) {
            plan.sections.put(moreBlock.name() + MORE_BLOCK_SUFFIX, moreBlock.body());
        }
        for (NewEvent event : plan.addedEvents) {
            plan.sections.put(event.key(), event.body());
        }
        apply(plan, converter);
    }

    void apply(Plan plan, @Nullable JavaToBlocks converter) {
        eC dataManager = jC.a(scId);
        String javaName = projectFile.getJavaName();
        boolean isFragment = projectFile.fileName.contains("_fragment");
        synchronized (dataManager) {
            if (!dataManager.d.containsKey(javaName)) dataManager.d.put(javaName, new HashMap<>());
            HashMap<String, ArrayList<BlockBean>> blocks = dataManager.d.get(javaName);

            HashMap<String, String> keyRenames = new HashMap<>();
            for (Map.Entry<String, String> rename : plan.renamedMoreBlocks.entrySet()) {
                keyRenames.put(rename.getKey() + MORE_BLOCK_SUFFIX,
                        renameMoreBlock(dataManager, javaName, rename.getKey(), rename.getValue()));
            }

            for (Map.Entry<String, String> entry : plan.sections.entrySet()) {
                String key = keyRenames.containsKey(entry.getKey()) ? keyRenames.get(entry.getKey()) : entry.getKey();
                String code = isFragment ? undoFragmentRewrites(entry.getValue()) : entry.getValue();
                ArrayList<BlockBean> newBlocks = new ArrayList<>();
                if (!code.isEmpty()) {
                    if (converter != null && !isJavaEditorKey(key)) {
                        newBlocks = convertToBlocks(converter, dataManager, javaName, key, code);
                    } else {
                        newBlocks.add(createAsdBlock("10", code));
                    }
                }
                if (newBlocks.isEmpty() && isJavaEditorKey(key)) {
                    blocks.remove(key);
                } else {
                    dataManager.a(javaName, key, newBlocks);
                }
            }

            for (String moreBlock : plan.deletedMoreBlocks) {
                dataManager.n(javaName, moreBlock);
            }

            ArrayList<Pair<Integer, String>> variables = dataManager.e.get(javaName);
            if (variables != null) {
                for (Pair<Integer, String> removed : plan.removedVariables) {
                    variables.removeIf(v -> v.first.equals(removed.first) && v.second.equals(removed.second));
                }
                for (Map.Entry<Pair<Integer, String>, String> changed : plan.changedVariables.entrySet()) {
                    for (int i = 0; i < variables.size(); i++) {
                        Pair<Integer, String> v = variables.get(i);
                        if (v.first.equals(changed.getKey().first) && v.second.equals(changed.getKey().second)) {
                            variables.set(i, new Pair<>(6, changed.getValue()));
                        }
                    }
                }
            }
            for (String declaration : plan.addedVariables) {
                dataManager.c(javaName, 6, declaration);
            }
            for (String anImport : plan.addedImports) {
                dataManager.c(javaName, 9, anImport);
            }
        }
    }

    private static ArrayList<BlockBean> convertToBlocks(JavaToBlocks converter, eC dataManager, String javaName,
                                                        String key, String code) {
        List<JavaToBlocks.Reporter> arguments = new ArrayList<>();
        if (key.endsWith(MORE_BLOCK_SUFFIX)) {
            String name = key.substring(0, key.length() - MORE_BLOCK_SUFFIX.length());
            ArrayList<Pair<String, String>> moreBlocks = dataManager.i(javaName);
            if (moreBlocks != null) {
                for (Pair<String, String> moreBlock : moreBlocks) {
                    if (moreBlock.first.equals(name)) arguments = JavaToBlocks.moreBlockArguments(moreBlock.second);
                }
            }
        }
        try {
            ArrayList<BlockBean> blocks = converter.convert(code, arguments);
            if (!blocks.isEmpty()) return blocks;
        } catch (RuntimeException e) {
            LogUtil.e("EventCodeBridge", "Failed to convert " + key + " to blocks", e);
        }
        ArrayList<BlockBean> fallback = new ArrayList<>();
        fallback.add(createAsdBlock("10", code));
        return fallback;
    }

    /**
     * Creates a converter knowing every block of the palettes, Custom Blocks, this activity's More Blocks
     * and its variables and lists.
     */
    private JavaToBlocks createConverter(Context context) {
        eC dataManager = jC.a(scId);
        String javaName = projectFile.getJavaName();
        yq workspace = new yq(context, scId);
        workspace.a(jC.c(scId), jC.b(scId), dataManager);
        boolean viewBinding = new ProjectSettings(scId)
                .getValue(ProjectSettings.SETTING_ENABLE_VIEWBINDING, "false").equals("true");

        LinkedHashMap<String, JavaToBlocks.BlockDef> defs = new LinkedHashMap<>();
        for (Map.Entry<String, String> block : BuiltInBlockTypes.TYPES.entrySet()) {
            String opCode = block.getKey();
            String spec = paletteSpec(context, opCode);
            boolean core = spec != null;
            if (spec == null) spec = customBlockSpec(opCode);
            if (spec != null) defs.put(opCode, new JavaToBlocks.BlockDef(opCode, block.getValue(), "", spec, core));
        }
        try {
            for (HashMap<String, Object> block : ExtraBlockFile.getExtraBlockData()) {
                Object name = block.get("name");
                Object type = block.get("type");
                Object spec = block.get("spec");
                if (!(name instanceof String n) || !(type instanceof String t) || !(spec instanceof String sp)) continue;
                Object typeName = block.get("typeName");
                defs.putIfAbsent(n, new JavaToBlocks.BlockDef(n, t, typeName instanceof String tn ? tn : "", sp));
            }
        } catch (RuntimeException e) {
            LogUtil.e("EventCodeBridge", "Failed to read Custom Blocks", e);
        }
        ArrayList<JavaToBlocks.BlockDef> allDefs = new ArrayList<>(defs.values());
        ArrayList<String> moreBlockSpecs = new ArrayList<>();
        ArrayList<Pair<String, String>> moreBlocks = dataManager.i(javaName);
        if (moreBlocks != null) {
            for (Pair<String, String> moreBlock : moreBlocks) moreBlockSpecs.add(moreBlock.second);
        }
        allDefs.addAll(JavaToBlocks.moreBlockDefs(moreBlockSpecs));

        return new JavaToBlocks(projectFile.getActivityName(), workspace.N, viewBinding, allDefs,
                variableReporters(dataManager, javaName), kq::a);
    }

    /**
     * @return Spec of one of the original palette blocks, or {@code null}.
     */
    @Nullable
    private static String paletteSpec(Context context, String opCode) {
        try {
            String spec = xB.b().a(context, opCode);
            if (spec != null && !spec.isEmpty()) return spec;
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * @return Spec of a built-in or user Custom Block, or {@code null}.
     */
    @Nullable
    private static String customBlockSpec(String opCode) {
        ExtraBlockInfo info = BlockLoader.getBlockInfo(opCode);
        return info.isMissing || info.getSpec().isEmpty() ? null : info.getSpec();
    }

    /**
     * Variables and lists of an activity as {@code getVar} reporters, typed like the Logic editor's palette.
     */
    private static List<JavaToBlocks.Reporter> variableReporters(eC dataManager, String javaName) {
        ArrayList<JavaToBlocks.Reporter> reporters = new ArrayList<>();
        String[] types = {"b", "d", "s", "a"};
        for (int type = 0; type < types.length; type++) {
            ArrayList<String> names = dataManager.e(javaName, type);
            if (names == null) continue;
            for (String name : names) {
                reporters.add(new JavaToBlocks.Reporter(name, new JavaToBlocks.BlockDef("getVar", types[type], "", name)));
            }
        }
        ArrayList<String> custom = dataManager.e(javaName, 6);
        if (custom != null) {
            for (String variable : custom) {
                String variableType = CustomVariableUtil.getVariableType(variable);
                String name = CustomVariableUtil.getVariableName(variable);
                if (variableType == null || name == null) continue;
                String type = switch (variableType) {
                    case "boolean", "Boolean" -> "b";
                    case "String" -> "s";
                    case "double", "Double", "int", "Integer", "float", "Float", "long", "Long",
                         "short", "Short" -> "d";
                    default -> "v";
                };
                reporters.add(new JavaToBlocks.Reporter(name, new JavaToBlocks.BlockDef("getVar", type, variableType, name)));
            }
        }
        ArrayList<Pair<Integer, String>> lists = dataManager.j(javaName);
        if (lists != null) {
            for (Pair<Integer, String> list : lists) {
                if (list.first >= 1 && list.first <= 3) {
                    reporters.add(new JavaToBlocks.Reporter(list.second,
                            new JavaToBlocks.BlockDef("getVar", "l", kq.a(list.first), list.second)));
                }
            }
        }
        return reporters;
    }

    /**
     * Renames a More Block, its blocks and the blocks calling it in this activity.
     *
     * @return The More Block's new event key.
     */
    private static String renameMoreBlock(eC dataManager, String javaName, String storedName, String newName) {
        String oldName = ReturnMoreblockManager.getMbName(storedName);
        String newStoredName = newName + storedName.substring(oldName.length());
        HashMap<String, String> renames = new HashMap<>();
        renames.put(oldName, newName);

        ArrayList<Pair<String, String>> moreBlocks = dataManager.i(javaName);
        for (int i = 0; i < moreBlocks.size(); i++) {
            Pair<String, String> moreBlock = moreBlocks.get(i);
            if (moreBlock.first.equals(storedName)) {
                moreBlocks.set(i, new Pair<>(newStoredName, renameSpec(moreBlock.second, oldName, newName)));
                break;
            }
        }

        HashMap<String, ArrayList<BlockBean>> blocks = dataManager.d.get(javaName);
        String oldKey = storedName + MORE_BLOCK_SUFFIX;
        String newKey = newStoredName + MORE_BLOCK_SUFFIX;
        if (blocks != null) {
            ArrayList<BlockBean> moreBlockBlocks = blocks.remove(oldKey);
            if (moreBlockBlocks != null) blocks.put(newKey, moreBlockBlocks);
            for (ArrayList<BlockBean> eventBlocks : blocks.values()) {
                if (eventBlocks == null) continue;
                for (BlockBean block : eventBlocks) {
                    if ("definedFunc".equals(block.opCode)) {
                        block.spec = renameSpec(block.spec, oldName, newName);
                    } else if (ASD_OPCODE.equals(block.opCode) && !block.parameters.isEmpty()) {
                        block.parameters.set(0, applyRenames(block.parameters.get(0), renames));
                    }
                }
            }
        }
        return newKey;
    }

    private static String renameSpec(String spec, String oldName, String newName) {
        if (spec == null) return null;
        if (spec.equals(oldName)) return newName;
        return spec.startsWith(oldName + " ") ? newName + spec.substring(oldName.length()) : spec;
    }

    private static String applyRenames(String code, Map<String, String> renames) {
        for (Map.Entry<String, String> rename : renames.entrySet()) {
            code = code.replaceAll("\\b_" + Pattern.quote(rename.getKey()) + "(?=\\s*\\()",
                    Matcher.quoteReplacement("_" + rename.getValue()));
        }
        return code;
    }

    private static String undoFragmentRewrites(String code) {
        for (String[] rewrite : FRAGMENT_REWRITES) {
            code = code.replace(rewrite[0], rewrite[1]);
        }
        return code;
    }

    private static boolean isJavaEditorKey(String key) {
        return Jx.JAVA_EDITOR_IMPORTS_KEY.equals(key) || Jx.JAVA_EDITOR_MEMBERS_KEY.equals(key);
    }

    private static String currentBody(String key, @Nullable Plan plan, Map<String, String> generatedSections,
                                      Map<String, String> editedSections) {
        if (plan != null && plan.sections.containsKey(key)) return plan.sections.get(key);
        String body = editedSections.get(key);
        if (body == null) body = generatedSections.get(key);
        return body == null ? "" : normalizeBody(body);
    }

    private static String joinBodies(String first, String second) {
        String added = normalizeBody(second);
        if (added.isEmpty()) return first;
        if (first.isEmpty()) return added;
        return first + "\n\n" + added;
    }

    /**
     * Splits marked Java into event sections.
     *
     * @return Event key to section body, in order of appearance. Duplicate sections keep the first.
     */
    public static LinkedHashMap<String, String> parseSections(String code) {
        return parseSections(code, null);
    }

    private static LinkedHashMap<String, String> parseSections(String code, @Nullable Map<String, Integer> starts) {
        LinkedHashMap<String, String> sections = new LinkedHashMap<>();
        Matcher matcher = REGION.matcher(code);
        while (matcher.find()) {
            if (sections.putIfAbsent(matcher.group(1), matcher.group(2)) == null && starts != null) {
                starts.put(matcher.group(1), matcher.start());
            }
        }
        return sections;
    }

    /**
     * Removes common indentation and surrounding blank lines so sections can be compared and stored
     * independently of how the generator indents them.
     */
    public static String normalizeBody(String body) {
        String[] lines = body.split("\\R", -1);
        int start = 0;
        int end = lines.length;
        while (start < end && lines[start].trim().isEmpty()) start++;
        while (end > start && lines[end - 1].trim().isEmpty()) end--;

        int indent = Integer.MAX_VALUE;
        for (int i = start; i < end; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            int leading = 0;
            while (leading < line.length() && (line.charAt(leading) == ' ' || line.charAt(leading) == '\t')) leading++;
            indent = Math.min(indent, leading);
        }
        if (indent == Integer.MAX_VALUE) indent = 0;

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            String line = lines[i].replaceAll("\\s+$", "");
            sb.append(line.length() >= indent ? line.substring(indent) : line.trim());
            if (i < end - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Finds the body of a method named {@code _<name>} by its declaration, without marker lines.
     *
     * @return The body, or {@code null} if no such method is declared.
     */
    @Nullable
    static String findMethodBody(String code, String name) {
        int[] range = findMethod(code, name);
        return range == null ? null : MARKER_LINE.matcher(code.substring(range[1] + 1, range[2])).replaceAll("");
    }

    private static String removeMethod(String code, String name) {
        int[] range = findMethod(code, name);
        return range == null ? code : code.substring(0, range[0]) + code.substring(range[2] + 1);
    }

    /**
     * @return {start of the declaration's line, index of "{", index of the closing "}"}, or {@code null}.
     */
    @Nullable
    private static int[] findMethod(String code, String name) {
        Matcher matcher = Pattern.compile("\\b_" + Pattern.quote(name) + "\\s*\\(").matcher(code);
        while (matcher.find()) {
            int paramsEnd = findClosing(code, matcher.end() - 1, '(', ')');
            if (paramsEnd < 0) continue;
            int open = paramsEnd + 1;
            while (open < code.length() && Character.isWhitespace(code.charAt(open))) open++;
            // A call such as "_name(x);" is followed by something other than "{".
            if (open >= code.length() || code.charAt(open) != '{') continue;
            int close = findClosing(code, open, '{', '}');
            if (close < 0) return null;
            int lineStart = code.lastIndexOf('\n', matcher.start()) + 1;
            return new int[]{lineStart, open, close};
        }
        return null;
    }

    /**
     * @return Index of the bracket closing the one at {@code start}, skipping strings, chars and
     * comments, or -1.
     */
    private static int findClosing(String code, int start, char openChar, char closeChar) {
        int depth = 0;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : 0;
            if (c == '/' && next == '/') {
                i = code.indexOf('\n', i);
                if (i < 0) return -1;
            } else if (c == '/' && next == '*') {
                i = code.indexOf("*/", i + 2);
                if (i < 0) return -1;
                i++;
            } else if (c == '"' || c == '\'') {
                for (i++; i < code.length() && code.charAt(i) != c; i++) {
                    if (code.charAt(i) == '\\') i++;
                }
            } else if (c == openChar) {
                depth++;
            } else if (c == closeChar && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The parts of a Java file outside the event sections: imports, other top-level code (package,
     * class declaration) and the members of the top-level class. Sections are reduced to their marker.
     */
    private static final class Outline {
        final ArrayList<String> imports = new ArrayList<>();
        final ArrayList<String> topLevel = new ArrayList<>();
        /** Normalized header (declaration up to "{", "=" or ";") to normalized member text. */
        final LinkedHashMap<String, String> members = new LinkedHashMap<>();
        /** Normalized header to the member as typed, with the comments before it and on its last line. */
        final HashMap<String, String> rawMembers = new HashMap<>();
        /** Normalized header to the member's code as typed, without those comments. */
        final HashMap<String, String> codeMembers = new HashMap<>();
    }

    private static Outline outline(String code) {
        StringBuilder masked = new StringBuilder();
        Matcher matcher = REGION.matcher(code);
        int last = 0;
        while (matcher.find()) {
            masked.append(code, last, matcher.start()).append(BEGIN).append(matcher.group(1));
            last = matcher.end();
        }
        masked.append(code.substring(last));
        String text = masked.toString();

        Outline outline = new Outline();
        int depth = 0;
        int start = -1;
        int commentStart = -1;
        int displayStart = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if ((c == '/' && next == '/') || (c == '/' && next == '*')) {
                // Comments between members belong to the member after them.
                if (depth == 1 && start < 0 && commentStart < 0) commentStart = i;
                if (next == '/') {
                    int lineEnd = text.indexOf('\n', i);
                    i = lineEnd < 0 ? text.length() : lineEnd;
                } else {
                    int commentEnd = text.indexOf("*/", i + 2);
                    i = commentEnd < 0 ? text.length() : commentEnd + 1;
                }
                continue;
            }
            if (Character.isWhitespace(c)) continue;
            if (start < 0) {
                start = i;
                displayStart = depth == 1 && commentStart >= 0 ? commentStart : i;
                commentStart = -1;
            }

            if (c == '"' || c == '\'') {
                for (i++; i < text.length() && text.charAt(i) != c; i++) {
                    if (text.charAt(i) == '\\') i++;
                }
            } else if (c == '{') {
                depth++;
                if (depth == 1) {
                    // Declaration of the top-level class.
                    outline.topLevel.add(collapse(text.substring(start, i + 1)));
                    start = -1;
                }
            } else if (c == '}') {
                depth--;
                if (depth == 1 && start >= 0) {
                    int end = i + 1;
                    int after = end;
                    while (after < text.length() && Character.isWhitespace(text.charAt(after))) after++;
                    if (after < text.length() && text.charAt(after) == ';') end = after + 1;
                    int displayEnd = trailingCommentEnd(text, end);
                    addMember(outline, text.substring(start, end), text.substring(displayStart, displayEnd));
                    i = displayEnd - 1;
                    start = -1;
                } else if (depth == 0) {
                    start = -1;
                }
            } else if (c == ';') {
                if (depth == 0) {
                    String statement = collapse(text.substring(start, i + 1));
                    (statement.startsWith("import ") ? outline.imports : outline.topLevel).add(statement);
                    start = -1;
                } else if (depth == 1) {
                    int displayEnd = trailingCommentEnd(text, i + 1);
                    addMember(outline, text.substring(start, i + 1), text.substring(displayStart, displayEnd));
                    i = displayEnd - 1;
                    start = -1;
                }
            }
        }
        return outline;
    }

    /**
     * @return End of a {@code //} comment on the same line after {@code end}, or {@code end}.
     */
    private static int trailingCommentEnd(String text, int end) {
        int i = end;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (text.startsWith("//", i)) {
            int lineEnd = text.indexOf('\n', i);
            return lineEnd < 0 ? text.length() : lineEnd;
        }
        return end;
    }

    private static void addMember(Outline outline, String raw, String display) {
        String member = collapse(raw);
        int headerEnd = member.length();
        for (char c : new char[]{'{', '=', ';'}) {
            int index = member.indexOf(c);
            if (index >= 0) headerEnd = Math.min(headerEnd, index);
        }
        String header = member.substring(0, headerEnd).trim();
        outline.members.putIfAbsent(header, member);
        outline.rawMembers.putIfAbsent(header, display);
        outline.codeMembers.putIfAbsent(header, raw);
    }

    private static String collapse(String code) {
        return code.replaceAll("\\s+", " ").trim();
    }

    /**
     * Compares the code outside the sections. New imports and new class members are collected; changes to
     * generated code can't be stored and are counted as discarded.
     */
    private static void diffOutside(String generated, String edited, Plan plan,
                                    StringBuilder imports, StringBuilder members,
                                    List<Pair<Integer, String>> variables) {
        Outline before = outline(generated);
        Outline after = outline(edited);

        for (String anImport : after.imports) {
            if (!before.imports.contains(anImport) && imports.indexOf(anImport) < 0) {
                imports.append(anImport).append('\n');
                plan.movedImports++;
            }
        }
        for (String anImport : before.imports) {
            if (after.imports.contains(anImport)) continue;
            Pair<Integer, String> variable = findImport(variables, anImport);
            if (variable != null) {
                plan.removedVariables.add(variable);
            } else {
                plan.discardedChanges++;
            }
        }
        if (!before.topLevel.equals(after.topLevel)) plan.discardedChanges++;

        for (Map.Entry<String, String> member : after.members.entrySet()) {
            String generatedMember = before.members.get(member.getKey());
            if (generatedMember == null) {
                members.append(after.rawMembers.get(member.getKey())).append("\n\n");
                plan.movedMembers++;
            } else if (!generatedMember.equals(member.getValue())) {
                Pair<Integer, String> variable = findVariable(variables, member.getKey(), generatedMember);
                String declaration = fieldDeclaration(member.getValue());
                if (variable != null && variable.first == 6 && declaration != null) {
                    plan.changedVariables.put(variable, declaration);
                } else {
                    plan.discardedChanges++;
                }
            }
        }
        for (Map.Entry<String, String> member : before.members.entrySet()) {
            if (after.members.containsKey(member.getKey())) continue;
            Pair<Integer, String> variable = findVariable(variables, member.getKey(), member.getValue());
            if (variable != null) {
                plan.removedVariables.add(variable);
            } else {
                plan.discardedChanges++;
            }
        }
    }

    /**
     * @return The project variable a generated field declaration comes from, or {@code null}.
     */
    @Nullable
    private static Pair<Integer, String> findVariable(List<Pair<Integer, String>> variables, String header, String member) {
        String[] words = header.trim().split("\\s+");
        String name = words[words.length - 1];
        for (Pair<Integer, String> variable : variables) {
            if (variable.first == 6) {
                if (collapse(variable.second + ";").equals(member)) return variable;
            } else if (name.equals(variableName(variable))) {
                return variable;
            }
        }
        return null;
    }

    @Nullable
    private static Pair<Integer, String> findImport(List<Pair<Integer, String>> variables, String anImport) {
        for (Pair<Integer, String> variable : variables) {
            if (variable.first == 9 && anImport.equals("import " + variable.second + ";")) return variable;
        }
        return null;
    }

    private Set<String> collectEventKeys(eC dataManager, String javaName,
                                         @Nullable HashMap<String, ArrayList<BlockBean>> blocks) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("onCreate_initializeLogic");
        if (blocks != null) keys.addAll(blocks.keySet());
        ArrayList<EventBean> events = dataManager.g(javaName);
        if (events != null) {
            for (EventBean event : events) {
                keys.add(event.targetId + "_" + event.eventName);
            }
        }
        ArrayList<Pair<String, String>> moreBlocks = dataManager.i(javaName);
        if (moreBlocks != null) {
            for (Pair<String, String> moreBlock : moreBlocks) {
                keys.add(moreBlock.first + MORE_BLOCK_SUFFIX);
            }
        }
        keys.add(Jx.JAVA_EDITOR_IMPORTS_KEY);
        keys.add(Jx.JAVA_EDITOR_MEMBERS_KEY);
        return keys;
    }

    /**
     * Returns a copy of {@code blocks} with marker blocks around the top-level chain.
     * The project's own {@link BlockBean}s are never modified; the chain's last block is cloned.
     */
    private static ArrayList<BlockBean> wrapWithMarkers(String key, @Nullable ArrayList<BlockBean> blocks) {
        ArrayList<BlockBean> wrapped = new ArrayList<>();
        // Some events place their code in the middle of a line; the newlines keep each marker on a line of
        // its own, so it can't comment out generated code after it and is always found when saving.
        BlockBean begin = createAsdBlock(String.valueOf(MARKER_BEGIN_ID), "\n" + BEGIN + key);
        BlockBean end = createAsdBlock(String.valueOf(MARKER_END_ID), END + key + "\n");
        wrapped.add(begin);

        if (blocks == null || blocks.isEmpty()) {
            begin.nextBlock = MARKER_END_ID;
        } else {
            HashMap<String, Integer> indexById = new HashMap<>();
            for (int i = 0; i < blocks.size(); i++) {
                indexById.put(blocks.get(i).id, i);
            }
            ArrayList<BlockBean> copy = new ArrayList<>(blocks);
            int lastIndex = 0;
            Set<Integer> visited = new LinkedHashSet<>();
            while (visited.add(lastIndex)) {
                int next = copy.get(lastIndex).nextBlock;
                Integer nextIndex = next >= 0 ? indexById.get(String.valueOf(next)) : null;
                if (nextIndex == null) break;
                lastIndex = nextIndex;
            }
            BlockBean last = copy.get(lastIndex).clone();
            last.nextBlock = MARKER_END_ID;
            copy.set(lastIndex, last);

            begin.nextBlock = parseId(copy.get(0).id);
            wrapped.addAll(copy);
        }
        wrapped.add(end);
        return wrapped;
    }

    private static int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @NonNull
    private static BlockBean createAsdBlock(String id, String code) {
        BlockBean block = new BlockBean(id, ASD_SPEC, " ", ASD_OPCODE);
        block.color = ASD_COLOR;
        block.parameters.add(code);
        return block;
    }
}
