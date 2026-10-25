package pro.sketchware.activities.editor.view;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.besome.sketch.beans.BlockBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.Fx;
import a.a.a.Gx;
import a.a.a.jq;

/**
 * Converts Java code back into real blocks.
 * <p>
 * Every known block is turned into a code template by generating it with {@link Fx}, the same generator
 * that builds the app, with a marker in each of its slots, e.g. {@code __swParam0__.setText(__swParam1__);}.
 * Statements are then matched against these templates token by token, recursing into the code of
 * {@code if}/{@code else}/loop bodies and into nested values, which become reporter blocks, variables,
 * literals, or typed "add source directly" reporters.
 * <p>
 * Every converted statement is generated again and compared with the original code, so conversion never
 * changes what the code does. A statement that can't be matched, or whose blocks would produce different
 * code, is kept as an "add source directly" block; consecutive ones share a single block.
 */
public class JavaToBlocks {

    static final int KIND_BOOLEAN = 0;
    static final int KIND_NUMBER = 1;
    static final int KIND_STRING = 2;
    static final int KIND_RAW = 3;

    private static final String PARAM_MARKER_PREFIX = "__swParam";
    private static final String MARKER_SUFFIX = "__";
    private static final String STACK1_MARKER = "__swStack1__";
    private static final String STACK2_MARKER = "__swStack2__";
    private static final int TEMPLATE_ID = 1;
    private static final int PARAM_ID_BASE = 100;
    private static final int STACK1_ID = 200;
    private static final int STACK2_ID = 201;
    private static final int FIRST_BLOCK_ID = 10;
    private static final int MAX_MATCHES = 16;
    /** Upper bound of blocks tried per event, so deeply nested code can't make conversion slow. */
    private static final int MAX_ATTEMPTS = 20000;

    private static final int SLOT_STACK1 = -1;
    private static final int SLOT_STACK2 = -2;
    private static final int SLOT_REPEAT_VARIABLE = -3;

    /** Same as {@code Fx#extractParamsTypes}. */
    private static final Pattern PARAM_TYPES = Pattern.compile("%\\w+(?:\\.\\w+)?|%\\w");
    private static final Pattern REPEAT_VARIABLE = Pattern.compile("_repeat(\\d{1,9})");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Set<String> BLOCK_STATEMENT_KEYWORDS = Set.of(
            "if", "for", "while", "do", "try", "switch", "synchronized", "{");
    private static final String[] OPERATORS = {
            ">>>=", "<<=", ">>=", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<"};

    /**
     * A block that can be created, as the Logic editor's palettes define it.
     */
    public static final class BlockDef {
        final String opCode;
        final String type;
        final String typeName;
        final String spec;
        /** Whether it's one of the original palette blocks, preferred over Custom Blocks producing the same code. */
        final boolean core;

        public BlockDef(String opCode, String type, String typeName, String spec) {
            this(opCode, type, typeName, spec, false);
        }

        public BlockDef(String opCode, String type, String typeName, String spec, boolean core) {
            this.opCode = opCode;
            this.type = type;
            this.typeName = typeName == null ? "" : typeName;
            this.spec = spec;
            this.core = core;
        }
    }

    /**
     * A name usable as a reporter: a variable or list ({@code getVar}) or a More Block parameter
     * ({@code getArg}).
     *
     * @param code What the reporter generates, e.g. {@code count} or {@code _text}.
     */
    public record Reporter(String code, BlockDef def) {
    }

    /**
     * Supplies block colours; {@code null} leaves them at 0, as the Logic editor recomputes them anyway.
     */
    public interface ColorProvider {
        int color(String opCode, String type);
    }

    private final String activityName;
    private final jq buildConfig;
    private final boolean viewBinding;
    private final Map<String, BlockDef> defsByOpCode = new HashMap<>();
    private final List<BlockDef> defs;
    private final Map<String, Reporter> reporters = new HashMap<>();
    @Nullable
    private final ColorProvider colors;

    private List<Template> statementTemplates;
    private Map<String, List<Template>> reporterTemplates;

    public JavaToBlocks(String activityName, jq buildConfig, boolean viewBinding, List<BlockDef> defs,
                        List<Reporter> reporters, @Nullable ColorProvider colors) {
        this.activityName = activityName;
        this.buildConfig = buildConfig;
        this.viewBinding = viewBinding;
        this.defs = defs;
        this.colors = colors;
        for (BlockDef def : defs) {
            if (!"definedFunc".equals(def.opCode)) defsByOpCode.putIfAbsent(def.opCode, def);
        }
        for (Reporter reporter : reporters) {
            this.reporters.putIfAbsent(reporter.code, reporter);
        }
    }

    /**
     * Converts the code of an event to its blocks.
     *
     * @return The blocks, the first one being the start of the event's chain; empty for empty code.
     */
    public ArrayList<BlockBean> convert(String code) {
        return convert(code, Collections.emptyList());
    }

    /**
     * @param extraReporters Reporters only valid in this event, e.g. a More Block's parameters.
     */
    public ArrayList<BlockBean> convert(String code, List<Reporter> extraReporters) {
        code = braceElseIf(code);
        Conversion conversion = new Conversion(code, extraReporters);
        int first = conversion.convertStatements(0, conversion.tokens.size());
        ArrayList<BlockBean> blocks = conversion.blocks;
        if (first < 0) return new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).id.equals(String.valueOf(first))) {
                Collections.swap(blocks, 0, i);
                break;
            }
        }

        // Safety net: the blocks must produce the same code as a whole.
        boolean same;
        try {
            same = sameTokens(tokenize(generate(blocks)), conversion.tokens, 0, conversion.tokens.size());
        } catch (RuntimeException | LinkageError e) {
            same = false;
        }
        if (!same) {
            ArrayList<BlockBean> fallback = new ArrayList<>();
            BlockBean asd = newBlock(FIRST_BLOCK_ID, addSourceDirectlyDef());
            asd.parameters.add(code);
            fallback.add(asd);
            return fallback;
        }
        return blocks;
    }

    // region Templates

    private record Slot(int index) {
    }

    private static final class Template {
        final BlockDef def;
        final Object[] parts;
        final int[] kinds;
        final List<String> paramTypes;
        final Set<String> literals = new HashSet<>();
        final boolean usesRepeatVariable;

        Template(BlockDef def, Object[] parts, int[] kinds, List<String> paramTypes, boolean usesRepeatVariable) {
            this.def = def;
            this.parts = parts;
            this.kinds = kinds;
            this.paramTypes = paramTypes;
            this.usesRepeatVariable = usesRepeatVariable;
            for (Object part : parts) {
                if (part instanceof String literal) literals.add(literal);
            }
        }

        int captureCount() {
            return kinds.length + 3;
        }

        static int captureIndex(Slot slot, int paramCount) {
            return switch (slot.index) {
                case SLOT_STACK1 -> paramCount;
                case SLOT_STACK2 -> paramCount + 1;
                case SLOT_REPEAT_VARIABLE -> paramCount + 2;
                default -> slot.index;
            };
        }
    }

    private void ensureTemplates() {
        if (statementTemplates != null) return;
        statementTemplates = new ArrayList<>();
        reporterTemplates = new HashMap<>();
        for (BlockDef def : defs) {
            if (def.spec == null || def.spec.isEmpty() || isSpecialOpCode(def.opCode)) continue;
            Template template = buildTemplate(def);
            if (template == null) continue;
            switch (def.type) {
                case " ", "c", "e", "f" -> statementTemplates.add(template);
                case "h" -> {
                }
                default -> reporterTemplates.computeIfAbsent(def.type, k -> new ArrayList<>()).add(template);
            }
        }
        // Prefer the most specific templates.
        Comparator<Template> specificity = Comparator.comparingInt(t -> -t.literals.size());
        statementTemplates.sort(specificity);
        for (List<Template> templates : reporterTemplates.values()) templates.sort(specificity);
    }

    private static boolean isSpecialOpCode(String opCode) {
        return switch (opCode) {
            case "getVar", "getArg", "getResStr", "addSourceDirectly", "asdString", "asdNumber", "asdBoolean" -> true;
            default -> false;
        };
    }

    @Nullable
    private Template buildTemplate(BlockDef def) {
        ArrayList<String> paramTypes = paramTypes(def.spec);
        BlockBean block;
        try {
            block = new BlockBean(String.valueOf(TEMPLATE_ID), def.spec, def.type, def.typeName, def.opCode);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        ArrayList<BlockBean> blocks = new ArrayList<>();
        blocks.add(block);
        int[] kinds = new int[paramTypes.size()];
        ArrayList<Gx> classInfo = block.getParamClassInfo();
        for (int i = 0; i < paramTypes.size(); i++) {
            block.parameters.add("@" + (PARAM_ID_BASE + i));
            blocks.add(placeholder(PARAM_ID_BASE + i, PARAM_MARKER_PREFIX + i + MARKER_SUFFIX));
            kinds[i] = kindOf(classInfo, i);
        }
        if ("c".equals(def.type) || "e".equals(def.type)) {
            block.subStack1 = STACK1_ID;
            blocks.add(placeholder(STACK1_ID, STACK1_MARKER));
            block.subStack2 = STACK2_ID;
            blocks.add(placeholder(STACK2_ID, STACK2_MARKER));
        }

        String code;
        try {
            code = generate(blocks);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (code == null || code.trim().isEmpty()) return null;

        ArrayList<Object> parts = new ArrayList<>();
        boolean usesRepeatVariable = false;
        int literals = 0;
        for (Token token : tokenize(code)) {
            String text = token.text;
            if (token.comment) return null;
            if (text.startsWith(PARAM_MARKER_PREFIX) && text.endsWith(MARKER_SUFFIX)) {
                try {
                    parts.add(new Slot(Integer.parseInt(text.substring(PARAM_MARKER_PREFIX.length(),
                            text.length() - MARKER_SUFFIX.length()))));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (text.equals(STACK1_MARKER)) {
                parts.add(new Slot(SLOT_STACK1));
            } else if (text.equals(STACK2_MARKER)) {
                parts.add(new Slot(SLOT_STACK2));
            } else if (text.equals("_repeat" + TEMPLATE_ID)) {
                parts.add(new Slot(SLOT_REPEAT_VARIABLE));
                usesRepeatVariable = true;
            } else if (text.contains("__sw")) {
                // A marker glued to other text can't be matched reliably.
                return null;
            } else {
                parts.add(text);
                literals++;
            }
        }
        if (literals == 0) return null;
        return new Template(def, parts.toArray(), kinds, paramTypes, usesRepeatVariable);
    }

    private static int kindOf(ArrayList<Gx> classInfo, int index) {
        if (classInfo == null || index >= classInfo.size()) return KIND_RAW;
        Gx info = classInfo.get(index);
        if (info.b("boolean")) return KIND_BOOLEAN;
        if (info.b("double")) return KIND_NUMBER;
        if (info.b("String")) return KIND_STRING;
        return KIND_RAW;
    }

    private static ArrayList<String> paramTypes(String spec) {
        ArrayList<String> types = new ArrayList<>();
        Matcher matcher = PARAM_TYPES.matcher(spec);
        while (matcher.find()) types.add(matcher.group().toLowerCase());
        return types;
    }

    private BlockBean placeholder(int id, String code) {
        BlockBean block = newBlock(id, addSourceDirectlyDef());
        block.parameters.add(code);
        return block;
    }

    private BlockDef addSourceDirectlyDef() {
        BlockDef def = defsByOpCode.get("addSourceDirectly");
        return def != null ? def : new BlockDef("addSourceDirectly", " ", "", "add source directly %s.inputOnly");
    }

    // endregion

    private String generate(ArrayList<BlockBean> blocks) {
        return new Fx(activityName, buildConfig, blocks, viewBinding).a();
    }

    private BlockBean newBlock(int id, BlockDef def) {
        BlockBean block = new BlockBean(String.valueOf(id), def.spec, def.type, def.typeName, def.opCode);
        if (colors != null) {
            try {
                block.color = colors.color(def.opCode, def.type);
            } catch (RuntimeException ignored) {
            }
        }
        return block;
    }

    private record Range(int start, int end) {
    }

    private record Choice(Template template, Range[] captures) {
    }

    /**
     * State of converting one event.
     */
    private final class Conversion {
        final String source;
        final ArrayList<Token> tokens;
        final ArrayList<BlockBean> blocks = new ArrayList<>();
        final Set<Integer> usedIds = new HashSet<>();
        final Set<Integer> reservedIds = new HashSet<>();
        final Map<String, Reporter> localReporters = new HashMap<>(reporters);
        /** Best template per piece of code, so nested code isn't searched again for every candidate around it. */
        final Map<String, Choice> choices = new HashMap<>();
        int nextId = FIRST_BLOCK_ID;
        int attempts;

        Conversion(String source, List<Reporter> extraReporters) {
            this(source, extraReporters, true);
        }

        Conversion(String source, List<Reporter> extraReporters, boolean loadTemplates) {
            this.source = source;
            tokens = tokenize(source);
            // "repeat" blocks name their loop variable after their ID, e.g. _repeat20 needs ID 20.
            for (Token token : tokens) {
                Matcher matcher = REPEAT_VARIABLE.matcher(token.text);
                if (matcher.matches()) {
                    try {
                        reservedIds.add(Integer.parseInt(matcher.group(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            for (Reporter reporter : extraReporters) localReporters.put(reporter.code, reporter);
            if (loadTemplates) ensureTemplates();
        }

        int allocateId() {
            while (usedIds.contains(nextId) || reservedIds.contains(nextId)) nextId++;
            usedIds.add(nextId);
            return nextId++;
        }

        int mark() {
            return blocks.size();
        }

        void rollback(int mark) {
            while (blocks.size() > mark) {
                usedIds.remove(Integer.valueOf(blocks.remove(blocks.size() - 1).id));
            }
        }

        String text(int start, int end) {
            return source.substring(tokens.get(start).start, tokens.get(end - 1).end);
        }

        /**
         * Converts the statements in {@code [from, to)} and chains them.
         *
         * @return ID of the first block, or -1 if there is no code.
         */
        int convertStatements(int from, int to) {
            int first = -1;
            BlockBean previous = null;
            int rawStart = -1;
            int rawEnd = -1;
            List<Range> segments = splitStatements(from, to);
            for (int s = 0; s < segments.size(); s++) {
                Range segment = segments.get(s);
                BlockBean block = convertStatement(segment);
                if (block != null && "f".equals(block.type) && s < segments.size() - 1) {
                    // Blocks like "break" or "finish" end a chain in the Logic editor.
                    rollbackBlock(block);
                    block = null;
                }
                if (block == null) {
                    if (rawStart < 0) rawStart = segment.start;
                    rawEnd = segment.end;
                    continue;
                }
                if (rawStart >= 0) {
                    BlockBean raw = rawStatement(rawStart, rawEnd);
                    if (previous == null) first = Integer.parseInt(raw.id);
                    else previous.nextBlock = Integer.parseInt(raw.id);
                    previous = raw;
                    rawStart = -1;
                }
                if (previous == null) first = Integer.parseInt(block.id);
                else previous.nextBlock = Integer.parseInt(block.id);
                previous = block;
            }
            if (rawStart >= 0) {
                BlockBean raw = rawStatement(rawStart, rawEnd);
                if (previous == null) first = Integer.parseInt(raw.id);
                else previous.nextBlock = Integer.parseInt(raw.id);
            }
            return first;
        }

        private void rollbackBlock(BlockBean block) {
            int index = blocks.indexOf(block);
            if (index >= 0) rollback(index);
        }

        private BlockBean rawStatement(int start, int end) {
            BlockBean block = newBlock(allocateId(), addSourceDirectlyDef());
            block.parameters.add(text(start, end));
            blocks.add(block);
            return block;
        }

        /**
         * Splits {@code [from, to)} into statements; comments become segments of their own.
         */
        List<Range> splitStatements(int from, int to) {
            ArrayList<Range> segments = new ArrayList<>();
            int i = from;
            while (i < to) {
                if (tokens.get(i).comment) {
                    segments.add(new Range(i, i + 1));
                    i++;
                    continue;
                }
                int start = i;
                String first = tokens.get(i).text;
                boolean blockStatement = BLOCK_STATEMENT_KEYWORDS.contains(first);
                int depth = 0;
                for (; i < to; i++) {
                    Token token = tokens.get(i);
                    if (token.comment) continue;
                    String text = token.text;
                    if (text.equals("(") || text.equals("[") || text.equals("{")) {
                        depth++;
                    } else if (text.equals(")") || text.equals("]") || text.equals("}")) {
                        depth--;
                    }
                    if (depth == 0 && text.equals(";")) {
                        i++;
                        break;
                    }
                    if (depth == 0 && text.equals("}") && blockStatement) {
                        String next = nextCode(i + 1, to);
                        if ("else".equals(next) || "catch".equals(next) || "finally".equals(next)
                                || ("do".equals(first) && "while".equals(next))) {
                            continue;
                        }
                        i++;
                        break;
                    }
                }
                segments.add(new Range(start, i));
            }
            return segments;
        }

        @Nullable
        private String nextCode(int from, int to) {
            for (int i = from; i < to; i++) {
                if (!tokens.get(i).comment) return tokens.get(i).text;
            }
            return null;
        }

        @Nullable
        BlockBean convertStatement(Range range) {
            for (int i = range.start; i < range.end; i++) {
                if (tokens.get(i).comment) return null;
            }
            return best(statementTemplates, range);
        }

        /**
         * Tries every template matching {@code range} and builds the best conversion, see {@link #score}.
         *
         * @return The block, with the blocks it needs added; {@code null} if nothing matches.
         */
        @Nullable
        private BlockBean best(List<Template> templates, Range range) {
            String key = System.identityHashCode(templates) + ":" + range.start + ":" + range.end;
            if (choices.containsKey(key)) {
                Choice choice = choices.get(key);
                if (choice == null) return null;
                int mark = mark();
                BlockBean block = instantiate(choice.template, choice.captures);
                if (block == null) rollback(mark);
                return block;
            }
            Set<String> present = textsOf(range);
            Template bestTemplate = null;
            Range[] bestCaptures = null;
            long bestScore = Long.MAX_VALUE;
            for (Template template : templates) {
                if (!present.containsAll(template.literals)) continue;
                for (Range[] captures : match(template, range)) {
                    int mark = mark();
                    BlockBean block = instantiate(template, captures);
                    if (block != null && verify(block, mark, range)) {
                        long score = score(template, block, mark);
                        if (score < bestScore) {
                            bestScore = score;
                            bestTemplate = template;
                            bestCaptures = captures;
                        }
                    }
                    rollback(mark);
                }
            }
            choices.put(key, bestTemplate == null ? null : new Choice(bestTemplate, bestCaptures));
            if (bestTemplate == null) return null;
            int mark = mark();
            BlockBean block = instantiate(bestTemplate, bestCaptures);
            if (block == null) {
                rollback(mark);
                return null;
            }
            return block;
        }

        /**
         * Lower is better: fewer "add source directly" fallbacks, original palette blocks before Custom Blocks,
         * fewer parameters, then more specific templates.
         */
        private long score(Template template, BlockBean block, int mark) {
            long fallbacks = 0;
            for (int i = mark; i < blocks.size(); i++) {
                String opCode = blocks.get(i).opCode;
                if (opCode.equals("addSourceDirectly") || opCode.startsWith("asd")) fallbacks++;
            }
            return fallbacks * 1_000_000L + (template.def.core ? 0 : 10_000L)
                    + template.kinds.length * 100L + (100 - Math.min(99, template.literals.size()));
        }

        /**
         * @return Type of the project variable or list named {@code name}, lists as {@code l:<type name>}.
         */
        @Nullable
        private String reporterType(String name) {
            Reporter reporter = localReporters.get(name);
            if (reporter == null || !reporter.def.opCode.equals("getVar")) return null;
            return reporter.def.type.equals("l") ? "l:" + reporter.def.typeName : reporter.def.type;
        }

        @Nullable
        private String variableTypeOf(String paramType) {
            return switch (paramType) {
                case "%m.varbool" -> "b";
                case "%m.varint" -> "d";
                case "%m.varstr" -> "s";
                case "%m.varmap" -> "a";
                case "%m.liststr" -> "l:List String";
                case "%m.listint" -> "l:List Number";
                case "%m.listmap" -> "l:List Map";
                default -> null;
            };
        }

        /**
         * @return A parameter value for a slot of {@code kind}: a literal, raw code, or {@code @id} of a
         * reporter block. {@code null} if the code can't be represented.
         */
        @Nullable
        String convertValue(Range range, int kind) {
            String raw = text(range.start, range.end);
            switch (kind) {
                case KIND_RAW:
                    return raw;
                case KIND_STRING:
                    if (range.end - range.start == 1) {
                        String literal = tokens.get(range.start).text;
                        if (literal.startsWith("\"") && !literal.startsWith("\"\"\"")) {
                            return unescapeStringLiteral(literal);
                        }
                    }
                    break;
                case KIND_NUMBER:
                    if (PLAIN_NUMBER.matcher(raw).matches()) return raw;
                    break;
                default:
                    break;
            }
            String type = switch (kind) {
                case KIND_BOOLEAN -> "b";
                case KIND_NUMBER -> "d";
                default -> "s";
            };
            String reporter = convertReporter(range, type);
            if (reporter != null) return reporter;

            String asdOpCode = switch (kind) {
                case KIND_BOOLEAN -> "asdBoolean";
                case KIND_NUMBER -> "asdNumber";
                default -> "asdString";
            };
            BlockDef asd = defsByOpCode.get(asdOpCode);
            if (asd == null) return null;
            BlockBean block = newBlock(allocateId(), asd);
            block.parameters.add(raw);
            blocks.add(block);
            return "@" + block.id;
        }

        @Nullable
        private String convertReporter(Range range, String type) {
            if (range.end - range.start == 1) {
                Reporter reporter = localReporters.get(tokens.get(range.start).text);
                if (reporter != null && reporter.def.type.equals(type)) {
                    BlockBean block = newBlock(allocateId(), reporter.def);
                    blocks.add(block);
                    return "@" + block.id;
                }
            }
            List<Template> templates = reporterTemplates.get(type);
            if (templates == null) return null;
            BlockBean block = best(templates, range);
            if (block == null && isParenthesized(range)) {
                // Operators nested in operators are generated in parentheses, e.g. "a && (b < c)".
                return convertReporter(new Range(range.start + 1, range.end - 1), type);
            }
            return block == null ? null : "@" + block.id;
        }

        private boolean isParenthesized(Range range) {
            if (range.end - range.start < 3 || !tokens.get(range.start).text.equals("(")
                    || !tokens.get(range.end - 1).text.equals(")")) {
                return false;
            }
            int depth = 0;
            for (int i = range.start; i < range.end - 1; i++) {
                String text = tokens.get(i).text;
                if (text.equals("(")) depth++;
                else if (text.equals(")")) depth--;
                if (depth == 0) return false;
            }
            return true;
        }

        @Nullable
        private BlockBean instantiate(Template template, Range[] captures) {
            if (++attempts > MAX_ATTEMPTS) return null;
            int paramCount = template.kinds.length;
            int id;
            if (template.usesRepeatVariable) {
                Range variable = captures[paramCount + 2];
                if (variable == null) return null;
                try {
                    id = Integer.parseInt(tokens.get(variable.start).text.substring("_repeat".length()));
                } catch (NumberFormatException e) {
                    return null;
                }
                if (id < 0 || usedIds.contains(id)) return null;
                usedIds.add(id);
            } else {
                id = allocateId();
            }
            BlockBean block = newBlock(id, template.def);
            blocks.add(block);

            for (int i = 0; i < paramCount; i++) {
                Range capture = captures[i];
                String value = capture == null ? "" : convertValue(capture, template.kinds[i]);
                if (value == null) return null;
                String expected = i < template.paramTypes.size() ? variableTypeOf(template.paramTypes.get(i)) : null;
                if (expected != null && !expected.equals(reporterType(value))) {
                    // Blocks setting a variable need one of the project's variables of that type.
                    return null;
                }
                block.parameters.add(value);
            }
            Range stack1 = captures[paramCount];
            if (stack1 != null) block.subStack1 = convertStatements(stack1.start, stack1.end);
            Range stack2 = captures[paramCount + 1];
            if (stack2 != null) block.subStack2 = convertStatements(stack2.start, stack2.end);
            return block;
        }

        /**
         * Generates {@code block} with the blocks created for it and compares the code with {@code range}.
         */
        private boolean verify(BlockBean block, int mark, Range range) {
            ArrayList<BlockBean> generated = new ArrayList<>();
            BlockBean head = block.clone();
            head.nextBlock = -1;
            generated.add(head);
            for (int i = mark; i < blocks.size(); i++) {
                if (blocks.get(i) != block) generated.add(blocks.get(i));
            }
            String code;
            try {
                code = generate(generated);
            } catch (RuntimeException | LinkageError e) {
                // e.g. a Custom Block whose code can't be generated
                return false;
            }
            return sameTokens(tokenize(code), tokens, range.start, range.end);
        }

        private Set<String> textsOf(Range range) {
            HashSet<String> texts = new HashSet<>();
            for (int i = range.start; i < range.end; i++) texts.add(tokens.get(i).text);
            return texts;
        }

        /**
         * Finds ways {@code template} matches all of {@code range}.
         */
        private List<Range[]> match(Template template, Range range) {
            ArrayList<Range[]> matches = new ArrayList<>();
            match(template, 0, range.start, range.end, new Range[template.captureCount()], matches);
            return matches;
        }

        private void match(Template template, int part, int token, int end, Range[] captures, List<Range[]> out) {
            if (out.size() >= MAX_MATCHES) return;
            if (part == template.parts.length) {
                if (token == end) out.add(captures.clone());
                return;
            }
            Object current = template.parts[part];
            if (current instanceof String literal) {
                if (token < end && tokens.get(token).text.equals(literal)) {
                    match(template, part + 1, token + 1, end, captures, out);
                }
                return;
            }

            Slot slot = (Slot) current;
            int index = Template.captureIndex(slot, template.kinds.length);
            Range previous = captures[index];
            if (previous != null) {
                // A slot used twice must contain the same code both times.
                int length = previous.end - previous.start;
                if (token + length <= end && sameTexts(previous.start, token, length)) {
                    match(template, part + 1, token + length, end, captures, out);
                }
                return;
            }
            if (slot.index == SLOT_REPEAT_VARIABLE) {
                if (token < end && tokens.get(token).text.startsWith("_repeat")) {
                    captures[index] = new Range(token, token + 1);
                    match(template, part + 1, token + 1, end, captures, out);
                    captures[index] = null;
                }
                return;
            }

            boolean stack = slot.index == SLOT_STACK1 || slot.index == SLOT_STACK2;
            String nextLiteral = part + 1 < template.parts.length && template.parts[part + 1] instanceof String s ? s : null;
            int depth = 0;
            for (int e = token; e <= end; e++) {
                if (e > token) {
                    String text = tokens.get(e - 1).text;
                    if (text.equals("(") || text.equals("[") || text.equals("{")) {
                        depth++;
                    } else if (text.equals(")") || text.equals("]") || text.equals("}")) {
                        depth--;
                    }
                    if (depth < 0) break;
                    if (!stack && depth == 0 && text.equals(";")) break;
                }
                if (depth != 0 || (e == token && !stack)) continue;
                if (nextLiteral != null) {
                    if (e >= end || !tokens.get(e).text.equals(nextLiteral)) continue;
                } else if (part + 1 == template.parts.length && e != end) {
                    continue;
                }
                captures[index] = new Range(token, e);
                match(template, part + 1, e, end, captures, out);
                captures[index] = null;
                if (out.size() >= MAX_MATCHES) return;
            }
        }

        private boolean sameTexts(int a, int b, int length) {
            for (int i = 0; i < length; i++) {
                if (!tokens.get(a + i).text.equals(tokens.get(b + i).text)) return false;
            }
            return true;
        }
    }

    /**
     * Rewrites {@code else if (...) {...}} as {@code else { if (...) {...} }}, which is how if-else blocks
     * generate it, so else-if chains become nested if-else blocks. The code does the same either way.
     */
    String braceElseIf(String code) {
        Conversion scanner = new Conversion(code, Collections.emptyList(), false);
        ArrayList<Token> tokens = scanner.tokens;
        ArrayList<int[]> insertions = new ArrayList<>(); // {position, 0 = open / 1 = close}
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).text.equals("else")) continue;
            int next = i + 1;
            while (next < tokens.size() && tokens.get(next).comment) next++;
            if (next >= tokens.size() || !tokens.get(next).text.equals("if")) continue;
            List<Range> statements = scanner.splitStatements(next, tokens.size());
            if (statements.isEmpty()) continue;
            insertions.add(new int[]{tokens.get(next).start, 0});
            insertions.add(new int[]{tokens.get(statements.get(0).end - 1).end, 1});
        }
        if (insertions.isEmpty()) return code;
        insertions.sort((a, b) -> Integer.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder(code);
        for (int[] insertion : insertions) {
            sb.insert(insertion[0], insertion[1] == 0 ? "{\n" : "\n}");
        }
        return sb.toString();
    }

    /**
     * Reverses {@code Fx#escapeString}: {@code \"} and {@code \\} are unescaped, {@code \n} and {@code \t}
     * stay as written since the generator keeps them.
     */
    static String unescapeStringLiteral(String literal) {
        String content = literal.substring(1, literal.length() - 1);
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // region Tokens

    static final class Token {
        final String text;
        final int start;
        final int end;
        final boolean comment;

        Token(String text, int start, int end, boolean comment) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.comment = comment;
        }

        @NonNull
        @Override
        public String toString() {
            return text;
        }
    }

    static ArrayList<Token> tokenize(String code) {
        ArrayList<Token> tokens = new ArrayList<>();
        int length = code.length();
        int i = 0;
        while (i < length) {
            char c = code.charAt(i);
            char next = i + 1 < length ? code.charAt(i + 1) : 0;
            int start = i;
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && next == '/') {
                while (i < length && code.charAt(i) != '\n' && code.charAt(i) != '\r') i++;
                tokens.add(new Token(code.substring(start, i), start, i, true));
                continue;
            }
            if (c == '/' && next == '*') {
                int close = code.indexOf("*/", i + 2);
                i = close < 0 ? length : close + 2;
                tokens.add(new Token(code.substring(start, i), start, i, true));
                continue;
            }
            if (c == '"' || c == '\'') {
                if (c == '"' && code.startsWith("\"\"\"", i)) {
                    int close = code.indexOf("\"\"\"", i + 3);
                    i = close < 0 ? length : close + 3;
                } else {
                    i++;
                    while (i < length && code.charAt(i) != c && code.charAt(i) != '\n') {
                        if (code.charAt(i) == '\\') i++;
                        i++;
                    }
                    i = Math.min(i + 1, length);
                }
            } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(next))) {
                i++;
                while (i < length) {
                    char d = code.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '.') {
                        i++;
                    } else if ((d == '+' || d == '-') && (code.charAt(i - 1) == 'e' || code.charAt(i - 1) == 'E')
                            && !code.substring(start, i).startsWith("0x")) {
                        i++;
                    } else {
                        break;
                    }
                }
            } else if (Character.isJavaIdentifierStart(c)) {
                i++;
                while (i < length && Character.isJavaIdentifierPart(code.charAt(i))) i++;
            } else {
                String operator = null;
                for (String candidate : OPERATORS) {
                    if (code.startsWith(candidate, i)) {
                        operator = candidate;
                        break;
                    }
                }
                i += operator != null ? operator.length() : 1;
            }
            tokens.add(new Token(code.substring(start, i), start, i, false));
        }
        return tokens;
    }

    /**
     * Whether {@code generated} has the same code tokens as {@code tokens[from, to)}.
     */
    static boolean sameTokens(List<Token> generated, List<Token> tokens, int from, int to) {
        int g = 0;
        int t = from;
        while (true) {
            while (g < generated.size() && generated.get(g).comment) g++;
            while (t < to && tokens.get(t).comment) t++;
            if (g >= generated.size() || t >= to) return g >= generated.size() && t >= to;
            if (!generated.get(g).text.equals(tokens.get(t).text)) return false;
            g++;
            t++;
        }
    }

    // endregion

    /**
     * Builds {@link BlockDef}s of {@code definedFunc} blocks for an activity's More Blocks.
     *
     * @param moreBlockSpecs The More Blocks' specs, as stored in the project.
     */
    static List<BlockDef> moreBlockDefs(List<String> moreBlockSpecs) {
        ArrayList<BlockDef> defs = new ArrayList<>();
        for (String spec : moreBlockSpecs) {
            String type = mod.hey.studios.moreblock.ReturnMoreblockManager.getMoreblockChar(spec);
            String typeName = "";
            if (type.contains("|")) {
                String[] split = type.split("\\|");
                type = split[0];
                typeName = split.length > 1 ? split[1] : "";
            }
            defs.add(new BlockDef("definedFunc", type,
                    typeName, mod.hey.studios.moreblock.ReturnMoreblockManager.getMbName(spec)));
        }
        return defs;
    }

    /**
     * Builds {@link Reporter}s for a More Block's parameters, e.g. {@code %s.text} becomes {@code _text}.
     */
    static List<Reporter> moreBlockArguments(String moreBlockSpec) {
        ArrayList<Reporter> arguments = new ArrayList<>();
        Matcher matcher = Pattern.compile("%([bds])\\.(\\w+)").matcher(moreBlockSpec);
        while (matcher.find()) {
            String name = matcher.group(2);
            arguments.add(new Reporter("_" + name, new BlockDef("getArg", matcher.group(1), "", name)));
        }
        return arguments;
    }
}
