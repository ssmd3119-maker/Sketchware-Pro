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

import a.a.a.eC;
import a.a.a.jC;
import a.a.a.yq;

/**
 * Converts an activity's blocks to Java and edited Java back to blocks, without any extra files.
 * <p>
 * To know which part of the generated Java belongs to which event, the activity is generated once with
 * every event's blocks wrapped in two temporary "add source directly" blocks that emit
 * {@code //#region sw:<eventKey>} and {@code //#endregion sw:<eventKey>}. The project's blocks are restored
 * right after generating.
 * <p>
 * When saving, each section whose code changed replaces that event's blocks with a single
 * "add source directly" block holding the new code. Unchanged events keep their blocks.
 */
public class EventCodeBridge {

    private static final String BEGIN = "//#region sw:";
    private static final String END = "//#endregion sw:";
    private static final Pattern REGION = Pattern.compile(
            "^[ \\t]*" + Pattern.quote(BEGIN) + "(\\S+)[ \\t]*\\R(.*?)^[ \\t]*" + Pattern.quote(END) + "\\1[ \\t]*$",
            Pattern.MULTILINE | Pattern.DOTALL);

    private static final String ASD_SPEC = "add source directly %s.inputOnly";
    private static final String ASD_OPCODE = "addSourceDirectly";
    /** Colour of the operator palette, where "add source directly" lives. */
    private static final int ASD_COLOR = 0xff5cb722;
    private static final int MARKER_BEGIN_ID = 2000000001;
    private static final int MARKER_END_ID = 2000000002;

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
     * Splits marked Java into event sections.
     *
     * @return Event key to section body, in order of appearance. Duplicate sections keep the first.
     */
    public static LinkedHashMap<String, String> parseSections(String code) {
        LinkedHashMap<String, String> sections = new LinkedHashMap<>();
        Matcher matcher = REGION.matcher(code);
        while (matcher.find()) {
            sections.putIfAbsent(matcher.group(1), matcher.group(2));
        }
        return sections;
    }

    /**
     * Everything outside the event sections, normalized, for detecting edits that can't become blocks.
     */
    public static String skeleton(String code) {
        Matcher matcher = REGION.matcher(code);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(BEGIN + matcher.group(1)));
        }
        matcher.appendTail(sb);
        StringBuilder normalized = new StringBuilder();
        for (String line : sb.toString().split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) normalized.append(trimmed).append('\n');
        }
        return normalized.toString();
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
     * Stores the code of each changed section in its event as one "add source directly" block.
     *
     * @param changedSections Event key to new, normalized code. Empty code clears the event's blocks.
     */
    public void applyChanges(Map<String, String> changedSections) {
        eC dataManager = jC.a(scId);
        String javaName = projectFile.getJavaName();
        synchronized (dataManager) {
            for (Map.Entry<String, String> entry : changedSections.entrySet()) {
                ArrayList<BlockBean> blocks = new ArrayList<>();
                if (!entry.getValue().isEmpty()) {
                    blocks.add(createAsdBlock("10", entry.getValue()));
                }
                dataManager.a(javaName, entry.getKey(), blocks);
            }
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
                keys.add(moreBlock.first + "_moreBlock");
            }
        }
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
