package run.ihalo.ihalo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

/**
 * Delegates text generation to the Halo <b>AI Foundation</b> plugin through the {@code run.halo.aifoundation:api} SDK.
 * ihALO only orchestrates the per-mode prompts and applies the result to the editor; AI Foundation is responsible for
 * provider routing, credentials, retries and (SSRF-safe) outbound HTTP, so ihALO no longer calls upstream directly.
 *
 * @author galaxy-eye
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final ReactiveSettingFetcher settingFetcher;
    private final ExtensionGetter extensionGetter;

    public AiService(ReactiveSettingFetcher settingFetcher, ExtensionGetter extensionGetter) {
        this.settingFetcher = settingFetcher;
        this.extensionGetter = extensionGetter;
    }

    /**
     * Run an AI generation for the requested mode.
     *
     * @return the generated text
     */
    public Mono<String> complete(AiRequest request) {
        return Mono.just(request)
                .flatMap(this::validateRequest)
                .flatMap(ignored -> resolveSetting())
                .flatMap(setting -> {
                    GenerateTextRequest genRequest = buildRequest(request, setting);
                    return generate(genRequest, setting);
                });
    }

    /** Return a safe, non-secret projection of the current AI config for the UI. */
    public Mono<Map<String, Object>> config() {
        return settingFetcher
                .fetch(AiSetting.GROUP, AiSetting.class)
                .defaultIfEmpty(AiSetting.empty())
                .map(s -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("configured", StringUtils.isNotBlank(s.modelName()));
                    result.put("model", s.modelName() == null ? "" : s.modelName());
                    result.put("modes", List.of("write", "continue", "polish", "rewrite", "format"));
                    return result;
                });
    }

    private Mono<AiSetting> resolveSetting() {
        return settingFetcher
                .fetch(AiSetting.GROUP, AiSetting.class)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("请先在插件设置中完成 AI 配置")))
                .flatMap(s -> {
                    if (StringUtils.isBlank(s.modelName())) {
                        return Mono.error(new IllegalArgumentException("请在插件设置中配置 AI Foundation 模型（aiModelSelector）"));
                    }
                    return Mono.just(s);
                });
    }

    private Mono<String> generate(GenerateTextRequest request, AiSetting setting) {
        return extensionGetter
                .getEnabledExtension(AiModelService.class)
                .switchIfEmpty(Mono.error(new AiException("AI Foundation 插件未安装或未启用，请先安装并启用 plugin-ai-foundation")))
                .flatMap(service -> service.languageModel(setting.modelName()))
                .switchIfEmpty(Mono.error(new AiException("找不到已配置的语言模型: " + setting.modelName())))
                .flatMap(model -> model.generateText(request))
                .map(result -> {
                    String text = result == null ? null : result.getText();
                    if (StringUtils.isBlank(text)) {
                        throw new AiException("AI 服务返回了空内容");
                    }
                    return text;
                })
                .onErrorMap(error -> {
                    if (error instanceof IllegalArgumentException || error instanceof AiException) {
                        return error;
                    }
                    log.warn("AI Foundation generation failed", error);
                    return new AiException("AI 生成失败，请检查 AI Foundation 的模型配置", error);
                });
    }

    private static GenerateTextRequest buildRequest(AiRequest request, AiSetting setting) {
        var builder = GenerateTextRequest.builder()
                .system(buildSystemPrompt(request.mode(), setting))
                .prompt(buildUserPrompt(request.mode(), request.input(), request.extra()));
        if (setting.temperature() != null) {
            builder.temperature(setting.temperature());
        }
        if (setting.maxTokens() != null && setting.maxTokens() > 0) {
            builder.maxOutputTokens(setting.maxTokens());
        }
        return builder.build();
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

    private static String buildSystemPrompt(String mode, AiSetting setting) {
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
}
