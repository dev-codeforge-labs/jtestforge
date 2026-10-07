package com.devmanchego.jtestforge.config;

import java.util.List;
import java.util.Map;

/**
 * One entry of {@code aiProvider.providers.*} — jtestforge-specification.md §5, §12.2.
 *
 * @param env variables merged on top of the inherited environment for this CLI only.
 *            Deliberately generic: JTestForge knows nothing about any particular CLI's
 *            variables, but a corporate environment routinely needs some set - a config
 *            directory the user can actually write to, a proxy, a debug switch. API keys
 *            belong in the real environment, never in this file.
 * @param type {@code process} (default) launches {@code command} as a child process; {@code http}
 *             calls a model server at {@code baseUrl}. Every setting below it that is specific to
 *             one of the two is ignored - or rejected - for the other, see {@link ConfigValidator}.
 * @param api for {@code type: http}, the protocol the server speaks; only {@code ollama} (its
 *            native {@code /api/chat}) for now, defaulted when {@code null}
 * @param baseUrl for {@code type: http}, e.g. {@code http://localhost:11434}
 * @param model for {@code type: http}, the model name to ask for, e.g. {@code qwen3-coder:30b}
 * @param options for {@code type: http}, sampling/context settings sent with every request
 *                exactly as written (Ollama: {@code num_ctx}, {@code temperature},
 *                {@code num_predict}, {@code repeat_penalty}...). Unlike a CLI, an HTTP request
 *                can set these per call, so they do not need a derived model
 * @param keepAlive for {@code type: http}, how long the server keeps the model loaded after a
 *                  request (Ollama: {@code 30m}); {@code null} leaves the server's default
 * @param headers for {@code type: http}, extra request headers (e.g. a proxy's authorization).
 *                Never written to a log; API keys still belong in the real environment
 * @param maxPromptChars overrides {@code context.maxPromptChars} for this provider only.
 *                       {@code null} means "use the global value" - most CLIs share one
 *                       reasonable context budget, but a locally hosted model can have a
 *                       far smaller (or larger) one than a hosted CLI, and that is a
 *                       property of the provider, not of the run.
 */
public record ProviderConfig(
        String command,
        List<String> args,
        PromptDelivery promptDelivery,
        Integer timeoutSeconds,
        Integer transportRetries,
        Map<String, String> env,
        Integer maxPromptChars,
        ProviderType type,
        String api,
        String baseUrl,
        String model,
        Map<String, Object> options,
        String keepAlive,
        Map<String, String> headers) {

    public ProviderConfig {
        args = args == null ? List.of() : List.copyOf(args);
        promptDelivery = promptDelivery == null ? PromptDelivery.STDIN : promptDelivery;
        timeoutSeconds = timeoutSeconds == null ? 300 : timeoutSeconds;
        transportRetries = transportRetries == null ? 2 : transportRetries;
        env = env == null ? Map.of() : Map.copyOf(env);
        type = type == null ? ProviderType.PROCESS : type;
        api = (api == null || api.isBlank()) && type == ProviderType.HTTP ? "ollama" : api;
        options = options == null ? Map.of() : Map.copyOf(options);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** The shape this record had before HTTP providers existed: a CLI launched as a process. */
    public ProviderConfig(String command, List<String> args, PromptDelivery promptDelivery,
                          Integer timeoutSeconds, Integer transportRetries, Map<String, String> env,
                          Integer maxPromptChars) {
        this(command, args, promptDelivery, timeoutSeconds, transportRetries, env, maxPromptChars,
                null, null, null, null, null, null, null);
    }

    public boolean isHttp() {
        return type == ProviderType.HTTP;
    }
}
