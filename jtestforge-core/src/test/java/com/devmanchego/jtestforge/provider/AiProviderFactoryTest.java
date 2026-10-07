package com.devmanchego.jtestforge.provider;

import com.devmanchego.jtestforge.config.PromptDelivery;
import com.devmanchego.jtestforge.config.ProviderConfig;
import com.devmanchego.jtestforge.config.ProviderType;
import com.devmanchego.jtestforge.util.ProcessRunner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderFactoryTest {

    @Test
    void aProcessProviderIsTheCliLauncherUnderItsConfiguredId(@TempDir Path dir) {
        ProviderConfig config = new ProviderConfig("claude", List.of("-p"), PromptDelivery.STDIN, null, null, null, null);

        AiProvider provider = AiProviderFactory.create("claude", config, new ProcessRunner(), dir);

        assertThat(provider).isInstanceOf(ProcessAiProvider.class);
        assertThat(provider.id()).isEqualTo("claude");
    }

    @Test
    void anHttpProviderIsBuiltFromItsOwnSettingsAndReallyUsesThem() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            header.set(exchange.getRequestHeaders().getFirst("X-Team"));
            byte[] answer = "{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.start();
        try {
            ProviderConfig config = new ProviderConfig(null, null, null, null, 0, null, null, ProviderType.HTTP, null,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "qwen3-coder:30b",
                    Map.of("num_ctx", 32768), "30m", Map.of("X-Team", "qa"));

            // No process runner and no working directory: an HTTP provider needs neither.
            AiProvider provider = AiProviderFactory.create("ollama-http", config, null, null);

            assertThat(provider).isInstanceOf(HttpAiProvider.class);
            assertThat(provider.id()).isEqualTo("ollama-http");
            assertThat(provider.invoke("hi", Duration.ofSeconds(10)).content()).isEqualTo("OK");
            assertThat(body.get()).contains("\"model\":\"qwen3-coder:30b\"").contains("\"num_ctx\":32768")
                    .contains("\"keep_alive\":\"30m\"");
            assertThat(header.get()).isEqualTo("qa");
        } finally {
            server.stop(0);
        }
    }
}
