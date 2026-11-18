package run.ihalo.ihalo;

/**
 * Plugin setting values used to drive AI Foundation, stored under group {@value #GROUP}.
 *
 * <p>{@code modelName} is the {@code AiModel.metadata.name} selected via the {@code aiModelSelector} FormKit input
 * provided by the AI Foundation plugin.
 *
 * @author galaxy-eye
 */
public record AiSetting(String modelName, Double temperature, Integer maxTokens, String systemPrompt) {
    public static final String GROUP = "ai";

    public static AiSetting empty() {
        return new AiSetting(null, null, null, null);
    }
}
