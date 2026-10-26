package pro.sketchware.agent;

import androidx.annotation.Nullable;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools the agent can call, their schemas, and how they run against a {@link ProjectAccess}.
 */
public class AgentTools {

    private record Param(String name, String type, String description, boolean required) {
    }

    private record Spec(String name, String description, boolean changesProject, List<Param> params) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("project_info", "Overview of the project: app name, package, activities with their Java and layout"
                    + " files, custom views, and the top-level folders of the project folder. Call this first.", false, List.of()),
            new Spec("list_files", "List files in a folder of the project folder (or generated/ for the last build's"
                    + " sources).", false, List.of(
                    new Param("path", "string", "Folder relative to the project folder, e.g. \"files/java\". Empty for the project folder.", false),
                    new Param("recursive", "boolean", "Also list subfolders. Default false.", false))),
            new Spec("read_file", "Read a text file of the project folder (or generated/...). Long files are returned in"
                    + " parts; use offset and limit to read the rest.", false, List.of(
                    new Param("path", "string", "File relative to the project folder, e.g. \"files/resource/values/strings.xml\".", true),
                    new Param("offset", "integer", "First line to read, starting at 1.", false),
                    new Param("limit", "integer", "Number of lines to read.", false))),
            new Spec("search_files", "Search the project's files for a regular expression (Java regex syntax), returning"
                    + " matching lines with their file and line number.", false, List.of(
                    new Param("pattern", "string", "Regular expression to find.", true),
                    new Param("path", "string", "Folder or file to search, relative to the project folder. Empty for everything.", false),
                    new Param("file_name_contains", "string", "Only search files whose name contains this text, e.g. \".java\".", false))),
            new Spec("write_file", "Create or overwrite a text file in the project folder. Parent folders are created.",
                    true, List.of(
                    new Param("path", "string", "File relative to the project folder, e.g. \"files/java/com/my/app/Helper.java\".", true),
                    new Param("content", "string", "The complete new content of the file.", true))),
            new Spec("edit_file", "Replace text in a file of the project folder. old_string must match the file exactly"
                    + " (including indentation) and be unique unless replace_all is set.", true, List.of(
                    new Param("path", "string", "File relative to the project folder.", true),
                    new Param("old_string", "string", "Exact text to replace.", true),
                    new Param("new_string", "string", "Text to put in its place.", true),
                    new Param("replace_all", "boolean", "Replace every occurrence. Default false.", false))),
            new Spec("delete_file", "Delete a file or a folder (with its contents) in the project folder.", true, List.of(
                    new Param("path", "string", "File or folder relative to the project folder.", true))),
            new Spec("read_activity_java", "Get an activity's complete Java source as generated from its blocks."
                    + " Every \"//#region sw:<key>\" ... \"//#endregion sw:<key>\" section holds one event's or More"
                    + " Block's code.", false, List.of(
                    new Param("activity", "string", "Java file of the activity, e.g. \"MainActivity.java\".", true))),
            new Spec("edit_activity_java", "Change an activity's Java by replacing text in the code read_activity_java"
                    + " returns, then save it as blocks like the Direct Java editor does. Prefer this over"
                    + " write_activity_java for small changes.", true, List.of(
                    new Param("activity", "string", "Java file of the activity, e.g. \"MainActivity.java\".", true),
                    new Param("old_string", "string", "Exact text of the current code to replace.", true),
                    new Param("new_string", "string", "Text to put in its place.", true),
                    new Param("replace_all", "boolean", "Replace every occurrence. Default false.", false))),
            new Spec("write_activity_java", "Save a complete new version of an activity's Java (based on what"
                    + " read_activity_java returned) as blocks, like the Direct Java editor does.", true, List.of(
                    new Param("activity", "string", "Java file of the activity, e.g. \"MainActivity.java\".", true),
                    new Param("code", "string", "The complete Java source, keeping the region markers.", true))),
            new Spec("read_layout_xml", "Get a layout's XML as the Direct XML editor shows it.", false, List.of(
                    new Param("layout", "string", "Layout file, e.g. \"main.xml\".", true))),
            new Spec("edit_layout_xml", "Change a layout by replacing text in the XML read_layout_xml returns, then save"
                    + " it as views like the Direct XML editor does.", true, List.of(
                    new Param("layout", "string", "Layout file, e.g. \"main.xml\".", true),
                    new Param("old_string", "string", "Exact text of the current XML to replace.", true),
                    new Param("new_string", "string", "Text to put in its place.", true),
                    new Param("replace_all", "boolean", "Replace every occurrence. Default false.", false))),
            new Spec("write_layout_xml", "Replace a layout's views with complete new XML, like the Direct XML editor"
                    + " does.", true, List.of(
                    new Param("layout", "string", "Layout file, e.g. \"main.xml\".", true),
                    new Param("xml", "string", "The complete layout XML.", true)))
    );

    private final ProjectAccess project;

    public AgentTools(ProjectAccess project) {
        this.project = project;
    }

    /**
     * @param eagerInputStreaming Stream tool inputs as they're generated; Claude-specific, so off for other models.
     */
    public static List<Tool> definitions(boolean eagerInputStreaming) {
        ArrayList<Tool> tools = new ArrayList<>();
        for (Spec spec : SPECS) {
            Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
            ArrayList<String> required = new ArrayList<>();
            for (Param param : spec.params()) {
                properties.putAdditionalProperty(param.name(), JsonValue.from(Map.of(
                        "type", param.type(), "description", param.description())));
                if (param.required()) required.add(param.name());
            }
            Tool.Builder tool = Tool.builder()
                    .name(spec.name())
                    .description(spec.description())
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(properties.build())
                            .required(required)
                            .build());
            // File bodies and code stream as they're generated instead of after a long silent pause.
            if (eagerInputStreaming) tool.eagerInputStreaming(true);
            tools.add(tool.build());
        }
        return tools;
    }

    @Nullable
    private static Spec spec(String name) {
        for (Spec spec : SPECS) {
            if (spec.name().equals(name)) return spec;
        }
        return null;
    }

    public static boolean changesProject(String name) {
        Spec spec = spec(name);
        return spec != null && spec.changesProject();
    }

    /**
     * Checks a tool input against its schema. Inputs stream without server-side validation, so a truncated or
     * malformed input must not run.
     *
     * @return What's wrong, or {@code null} if the input is valid.
     */
    @Nullable
    public static String validate(String name, @Nullable Map<String, Object> input) {
        Spec spec = spec(name);
        if (spec == null) return "Unknown tool " + name;
        if (input == null) return "The input isn't a JSON object";
        for (Param param : spec.params()) {
            Object value = input.get(param.name());
            if (value == null) {
                if (param.required()) return "Missing required parameter \"" + param.name() + "\"";
                continue;
            }
            boolean ok = switch (param.type()) {
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "integer" -> value instanceof Number;
                default -> true;
            };
            if (!ok) return "Parameter \"" + param.name() + "\" must be a " + param.type();
        }
        return null;
    }

    /**
     * @return A short description of a call for the chat, e.g. "Edit files/java/Helper.java".
     */
    public static String label(String name, Map<String, Object> input) {
        String target = firstString(input, "path", "activity", "layout");
        if (target == null) target = "(no file given)";
        String label = switch (name) {
            case "project_info" -> "Read project overview";
            case "list_files" -> "List " + (input.get("path") instanceof String path && !path.isEmpty() ? path : "project folder");
            case "read_file" -> "Read " + target;
            case "search_files" -> "Search for \"" + string(input, "pattern") + "\"";
            case "write_file" -> "Write " + target;
            case "edit_file" -> "Edit " + target;
            case "delete_file" -> "Delete " + target;
            case "read_activity_java" -> "Read " + target;
            case "edit_activity_java", "write_activity_java" -> "Change " + target;
            case "read_layout_xml" -> "Read layout " + target;
            case "edit_layout_xml", "write_layout_xml" -> "Change layout " + target;
            default -> name;
        };
        return label;
    }

    /**
     * @return What a change will do, for the user to approve.
     */
    public static String preview(String name, Map<String, Object> input) {
        StringBuilder out = new StringBuilder();
        switch (name) {
            case "write_file" -> out.append("New content:\n\n").append(limit(string(input, "content")));
            case "write_activity_java" -> out.append("New Java (saved as blocks):\n\n").append(limit(string(input, "code")));
            case "write_layout_xml" -> out.append("New XML:\n\n").append(limit(string(input, "xml")));
            case "edit_file", "edit_activity_java", "edit_layout_xml" -> out
                    .append("Replace").append(Boolean.TRUE.equals(input.get("replace_all")) ? " every occurrence of" : "").append(":\n\n")
                    .append(limit(string(input, "old_string")))
                    .append("\n\nwith:\n\n")
                    .append(limit(string(input, "new_string")));
            case "delete_file" -> out.append("The file or folder and everything in it will be deleted.");
            default -> out.append(name);
        }
        return out.toString();
    }

    public String run(String name, Map<String, Object> input) throws ProjectAccess.AccessException, IOException {
        return switch (name) {
            case "project_info" -> project.describeProject();
            case "list_files" -> project.listFiles(string(input, "path"), Boolean.TRUE.equals(input.get("recursive")));
            case "read_file" -> project.readFile(string(input, "path"), integer(input, "offset"), integer(input, "limit"));
            case "search_files" -> project.search(string(input, "pattern"), string(input, "path"), (String) input.get("file_name_contains"));
            case "write_file" -> project.writeFile(string(input, "path"), string(input, "content"));
            case "edit_file" -> project.editFile(string(input, "path"), string(input, "old_string"), string(input, "new_string"),
                    Boolean.TRUE.equals(input.get("replace_all")));
            case "delete_file" -> project.delete(string(input, "path"));
            case "read_activity_java" -> project.readActivityJava(string(input, "activity"));
            case "edit_activity_java" -> project.editActivityJava(string(input, "activity"), string(input, "old_string"),
                    string(input, "new_string"), Boolean.TRUE.equals(input.get("replace_all")));
            case "write_activity_java" -> project.writeActivityJava(string(input, "activity"), string(input, "code"));
            case "read_layout_xml" -> project.readLayoutXml(string(input, "layout"));
            case "edit_layout_xml" -> project.editLayoutXml(string(input, "layout"), string(input, "old_string"),
                    string(input, "new_string"), Boolean.TRUE.equals(input.get("replace_all")));
            case "write_layout_xml" -> project.writeLayoutXml(string(input, "layout"), string(input, "xml"));
            default -> throw new ProjectAccess.AccessException("Unknown tool " + name);
        };
    }

    private static String string(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value instanceof String ? (String) value : "";
    }

    private static int integer(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    @Nullable
    private static String firstString(Map<String, Object> input, String... keys) {
        for (String key : keys) {
            if (input.get(key) instanceof String value) return value;
        }
        return null;
    }

    private static String limit(String text) {
        return text.length() > 6000 ? text.substring(0, 6000) + "\n... (" + (text.length() - 6000) + " more characters)" : text;
    }

    public static Map<String, Object> emptyInput() {
        return new LinkedHashMap<>();
    }
}
