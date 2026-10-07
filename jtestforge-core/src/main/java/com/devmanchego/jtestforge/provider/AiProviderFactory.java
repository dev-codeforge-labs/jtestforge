package com.devmanchego.jtestforge.provider;

import com.devmanchego.jtestforge.config.ProviderConfig;
import com.devmanchego.jtestforge.util.ProcessRunner;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Turns one {@code aiProvider.providers.*} entry into the {@link AiProvider} that talks to it -
 * the single place that knows which implementation a {@link ProviderConfig#type()} means, so
 * the command line and the manual tuning loop cannot drift apart on it.
 */
public final class AiProviderFactory {

    private AiProviderFactory() {
    }

    /**
     * @param processRunner    runs the CLI for a {@code process} provider; unused for {@code http}
     * @param workingDirectory where a {@code process} provider's CLI starts; unused for {@code http}
     */
    public static AiProvider create(String id, ProviderConfig config, ProcessRunner processRunner,
                                    Path workingDirectory) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(config, "config");
        if (config.isHttp()) {
            return new HttpAiProvider(id, config.baseUrl(), config.model(), config.options(),
                    config.keepAlive(), config.headers(), config.transportRetries());
        }
        return new ProcessAiProvider(id, Objects.requireNonNull(processRunner, "processRunner"),
                config.command(), config.args(), config.promptDelivery(), config.transportRetries(),
                Objects.requireNonNull(workingDirectory, "workingDirectory"), config.env());
    }
}
