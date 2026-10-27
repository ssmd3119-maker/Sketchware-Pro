package pro.sketchware.agent;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The agent's settings, stored in the app's private preferences.
 */
public class AgentSettings {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    /** Models offered in settings, most capable first. */
    public static final String[] MODELS = {"claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"};
    public static final String[] MODEL_NAMES = {"Claude Fable 5.1", "Claude Opus 5.5", "Claude Sonnet 5.5", "Claude Haiku 5.5"};
    public static final String[] EFFORTS = {"low", "medium", "high", "xhigh", "max"};

    private static final String PREFERENCES = "ai_agent";
    private final SharedPreferences preferences;

    public AgentSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public String getApiKey() {
        return preferences.getString("api_key", "").trim();
    }

    public boolean hasApiKey() {
        return !getApiKey().isEmpty();
    }

    public String getModel() {
        return preferences.getString("model", DEFAULT_MODEL);
    }

    public String getEffort() {
        return preferences.getString("effort", "high");
    }

    /** API address to use instead of Anthropic's, e.g. a proxy; empty for the default. */
    public String getBaseUrl() {
        return preferences.getString("base_url", "").trim();
    }

    /** Whether changes run without asking first. */
    public boolean isAutoApprove() {
        return preferences.getBoolean("auto_approve", false);
    }

    public void save(String apiKey, String baseUrl, String model, String effort, boolean autoApprove) {
        preferences.edit()
                .putString("api_key", apiKey.trim())
                .putString("base_url", baseUrl.trim())
                .putString("model", model)
                .putString("effort", effort)
                .putBoolean("auto_approve", autoApprove)
                .apply();
    }

    /**
     * @param nameOrId A model name from {@link #MODEL_NAMES}, or any model ID typed by the user.
     */
    public static String modelId(String nameOrId) {
        String text = nameOrId.trim();
        if (text.isEmpty()) return DEFAULT_MODEL;
        for (int i = 0; i < MODEL_NAMES.length; i++) {
            if (MODEL_NAMES[i].equals(text)) return MODELS[i];
        }
        return text;
    }

    /** Whether the model is one of the Claude models offered, rather than an ID the user typed. */
    public static boolean isKnownModel(String model) {
        for (String known : MODELS) {
            if (known.equals(model)) return true;
        }
        return false;
    }

    public static String modelName(String model) {
        for (int i = 0; i < MODELS.length; i++) {
            if (MODELS[i].equals(model)) return MODEL_NAMES[i];
        }
        return model;
    }
}
