package run.ihalo.ihalo;

/**
 * Plugin setting values for the AI provider, stored under group {@value #GROUP}.
 *
 * @author galaxy-eye
 */
public record AiSetting(
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Integer maxTokens,
        String systemPrompt,
        Integer timeoutSeconds) {
    public static final String GROUP = "ai";

    public static AiSetting empty() {
        return new AiSetting(null, null, null, null, null, null, null);
    }
}
