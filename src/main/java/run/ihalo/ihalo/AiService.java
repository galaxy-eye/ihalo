package run.ihalo.ihalo;

import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * Calls the configured OpenAI-compatible chat completions endpoint and returns the generated text. The API key is
 * stored in a Halo {@link Secret} and only read when a request is made. All requests pass through {@link SsrfGuard}
 * before the token is attached and use explicit connect/response timeouts plus a response size limit.
 *
 * @author galaxy-eye
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024; // 8 MiB
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final ReactiveSettingFetcher settingFetcher;
    private final ReactiveExtensionClient extensionClient;
    private final WebClient webClient;

    public AiService(ReactiveSettingFetcher settingFetcher, ReactiveExtensionClient extensionClient) {
        this.settingFetcher = settingFetcher;
        this.extensionClient = extensionClient;
        var httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(Duration.ofSeconds(60));
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }

    /**
     * Run an AI completion for the requested mode.
     *
     * @return the generated text
     */
    public Mono<String> complete(AiRequest request) {
        return Mono.just(request)
                .flatMap(this::validateRequest)
                .flatMap(ignored -> resolveSetting())
                .flatMap(setting -> loadSettingGuard(setting).map(s -> setting))
                .flatMap(setting -> {
                    String system = buildSystemPrompt(request.mode(), setting);
                    String user = buildUserPrompt(request.mode(), request.input(), request.extra());
                    Map<String, Object> body = buildRequestBody(setting, system, user);
                    return callCompletions(setting, body);
                });
    }

    /** Return a safe, non-secret projection of the current AI config for the UI. */
    public Mono<Map<String, Object>> config() {
        return settingFetcher
                .fetch(AiSetting.GROUP, AiSetting.class)
                .defaultIfEmpty(AiSetting.empty())
                .map(s -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put(
                            "configured",
                            StringUtils.isNotBlank(s.model())
                                    && StringUtils.isNotBlank(s.baseUrl())
                                    && StringUtils.isNotBlank(s.apiKey()));
                    result.put("baseUrl", s.baseUrl() == null ? "" : s.baseUrl());
                    result.put("model", s.model() == null ? "" : s.model());
                    result.put("modes", List.of("write", "continue", "polish", "rewrite", "format"));
                    return result;
                });
    }

    private Mono<ResolvedSetting> resolveSetting() {
        return settingFetcher
                .fetch(AiSetting.GROUP, AiSetting.class)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("请先在插件设置中完成 AI 配置")))
                .flatMap(s -> {
                    if (StringUtils.isBlank(s.baseUrl())
                            || StringUtils.isBlank(s.model())
                            || StringUtils.isBlank(s.apiKey())) {
                        return Mono.error(new IllegalArgumentException("AI 配置不完整，请在插件设置中配置服务地址、API Key 和模型"));
                    }
                    return extensionClient
                            .fetch(Secret.class, s.apiKey())
                            .switchIfEmpty(Mono.error(new IllegalArgumentException("未找到 API Key 对应的 Secret")))
                            .map(secret -> {
                                String token = null;
                                if (secret.getStringData() != null) {
                                    token = secret.getStringData().get("token");
                                }
                                if (StringUtils.isBlank(token)) {
                                    throw new IllegalArgumentException("API Key 内容为空");
                                }
                                return new ResolvedSetting(
                                        s.baseUrl(),
                                        token,
                                        s.model(),
                                        s.temperature(),
                                        s.maxTokens(),
                                        s.systemPrompt(),
                                        s.timeoutSeconds());
                            });
                });
    }

    private Mono<ResolvedSetting> loadSettingGuard(ResolvedSetting setting) {
        URI uri = SsrfGuard.validate(setting.baseUrl());
        String host = uri.getHost();
        return Mono.<ResolvedSetting>fromCallable(() -> {
                    SsrfGuard.assertPublicHost(host);
                    return setting;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<AiRequest> validateRequest(AiRequest request) {
        if (request == null) {
            return Mono.error(new IllegalArgumentException("请求体不能为空"));
        }
        if (StringUtils.isBlank(request.mode())) {
            return Mono.error(new IllegalArgumentException("缺少操作类型 mode"));
        }
        return Mono.just(request.mode())
                .filter(mode -> List.of("write", "continue", "polish", "rewrite", "format")
                        .contains(mode))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("不支持的操作类型: " + request.mode())))
                .thenReturn(request);
    }

    private Mono<String> callCompletions(ResolvedSetting setting, Map<String, Object> body) {
        String base = setting.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String url = base + "/chat/completions";
        Duration timeout = Duration.ofSeconds(resolveTimeout(setting));
        return webClient
                .post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + setting.apiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .timeout(timeout)
                .map(response -> {
                    String content = extractContent(response);
                    if (StringUtils.isBlank(content)) {
                        throw new AiException("AI 服务返回了空内容");
                    }
                    return content;
                })
                .onErrorMap(
                        WebClientResponseException.class,
                        e -> new AiException("AI 服务调用失败(" + e.getStatusCode().value() + ")", e))
                .onErrorMap(java.util.concurrent.TimeoutException.class, e -> new AiException("AI 服务响应超时", e))
                .onErrorMap(java.net.ConnectException.class, e -> new AiException("无法连接 AI 服务", e));
    }

    private static String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        var message = response.choices().get(0).message();
        return message == null ? null : message.content();
    }

    private static Map<String, Object> buildRequestBody(ResolvedSetting setting, String system, String user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", setting.model());
        if (setting.temperature() != null) {
            body.put("temperature", setting.temperature());
        }
        if (setting.maxTokens() != null && setting.maxTokens() > 0) {
            body.put("max_tokens", setting.maxTokens());
        }
        body.put(
                "messages",
                List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        return body;
    }

    private static String buildSystemPrompt(String mode, ResolvedSetting setting) {
        String base =
                switch (mode) {
                    case "write" ->
                        "你是一名中文内容创作者。请根据用户给出的主题，输出一篇结构清晰、段落分明、可以直接发布的完整文章正文（使用 Markdown 排版，包含合适的标题层级，不要输出解释性前言）。";
                    case "continue" -> "你是一名中文内容创作者。请顺着用户提供的已有内容继续往下写，保持原有的语气与风格，自然衔接，输出后续内容（Markdown 排版）。";
                    case "polish" -> "你是一名专业的文字编辑。请在保持原意不变的前提下润色以下内容，使表达更流畅、准确和自然。只输出润色后的结果，不要解释。";
                    case "rewrite" -> "你是一名写作专家。请在不改变原意的前提下，用全新的表达方式改写以下内容，使行文更生动、有条理。只输出改写后的结果，不要解释。";
                    case "format" -> "你是一名排版助手。请优化以下内容的 Markdown 排版，合理使用标题、列表、加粗、引用、代码块等语法，让结构清晰易读。只输出排版后的结果，不要解释。";
                    default -> "你是一名中文内容创作者。请礼貌地完成任务并用 Markdown 输出结果。";
                };
        if (StringUtils.isNotBlank(setting.systemPrompt())) {
            base = base + "\n\n补充要求：" + setting.systemPrompt();
        }
        return base;
    }

    private static String buildUserPrompt(String mode, String input, String extra) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(input)) {
            sb.append(input.trim());
        }
        if (StringUtils.isNotBlank(extra)) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("[额外要求] ").append(extra.trim());
        }
        if (sb.length() == 0) {
            sb.append("（请提供内容或主题）");
        }
        return sb.toString();
    }

    private static int resolveTimeout(ResolvedSetting setting) {
        return (setting.timeoutSeconds() == null || setting.timeoutSeconds() <= 0) ? 60 : setting.timeoutSeconds();
    }

    /** Resolved config including the decrypted API token. Kept internal, never logged. */
    private record ResolvedSetting(
            String baseUrl,
            String apiKey,
            String model,
            Double temperature,
            Integer maxTokens,
            String systemPrompt,
            Integer timeoutSeconds) {}

    /** Response DTO for OpenAI-compatible chat completions. */
    public record ChatCompletionResponse(List<Choice> choices) {
        public record Choice(Message message) {}

        public record Message(String content) {}
    }
}
