package run.ihalo.ihalo;

/**
 * Request body of the plugin AI endpoint.
 *
 * @param mode one of {@code write|continue|polish|rewrite|format}
 * @param input the source text or topic
 * @param extra additional user instruction
 * @author galaxy-eye
 */
public record AiRequest(String mode, String input, String extra) {}
