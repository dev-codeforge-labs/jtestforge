package com.devmanchego.jtestforge.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HttpAiProvider} against a real local HTTP server with scripted answers - real sockets,
 * real timeouts, no mocked client. The response shapes are Ollama's, as observed from a real
 * Ollama 0.35.1 (including its context-size refusal).
 */
class HttpAiProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private java.util.concurrent.ExecutorService serverThreads;
    private final Deque<Scripted> script = new ArrayDeque<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final List<Map<String, List<String>>> requestHeaders = new ArrayList<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            hits.incrementAndGet();
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestHeaders.add(Map.copyOf(exchange.getRequestHeaders()));
            Scripted next = script.isEmpty() ? new Scripted(500, "{\"error\":\"nothing scripted\"}", 0) : script.poll();
            if (next.delayMillis() > 0) {
                try {
                    Thread.sleep(next.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(next.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // One thread per exchange: the JDK default is a single thread, which would queue a retry
        // behind a deliberately slow first answer and make attempts look like they never happened.
        serverThreads = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void aSuccessfulCallReturnsTheMessageContentAndSendsExactlyTheConfiguredRequest() throws Exception {
        script.add(ok("```java\n@Test void t() {}\n```", "\"done_reason\":\"stop\",\"prompt_eval_count\":1759,\"eval_count\":87"));
        HttpAiProvider provider = new HttpAiProvider("ollama-http", baseUrl(), "qwen3-coder:30b",
                Map.of("num_ctx", 32768, "temperature", 0.7), "30m", Map.of("X-Proxy-Token", "s3cret"), 0);

        AiResponse response = provider.invoke("Write tests for Foo", Duration.ofSeconds(10));

        assertThat(response.content()).isEqualTo("```java\n@Test void t() {}\n```");
        JsonNode sent = MAPPER.readTree(requestBodies.get(0));
        assertThat(sent.path("model").asText()).isEqualTo("qwen3-coder:30b");
        assertThat(sent.path("stream").asBoolean(true)).isFalse();
        assertThat(sent.path("truncate").asBoolean(true)).as("never let the server cut the prompt").isFalse();
        assertThat(sent.path("keep_alive").asText()).isEqualTo("30m");
        assertThat(sent.path("options").path("num_ctx").asInt()).isEqualTo(32768);
        assertThat(sent.path("options").path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(sent.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo("Write tests for Foo");
        assertThat(requestHeaders.get(0).get("X-proxy-token")).containsExactly("s3cret");
        assertThat(response.stderr()).contains("prompt_eval_count: 1759").contains("eval_count: 87")
                .contains("num_ctx: 32768").contains("done_reason: stop");
    }

    @Test
    void aTrailingSlashInTheBaseUrlStillReachesApiChat() throws Exception {
        script.add(ok("answer", ""));

        new HttpAiProvider("o", baseUrl() + "/", "m", null, null, null, 0).invoke("p", Duration.ofSeconds(10));

        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void anUnknownModelIsNotRetriedAndTheServersOwnWordsAreKept() {
        script.add(new Scripted(404, "{\"error\":\"model 'nope:1b' not found\"}", 0));

        assertThatThrownBy(() -> provider(2).invoke("p", Duration.ofSeconds(10)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("HTTP 404").hasMessageContaining("not found")
                .satisfies(e -> assertThat(((ProviderException) e).diagnostics()).hasValueSatisfying(d -> {
                    assertThat(d.exitCode()).isEqualTo(404);
                    assertThat(d.command()).contains("POST").contains("/api/chat").contains("(model m)");
                }));
        assertThat(hits.get()).as("a 4xx is not retried").isEqualTo(1);
    }

    @Test
    void aPromptThatDoesNotFitTheContextIsRefusedClearlyAndNotRetried() {
        // Ollama 0.35.1's actual answer to "truncate": false with an oversized prompt.
        script.add(new Scripted(400, "{\"error\":\"{\\\"error\\\":{\\\"code\\\":400,\\\"message\\\":\\\"request (5605 tokens) "
                + "exceeds the available context size (512 tokens), try increasing it\\\",\\\"type\\\":"
                + "\\\"exceed_context_size_error\\\",\\\"n_prompt_tokens\\\":5605,\\\"n_ctx\\\":512}}\"}", 0));

        assertThatThrownBy(() -> provider(2).invoke("a long prompt", Duration.ofSeconds(10)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("does not fit the model's context")
                .hasMessageContaining("5605 tokens")
                .hasMessageContaining("options.num_ctx");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void aServerErrorIsRetriedAndASubsequentSuccessIsReturned() throws Exception {
        script.add(new Scripted(500, "{\"error\":\"model runner crashed\"}", 0));
        script.add(ok("second time lucky", ""));

        AiResponse response = provider(1).invoke("p", Duration.ofSeconds(10));

        assertThat(response.content()).isEqualTo("second time lucky");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void persistentServerErrorsExhaustTheRetriesAndReportTheLastOne() {
        script.add(new Scripted(503, "busy", 0));
        script.add(new Scripted(503, "still busy", 0));

        assertThatThrownBy(() -> provider(1).invoke("p", Duration.ofSeconds(10)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("attempt 2/2").hasMessageContaining("HTTP 503").hasMessageContaining("still busy");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void aSlowServerTimesOutAndIsRetried() {
        script.add(new Scripted(200, okBody("too late", ""), 3_000));
        script.add(new Scripted(200, okBody("too late again", ""), 3_000));

        assertThatThrownBy(() -> provider(1).invoke("p", Duration.ofMillis(500)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("timed out")
                .satisfies(e -> assertThat(((ProviderException) e).diagnostics())
                        .hasValueSatisfying(d -> assertThat(d.exitCode()).isEqualTo(Integer.MIN_VALUE)));
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void aServerThatIsNotRunningSaysSo() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> new HttpAiProvider("o", "http://127.0.0.1:" + closedPort, "m", null, null, null, 0)
                .invoke("p", Duration.ofSeconds(5)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("could not connect").hasMessageContaining("is the server running");
    }

    @Test
    void anEmptyAnswerOrOneThatIsNotJsonIsATransportFailureAndIsRetried() throws Exception {
        script.add(ok("", ""));
        script.add(new Scripted(200, "<html>a proxy error page</html>", 0));
        script.add(ok("finally", ""));

        assertThat(provider(2).invoke("p", Duration.ofSeconds(10)).content()).isEqualTo("finally");
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test
    void anAnswerCutOffByTheOutputLimitIsReturnedButSaidSoInTheMetrics() throws Exception {
        script.add(ok("```java\n@Test void t() {", "\"done_reason\":\"length\",\"eval_count\":4096"));

        AiResponse response = provider(0).invoke("p", Duration.ofSeconds(10));

        assertThat(response.content()).startsWith("```java");
        assertThat(response.stderr()).contains("done_reason: length").contains("output limit");
    }

    @Test
    void configuredHeadersNeverAppearInAFailureOrItsDiagnostics() {
        script.add(new Scripted(500, "{\"error\":\"boom\"}", 0));

        assertThatThrownBy(() -> new HttpAiProvider("o", baseUrl(), "m", null, null,
                Map.of("Authorization", "Bearer sk-very-secret"), 0).invoke("p", Duration.ofSeconds(10)))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException failure = (ProviderException) e;
                    assertThat(failure.getMessage()).doesNotContain("sk-very-secret");
                    assertThat(failure.diagnostics()).hasValueSatisfying(d -> assertThat(
                            d.command() + d.stdout() + d.stderr()).doesNotContain("sk-very-secret"));
                });
    }

    // --- fixtures ---------------------------------------------------------------------------

    private HttpAiProvider provider(int retries) {
        return new HttpAiProvider("o", baseUrl(), "m", null, null, null, retries);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Scripted ok(String content, String extraFields) {
        return new Scripted(200, okBody(content, extraFields), 0);
    }

    private static String okBody(String content, String extraFields) {
        String escaped;
        try {
            escaped = MAPPER.writeValueAsString(content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":" + escaped + "},\"done\":true"
                + (extraFields.isEmpty() ? "" : "," + extraFields) + "}";
    }

    private record Scripted(int status, String body, long delayMillis) {
    }
}
