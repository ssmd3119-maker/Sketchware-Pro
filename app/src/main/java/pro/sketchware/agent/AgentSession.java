package pro.sketchware.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One conversation with the agent about one project: the message history sent to Claude, the items shown in
 * the chat, and the loop that runs Claude's tool calls until it's done.
 * <p>
 * Sessions outlive their screen (e.g. on rotation) and are kept per project until the app process ends.
 */
public class AgentSession {

    public static class ChatItem {
        public enum Type {USER, ASSISTANT, THINKING, TOOL, ERROR, NOTICE}

        public enum Status {RUNNING, DONE, FAILED, DECLINED}

        public final Type type;
        public final StringBuilder text = new StringBuilder();
        /** For tools: the result, or what failed. */
        public volatile String detail;
        public volatile Status status = Status.DONE;
        public boolean expanded;

        ChatItem(Type type, String text) {
            this.type = type;
            this.text.append(text);
        }
    }

    /** A change waiting for the user to allow it. */
    public static class ApprovalRequest {
        public final String title;
        public final String preview;
        final CompletableFuture<Boolean> answer = new CompletableFuture<>();

        ApprovalRequest(String title, String preview) {
            this.title = title;
            this.preview = preview;
        }

        public void answer(boolean allowed) {
            answer.complete(allowed);
        }
    }

    public interface Listener {
        void onChanged();

        void onApprovalNeeded(ApprovalRequest request);
    }

    private static final Map<String, AgentSession> SESSIONS = new HashMap<>();
    /** Model turns per message before the agent stops and asks to continue. */
    private static final int MAX_TURNS = 80;
    private static final int MAX_RESULT_CHARS = 120_000;

    private final Context context;
    private final ProjectAccess project;
    private final AgentTools tools;
    private final AgentSettings settings;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable notifyListener = this::notifyNow;

    /** Exactly what is sent to Claude. Only ever appended to, except a trailing user message nobody answered. */
    private final List<MessageParam> history = new ArrayList<>();
    /**
     * Whether the history ends with a user message. Tracked here because {@link MessageParam#role()} can't read
     * the role of messages made by {@link Message#toParam()}.
     */
    private boolean historyEndsWithUser;
    public final List<ChatItem> items = new ArrayList<>();

    @Nullable
    private Listener listener;
    @Nullable
    private volatile ApprovalRequest pendingApproval;
    private volatile boolean running;
    private volatile boolean cancelled;
    @Nullable
    private volatile StreamResponse<RawMessageStreamEvent> stream;
    @Nullable
    private AnthropicClient client;
    @Nullable
    private String clientKey;
    private boolean notifyScheduled;

    public static synchronized AgentSession get(Context context, String scId) {
        AgentSession session = SESSIONS.get(scId);
        if (session == null) {
            session = new AgentSession(context, scId);
            SESSIONS.put(scId, session);
        }
        return session;
    }

    /** Starts a new conversation for the project. */
    public static synchronized AgentSession reset(Context context, String scId) {
        AgentSession old = SESSIONS.remove(scId);
        if (old != null) old.stop();
        return get(context, scId);
    }

    private AgentSession(Context context, String scId) {
        this.context = context.getApplicationContext();
        project = new ProjectAccess(this.context, scId);
        tools = new AgentTools(project);
        settings = new AgentSettings(this.context);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
        ApprovalRequest request = pendingApproval;
        if (listener != null && request != null && !request.answer.isDone()) listener.onApprovalNeeded(request);
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isProjectDataChanged() {
        return project.isProjectDataChanged();
    }

    public void send(String text) {
        if (running || text.trim().isEmpty()) return;
        running = true;
        cancelled = false;
        synchronized (items) {
            items.add(new ChatItem(ChatItem.Type.USER, text.trim()));
        }
        notifyNow();
        executor.execute(() -> {
            try {
                addUserText(text.trim());
                runLoop();
            } catch (RuntimeException | LinkageError e) {
                addItem(ChatItem.Type.ERROR, "Something went wrong: " + e);
            } finally {
                running = false;
                stream = null;
                pendingApproval = null;
                notifyNow();
            }
        });
    }

    /** Stops the running turn; a change being made finishes first. */
    public void stop() {
        cancelled = true;
        ApprovalRequest request = pendingApproval;
        if (request != null) request.answer(false);
        StreamResponse<RawMessageStreamEvent> current = stream;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
    }

    // region Loop

    private void runLoop() {
        String apiKey = settings.getApiKey();
        if (apiKey.isEmpty()) {
            addItem(ChatItem.Type.ERROR, "Add your Anthropic API key in the agent's settings first.");
            return;
        }
        AnthropicClient client = client(apiKey, settings.getBaseUrl());
        List<Tool> toolDefinitions = AgentTools.definitions();

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            if (cancelled) {
                addItem(ChatItem.Type.NOTICE, "Stopped.");
                return;
            }
            Message message;
            try {
                message = requestTurn(client, toolDefinitions);
            } catch (CancelledException e) {
                addItem(ChatItem.Type.NOTICE, "Stopped.");
                return;
            } catch (UnauthorizedException e) {
                addItem(ChatItem.Type.ERROR, "The API key was rejected. Check it in the agent's settings.");
                return;
            } catch (PermissionDeniedException e) {
                addItem(ChatItem.Type.ERROR, "This API key can't use " + AgentSettings.modelName(settings.getModel())
                        + ". Choose another model in settings.\n\n" + e.getMessage());
                return;
            } catch (RateLimitException e) {
                addItem(ChatItem.Type.ERROR, "Rate limit reached. Wait a moment and send your message again.");
                return;
            } catch (AnthropicServiceException e) {
                addItem(ChatItem.Type.ERROR, "Claude API error " + e.statusCode() + ": " + e.getMessage());
                return;
            } catch (AnthropicIoException e) {
                if (cancelled) {
                    addItem(ChatItem.Type.NOTICE, "Stopped.");
                } else {
                    addItem(ChatItem.Type.ERROR, "Network error: " + e.getMessage() + "\nCheck your connection and send again.");
                }
                return;
            } catch (RuntimeException e) {
                if (cancelled) {
                    addItem(ChatItem.Type.NOTICE, "Stopped.");
                } else {
                    addItem(ChatItem.Type.ERROR, "Something went wrong: " + e);
                }
                return;
            }

            StopReason stopReason = message.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(stopReason)) {
                // A refusal can cut a tool call off mid-input; nothing of this turn runs or stays in the history.
                addItem(ChatItem.Type.ERROR, "Claude declined to continue with this request."
                        + message.stopDetails().map(details -> details.explanation().map(text -> "\n\n" + text).orElse("")).orElse(""));
                return;
            }
            if (message.content().isEmpty()) return;
            history.add(message.toParam());
            historyEndsWithUser = false;

            List<ToolUseBlock> toolUses = new ArrayList<>();
            for (ContentBlock block : message.content()) block.toolUse().ifPresent(toolUses::add);
            if (toolUses.isEmpty()) {
                if (StopReason.MAX_TOKENS.equals(stopReason)) {
                    addItem(ChatItem.Type.NOTICE, "The reply reached its length limit. Send \"continue\" to go on.");
                }
                return;
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock toolUse : toolUses) {
                results.add(ContentBlockParam.ofToolResult(runTool(toolUse, StopReason.MAX_TOKENS.equals(stopReason))));
            }
            history.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
            historyEndsWithUser = true;
        }
        addItem(ChatItem.Type.NOTICE, "The agent took many steps. Send \"continue\" to let it go on.");
    }

    private static class CancelledException extends RuntimeException {
    }

    private Message requestTurn(AnthropicClient client, List<Tool> toolDefinitions) {
        String model = settings.getModel();
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(64000L)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(AgentPrompt.SYSTEM).build()))
                .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                .outputConfig(OutputConfig.builder().effort(effort(settings.getEffort())).build())
                // Caches everything up to the newest message, so each step only pays for what's new.
                .cacheControl(CacheControlEphemeral.builder().build())
                .messages(history);
        for (Tool tool : toolDefinitions) params.addTool(tool);
        if (supportsServerFallback(model)) {
            // Retries a declined request on the model Anthropic recommends for that kind of refusal.
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        MessageAccumulator accumulator = MessageAccumulator.create();
        ChatItem[] current = new ChatItem[1];
        try (StreamResponse<RawMessageStreamEvent> response = client.messages().createStreaming(params.build())) {
            stream = response;
            if (cancelled) throw new CancelledException();
            response.stream().forEach(event -> {
                if (cancelled) throw new CancelledException();
                accumulator.accumulate(event);
                event.contentBlockStart().ifPresent(start -> current[0] = null);
                event.contentBlockDelta().ifPresent(delta -> {
                    delta.delta().text().ifPresent(text -> current[0] = append(current[0], ChatItem.Type.ASSISTANT, text.text()));
                    delta.delta().thinking().ifPresent(thinking -> current[0] = append(current[0], ChatItem.Type.THINKING, thinking.thinking()));
                });
            });
        } finally {
            stream = null;
        }
        if (cancelled) throw new CancelledException();
        return accumulator.message();
    }

    private ToolResultBlockParam runTool(ToolUseBlock toolUse, boolean truncated) {
        Map<String, Object> input;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = toolUse._input().convert(Map.class);
            input = parsed;
        } catch (RuntimeException e) {
            input = null;
        }
        Map<String, Object> safeInput = input == null ? AgentTools.emptyInput() : input;
        ChatItem item = addItem(ChatItem.Type.TOOL, AgentTools.label(toolUse.name(), safeInput));
        item.status = ChatItem.Status.RUNNING;
        notifyNow();

        String error = truncated
                ? "Your reply hit the output limit, so this call's input was cut off. Make the change in smaller steps."
                : AgentTools.validate(toolUse.name(), input);
        if (error != null) return finish(item, toolUse, ChatItem.Status.FAILED, "INVALID_INPUT: " + error, true);
        if (cancelled) return finish(item, toolUse, ChatItem.Status.DECLINED, "The user stopped the agent before this ran.", true);

        if (AgentTools.changesProject(toolUse.name()) && !settings.isAutoApprove()) {
            ApprovalRequest request = new ApprovalRequest(item.text.toString(), AgentTools.preview(toolUse.name(), safeInput));
            pendingApproval = request;
            mainHandler.post(() -> {
                Listener current = listener;
                if (current != null) current.onApprovalNeeded(request);
            });
            boolean allowed;
            try {
                allowed = request.answer.get();
            } catch (Exception e) {
                allowed = false;
            } finally {
                pendingApproval = null;
            }
            if (!allowed) {
                return finish(item, toolUse, ChatItem.Status.DECLINED, cancelled
                        ? "The user stopped the agent before this ran."
                        : "The user declined this change. Ask what they want instead, or try a different approach.", true);
            }
        }

        try {
            String result = tools.run(toolUse.name(), safeInput);
            if (result.length() > MAX_RESULT_CHARS) {
                result = result.substring(0, MAX_RESULT_CHARS) + "\n[Cut off after " + MAX_RESULT_CHARS + " characters]";
            }
            return finish(item, toolUse, ChatItem.Status.DONE, result, false);
        } catch (ProjectAccess.AccessException e) {
            return finish(item, toolUse, ChatItem.Status.FAILED, e.getMessage(), true);
        } catch (Exception | LinkageError e) {
            return finish(item, toolUse, ChatItem.Status.FAILED, "Failed: " + e, true);
        }
    }

    private ToolResultBlockParam finish(ChatItem item, ToolUseBlock toolUse, ChatItem.Status status, String result, boolean isError) {
        item.status = status;
        item.detail = result;
        notifyNow();
        return ToolResultBlockParam.builder()
                .toolUseId(toolUse.id())
                .content(result)
                .isError(isError)
                .build();
    }

    // endregion

    // region History and items

    /**
     * Adds the user's text. If the last message is the user's (the previous turn failed or was stopped before
     * Claude answered), the text joins it, as consecutive user messages aren't a valid history.
     */
    private void addUserText(String text) {
        ContentBlockParam block = ContentBlockParam.ofText(TextBlockParam.builder().text(text).build());
        if (historyEndsWithUser && !history.isEmpty()) {
            MessageParam last = history.remove(history.size() - 1);
            List<ContentBlockParam> blocks = new ArrayList<>();
            MessageParam.Content content = last.content();
            if (content.isString()) {
                blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(content.asString()).build()));
            } else {
                blocks.addAll(content.asBlockParams());
            }
            blocks.add(block);
            history.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build());
        } else {
            history.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(List.of(block)).build());
        }
        historyEndsWithUser = true;
    }

    private ChatItem addItem(ChatItem.Type type, String text) {
        ChatItem item = new ChatItem(type, text);
        synchronized (items) {
            items.add(item);
        }
        notifyNow();
        return item;
    }

    private ChatItem append(@Nullable ChatItem current, ChatItem.Type type, String text) {
        if (text.isEmpty()) return current;
        ChatItem item = current;
        if (item == null || item.type != type) {
            item = new ChatItem(type, "");
            synchronized (items) {
                items.add(item);
            }
        }
        synchronized (items) {
            item.text.append(text);
        }
        scheduleNotify();
        return item;
    }

    private void scheduleNotify() {
        mainHandler.post(() -> {
            if (notifyScheduled) return;
            notifyScheduled = true;
            mainHandler.postDelayed(notifyListener, 60);
        });
    }

    private void notifyNow() {
        mainHandler.post(() -> {
            notifyScheduled = false;
            mainHandler.removeCallbacks(notifyListener);
            Listener current = listener;
            if (current != null) current.onChanged();
        });
    }

    // endregion

    private synchronized AnthropicClient client(String apiKey, String baseUrl) {
        String key = apiKey + "\n" + baseUrl;
        if (client == null || !key.equals(clientKey)) {
            AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
            if (!baseUrl.isEmpty()) builder.baseUrl(baseUrl);
            client = builder.build();
            clientKey = key;
        }
        return client;
    }

    private static OutputConfig.Effort effort(String effort) {
        return switch (effort) {
            case "low" -> OutputConfig.Effort.LOW;
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.HIGH;
        };
    }

    private static boolean supportsServerFallback(String model) {
        return model.equals("claude-fable-5-1") || model.equals("claude-opus-5-5") || model.equals("claude-sonnet-5-5");
    }
}
