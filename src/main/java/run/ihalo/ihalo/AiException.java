package run.ihalo.ihalo;

/**
 * Thrown when the upstream AI provider returns an error or times out.
 *
 * @author galaxy-eye
 */
public class AiException extends RuntimeException {

    public AiException(String message) {
        super(message);
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
