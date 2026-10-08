package com.devmanchego.jtestforge.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Answers are the shapes of a real Ollama 0.35-0.40 ({@code /api/version}, {@code /api/show}). */
class HttpProviderPreflightTest {

    private HttpServer server;
    private int showStatus;
    private String showBody;
    private final List<String> authorizationHeaders = new ArrayList<>();
    private final List<List<String>> contentTypesSentToShow = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        showStatus = 200;
        showBody = "{\"model_info\":{\"general.architecture\":\"qwen3moe\",\"qwen3moe.context_length\":262144}}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/version", exchange -> {
            authorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            reply(exchange, 200, "{\"version\":\"0.40.0\"}");
        });
        server.createContext("/api/show", exchange -> {
            authorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.getRequestBody().readAllBytes();
            contentTypesSentToShow.add(exchange.getRequestHeaders().get("Content-Type"));
            reply(exchange, showStatus, showBody);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aRunningServerWithTheModelAndAnAmpleContextRaisesNothing() {
        HttpProviderPreflight.Result result = check(Map.of("num_ctx", 32768, "num_predict", 4096), 60_000);

        assertThat(result.isUsable()).isTrue();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("Ollama 0.40.0"))
                .anySatisfy(note -> assertThat(note).contains("262144"))
                .anySatisfy(note -> assertThat(note).contains("num_ctx 32768"));
    }

    @Test
    void aServerThatIsNotRunningStopsTheRunBeforeAnyUnit() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        HttpProviderPreflight.Result result = new HttpProviderPreflight().check(
                "http://127.0.0.1:" + closedPort, "m", Map.of(), Map.of(), 60_000);

        assertThat(result.isUsable()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(error ->
                assertThat(error).contains("Cannot reach the model server").contains("is it running"));
    }

    @Test
    void aModelTheServerDoesNotHaveIsReportedWithHowToGetIt() {
        showStatus = 404;
        showBody = "{\"error\":\"model 'nope' not found\"}";

        HttpProviderPreflight.Result result = check(Map.of("num_ctx", 32768), 60_000);

        assertThat(result.isUsable()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(error ->
                assertThat(error).contains("no model named").contains("ollama pull").contains("ollama list"));
    }

    @Test
    void anUnexpectedStatusFromShowIsAnError() {
        showStatus = 500;
        showBody = "{}";

        assertThat(check(Map.of("num_ctx", 32768), 60_000).errors()).singleElement()
                .satisfies(error -> assertThat(error).contains("HTTP 500"));
    }

    @Test
    void anUnsetContextIsWarnedAboutBecauseTheServersDefaultIsTooSmallForThePrompts() {
        HttpProviderPreflight.Result result = check(Map.of(), 60_000);

        assertThat(result.isUsable()).isTrue();
        assertThat(result.warnings()).singleElement().satisfies(warning ->
                assertThat(warning).contains("options.num_ctx is not set").contains("60000-character"));
    }

    @Test
    void aContextTooSmallForTheBudgetSaysHowManyCharactersDoFit() {
        // 60000 chars ~ 20000 tokens + 4096 of answer = 24096 > 16384; (16384 - 4096) * 3 = 36864 fit.
        HttpProviderPreflight.Result result = check(Map.of("num_ctx", 16384, "num_predict", 4096), 60_000);

        assertThat(result.isUsable()).isTrue();
        assertThat(result.warnings()).singleElement().satisfies(warning -> assertThat(warning)
                .contains("may not fit").contains("refused, not cut").contains("36864 characters fit"));
    }

    @Test
    void aContextLargerThanTheModelSupportsIsWarnedAbout() {
        showBody = "{\"model_info\":{\"llama.context_length\":8192}}";

        HttpProviderPreflight.Result result = check(Map.of("num_ctx", 32768), 20_000);

        assertThat(result.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("larger than the model's own context").contains("8192"));
    }

    @Test
    void aContextAboveTheModelsOwnIsJudgedAtWhatTheServerWillActuallyGive() {
        // Review finding: 32768 configured on an 8192-token model used to pass the "does it fit"
        // check against 32768, although every 60000-character prompt would be refused.
        showBody = "{\"model_info\":{\"llama.context_length\":8192}}";

        HttpProviderPreflight.Result result = check(Map.of("num_ctx", 32768, "num_predict", 1024), 60_000);

        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("larger than the model's own"))
                .anySatisfy(warning -> assertThat(warning).contains("may not fit").contains("21504 characters fit"));
    }

    @Test
    void aNumericStringForNumCtxIsUnderstoodLikeANumber() {
        assertThat(check(Map.of("num_ctx", "32768"), 60_000).warnings()).isEmpty();
    }

    @Test
    void theBudgetArithmeticIsExactAtTheBoundary() {
        List<String> warnings = new ArrayList<>();
        // 30000 chars -> exactly 10000 tokens: fits num_ctx 10000, does not fit 9999.
        HttpProviderPreflight.assessContext(Map.of("num_ctx", 10_000), 30_000, OptionalLong.empty(), warnings, new ArrayList<>());
        assertThat(warnings).isEmpty();

        HttpProviderPreflight.assessContext(Map.of("num_ctx", 9_999), 30_000, OptionalLong.empty(), warnings, new ArrayList<>());
        assertThat(warnings).hasSize(1);
    }

    @Test
    void configuredHeadersAreSentButNeverAppearInAnyMessage() {
        showStatus = 500;

        HttpProviderPreflight.Result result = new HttpProviderPreflight().check(baseUrl(), "m", Map.of(),
                Map.of("Authorization", "Bearer sk-very-secret"), 60_000);

        assertThat(authorizationHeaders).containsOnly("Bearer sk-very-secret");
        assertThat(String.join(" ", result.errors()) + String.join(" ", result.warnings())
                + String.join(" ", result.notes())).doesNotContain("sk-very-secret");
    }

    @Test
    void aConfiguredContentTypeReplacesTheDefaultInsteadOfBeingSentTwice() {
        new HttpProviderPreflight().check(baseUrl(), "m", Map.of(),
                Map.of("Content-Type", "application/json; charset=utf-8"), 60_000);

        assertThat(contentTypesSentToShow).singleElement()
                .satisfies(values -> assertThat(values).containsExactly("application/json; charset=utf-8"));
    }

    private HttpProviderPreflight.Result check(Map<String, Object> options, int maxPromptChars) {
        return new HttpProviderPreflight().check(baseUrl(), "qwen3-coder:30b", options, Map.of(), maxPromptChars);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
