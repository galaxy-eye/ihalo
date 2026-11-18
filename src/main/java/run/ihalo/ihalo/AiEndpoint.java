package run.ihalo.ihalo;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * Exposes the AI completion endpoints to the Console/UC UI.
 *
 * <p>Paths become {@code /apis/console.api.ihalo.run/v1alpha1/ai...}. Only authenticated users configured with the
 * plugin role may call them.
 *
 * @author galaxy-eye
 */
@Component
public class AiEndpoint implements CustomEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AiEndpoint.class);

    private final AiService aiService;

    public AiEndpoint(AiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
                .POST("/ai", this::complete)
                .GET("/ai/config", this::config)
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ihalo.run", "v1alpha1");
    }

    private Mono<ServerResponse> complete(ServerRequest request) {
        return request.bodyToMono(AiRequest.class)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("请求体不能为空")))
                .flatMap(aiService::complete)
                .flatMap(content ->
                        ServerResponse.ok().contentType(APPLICATION_JSON).bodyValue(Map.of("content", content)))
                .onErrorResume(this::handleError);
    }

    private Mono<ServerResponse> config(ServerRequest request) {
        return aiService
                .config()
                .flatMap(data ->
                        ServerResponse.ok().contentType(APPLICATION_JSON).bodyValue(data))
                .onErrorResume(this::handleError);
    }

    private Mono<ServerResponse> handleError(Throwable error) {
        if (error instanceof IllegalArgumentException e) {
            log.info("AI request rejected: {}", e.getMessage());
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (error instanceof AiException e) {
            log.warn("AI provider error: {}", e.getMessage());
            return errorResponse(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        log.error("Unexpected AI endpoint error", error);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "AI 服务内部错误");
    }

    private static Mono<ServerResponse> errorResponse(HttpStatus status, String message) {
        return ServerResponse.status(status)
                .contentType(APPLICATION_JSON)
                .bodyValue(Map.of("message", message == null ? "请求失败" : message));
    }
}
