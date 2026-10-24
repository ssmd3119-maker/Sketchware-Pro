package pro.sketchware.activities.editor.view;

import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.besome.sketch.beans.BlockBean;
import com.besome.sketch.beans.EventBean;
import com.besome.sketch.beans.ProjectFileBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.Jx;
import a.a.a.eC;
import a.a.a.jC;
import a.a.a.yq;
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

        public boolean hasChanges() {
            return !sections.isEmpty() || !renamedMoreBlocks.isEmpty() || !deletedMoreBlocks.isEmpty();
        }

        public String summary() {
            ArrayList<String> parts = new ArrayList<>();
            int events = 0;
            for (String key : sections.keySet()) {
                if (!isJavaEditorKey(key)) events++;
            }
            if (events > 0) parts.add(events + (events == 1 ? " event" : " events") + " saved as blocks");
            if (sections.containsKey(Jx.JAVA_EDITOR_MEMBERS_KEY) || sections.containsKey(Jx.JAVA_EDITOR_IMPORTS_KEY)) {
                parts.add("custom code saved");
            }
            if (!renamedMoreBlocks.isEmpty()) parts.add(renamedMoreBlocks.size() + " More Block(s) renamed");
            if (!deletedMoreBlocks.isEmpty()) parts.add(deletedMoreBlocks.size() + " More Block(s) deleted");
            if (parts.isEmpty()) return "No changes to save";
            String summary = String.join(", ", parts);
            return Character.toUpperCase(summary.charAt(0)) + summary.substring(1);
        }
    }

    /**
     * Works out how to store {@code edited}, given the {@code generated} code it started from.
     */
    public Plan plan(String generated, String edited) {
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
            if (!newBody.equals(normalizeBody(applyRenames(entry.getValue(), renames)))) {
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
        diffOutside(generatedOutside, applyRenames(edited, renames), plan, imports, members);
        if (imports.length() > 0) {
            plan.sections.put(Jx.JAVA_EDITOR_IMPORTS_KEY, joinBodies(
                    currentBody(Jx.JAVA_EDITOR_IMPORTS_KEY, plan, generatedSections, editedSections), imports.toString()));
        }
        if (members.length() > 0) {
            plan.sections.put(Jx.JAVA_EDITOR_MEMBERS_KEY, joinBodies(
                    currentBody(Jx.JAVA_EDITOR_MEMBERS_KEY, plan, generatedSections, editedSections), members.toString()));
        }
        return plan;
    }

    /**
     * Stores a {@link Plan} in the project's blocks. Changes are made in memory, like the Logic editor's;
     * the Design screen saves them with the project.
     */
    public void apply(Plan plan) {
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
                if (!code.isEmpty()) newBlocks.add(createAsdBlock("10", code));
                if (newBlocks.isEmpty() && isJavaEditorKey(key)) {
                    blocks.remove(key);
                } else {
                    dataManager.a(javaName, key, newBlocks);
                }
            }

            for (String moreBlock : plan.deletedMoreBlocks) {
                dataManager.n(javaName, moreBlock);
            }
        }
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

    private static String currentBody(String key, Plan plan, Map<String, String> generatedSections,
                                      Map<String, String> editedSections) {
        if (plan.sections.containsKey(key)) return plan.sections.get(key);
        String body = editedSections.get(key);
        if (body == null) body = generatedSections.get(key);
        return body == null ? "" : normalizeBody(body);
    }

    private static String joinBodies(String first, String second) {
        String added = normalizeBody(second);
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
        /** Normalized header to the member's code as typed. */
        final HashMap<String, String> rawMembers = new HashMap<>();
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
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (c == '/' && next == '/') {
                int lineEnd = text.indexOf('\n', i);
                i = lineEnd < 0 ? text.length() : lineEnd;
                continue;
            } else if (c == '/' && next == '*') {
                int commentEnd = text.indexOf("*/", i + 2);
                i = commentEnd < 0 ? text.length() : commentEnd + 1;
                continue;
            }
            if (Character.isWhitespace(c)) continue;
            if (start < 0) start = i;

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
                    addMember(outline, text.substring(start, end));
                    i = end - 1;
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
                    addMember(outline, text.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return outline;
    }

    private static void addMember(Outline outline, String raw) {
        String member = collapse(raw);
        int headerEnd = member.length();
        for (char c : new char[]{'{', '=', ';'}) {
            int index = member.indexOf(c);
            if (index >= 0) headerEnd = Math.min(headerEnd, index);
        }
        String header = member.substring(0, headerEnd).trim();
        outline.members.putIfAbsent(header, member);
        outline.rawMembers.putIfAbsent(header, raw);
    }

    private static String collapse(String code) {
        return code.replaceAll("\\s+", " ").trim();
    }

    /**
     * Compares the code outside the sections. New imports and new class members are collected; changes to
     * generated code can't be stored and are counted as discarded.
     */
    private static void diffOutside(String generated, String edited, Plan plan,
                                    StringBuilder imports, StringBuilder members) {
        Outline before = outline(generated);
        Outline after = outline(edited);

        for (String anImport : after.imports) {
            if (!before.imports.contains(anImport) && imports.indexOf(anImport) < 0) {
                imports.append(anImport).append('\n');
                plan.movedImports++;
            }
        }
        for (String anImport : before.imports) {
            if (!after.imports.contains(anImport)) plan.discardedChanges++;
        }
        if (!before.topLevel.equals(after.topLevel)) plan.discardedChanges++;

        for (Map.Entry<String, String> member : after.members.entrySet()) {
            String generatedMember = before.members.get(member.getKey());
            if (generatedMember == null) {
                members.append(after.rawMembers.get(member.getKey())).append("\n\n");
                plan.movedMembers++;
            } else if (!generatedMember.equals(member.getValue())) {
                plan.discardedChanges++;
            }
        }
        for (String header : before.members.keySet()) {
            if (!after.members.containsKey(header)) plan.discardedChanges++;
        }
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
        BlockBean begin = createAsdBlock(String.valueOf(MARKER_BEGIN_ID), BEGIN + key);
        BlockBean end = createAsdBlock(String.valueOf(MARKER_END_ID), END + key);
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
