package com.devmanchego.jtestforge.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Talks to a model server over HTTP - {@code aiProvider.providers.*} with {@code type: http}.
 * Speaks Ollama's native {@code POST /api/chat}, non-streaming.
 *
 * <p>Why a second provider at all, when {@link ProcessAiProvider} can already drive
 * {@code ollama run}: an HTTP request can set the context size, temperature and repetition
 * penalty per call (a CLI cannot - it needs a derived model), and the server can be told to
 * refuse rather than silently cut a prompt that does not fit. Measured against Ollama 0.35.1:
 * by default a 5,605-token prompt sent with {@code num_ctx: 512} was cut to 258 tokens - half
 * the context, not all of it - and answered {@code done_reason: stop} as if nothing had
 * happened, so no threshold on {@code prompt_eval_count} can detect it. With
 * {@code "truncate": false} the same request is an HTTP 400 {@code exceed_context_size_error}
 * that names both sizes. This provider always sends it. An answer that ran out of output
 * tokens ({@code done_reason: length}) is still returned - the response parser and the
 * corrective re-prompt deal with a cut-off answer - but said so in the metrics.
 *
 * <p>Failure handling mirrors {@link ProcessAiProvider}'s split between transport and content:
 * <ul>
 *   <li><b>Retried</b> up to {@code transportRetries} times - nothing was learned about the
 *       prompt: the server could not be reached, timed out, answered 5xx or 429, or answered
 *       200 with no usable text.</li>
 *   <li><b>Not retried</b> - the same request would fail the same way: 4xx (an unknown model is
 *       Ollama's 404), including a prompt that does not fit the context.</li>
 * </ul>
 * Configured headers are sent but never put in an exception, a diagnostic or a log line.
 */
public final class HttpAiProvider implements AiProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** A diagnostic's response body is kept, but not without limit: a runaway answer can be huge. */
    private static final int MAX_BODY_IN_DIAGNOSTICS = 20_000;

    private final String id;
    private final URI chatEndpoint;
    private final String model;
    private final Map<String, Object> options;
    private final String keepAlive;
    private final Map<String, String> headers;
    private final int transportRetries;
    private final HttpClient client;

    /**
     * @param baseUrl          the server, e.g. {@code http://localhost:11434}; {@code /api/chat} is appended
     * @param options          sent as the request's {@code options}, exactly as configured
     * @param keepAlive        sent as {@code keep_alive} when not null
     * @param headers          extra request headers; never logged
     * @param transportRetries extra attempts after a retryable failure
     */
    public HttpAiProvider(String id, String baseUrl, String model, Map<String, Object> options,
                          String keepAlive, Map<String, String> headers, int transportRetries) {
        this.id = Objects.requireNonNull(id, "id");
        this.chatEndpoint = chatEndpointOf(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.model = Objects.requireNonNull(model, "model");
        this.options = Map.copyOf(options == null ? Map.of() : options);
        this.keepAlive = keepAlive;
        this.headers = Map.copyOf(headers == null ? Map.of() : headers);
        this.transportRetries = Math.max(0, transportRetries);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(MAX_CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public AiResponse invoke(String prompt, Duration timeout) throws ProviderException {
        long startNanos = System.nanoTime();
        byte[] body = requestBody(prompt);
        ProviderException lastFailure = null;
        for (int attempt = 0; attempt <= transportRetries; attempt++) {
            String label = "\"" + id + "\" attempt " + (attempt + 1) + "/" + (transportRetries + 1);
            try {
                return attempt(body, timeout, label, startNanos);
            } catch (Retryable failure) {
                lastFailure = failure.toProviderException();
            }
        }
        throw lastFailure;
    }

    private AiResponse attempt(byte[] body, Duration timeout, String label, long startNanos)
            throws Retryable, ProviderException {
        HttpRequest.Builder request = HttpRequest.newBuilder(chatEndpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);

        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new Retryable(label + " failed: timed out after " + timeout.toSeconds() + " s",
                    diagnostics(Integer.MIN_VALUE, "", "timed out"));
        } catch (ConnectException e) {
            throw new Retryable(label + " failed: could not connect to " + chatEndpoint
                    + " - is the server running?", diagnostics(-1, "", describe(e)));
        } catch (IOException e) {
            throw new Retryable(label + " failed: " + describe(e), diagnostics(-1, "", describe(e)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while calling \"" + id + "\"", e);
        }

        int status = response.statusCode();
        String responseBody = response.body() == null ? "" : response.body();
        if (status == 429 || status >= 500) {
            throw new Retryable(label + " failed: HTTP " + status + firstLine(responseBody),
                    diagnostics(status, responseBody, ""));
        }
        if (status != 200) {
            // 4xx: the request itself is wrong (an unknown model is Ollama's 404) - retrying
            // would only repeat it.
            if (isContextTooSmall(responseBody)) {
                throw new ProviderException(label + " failed: the prompt does not fit the model's context"
                        + firstLine(responseBody) + " - raise options.num_ctx or lower maxPromptChars for "
                        + "this provider.", diagnostics(status, responseBody, ""));
            }
            throw new ProviderException(label + " failed: HTTP " + status + firstLine(responseBody),
                    diagnostics(status, responseBody, ""));
        }
        return parse(responseBody, label, startNanos);
    }

    private AiResponse parse(String responseBody, String label, long startNanos)
            throws Retryable, ProviderException {
        JsonNode json;
        try {
            json = MAPPER.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new Retryable(label + " failed: the response is not JSON", diagnostics(200, responseBody, ""));
        }
        String content = json.path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new Retryable(label + " failed: produced no output", diagnostics(200, responseBody, ""));
        }
        return new AiResponse(content, elapsedMillis(startNanos), metrics(json));
    }

    /** The response's own numbers, for {@code N.stderr.log}; says plainly when output ran out. */
    private String metrics(JsonNode json) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", json.path("model").asText(model));
        longField(json, "prompt_eval_count").ifPresent(value -> fields.put("prompt_eval_count", Long.toString(value)));
        numCtx().ifPresent(value -> fields.put("num_ctx", Long.toString(value)));
        longField(json, "eval_count").ifPresent(value -> fields.put("eval_count", Long.toString(value)));
        longField(json, "total_duration").ifPresent(value -> fields.put("total_duration_ms", Long.toString(value / 1_000_000)));
        String doneReason = json.path("done_reason").asText("");
        if (!doneReason.isEmpty()) {
            fields.put("done_reason", doneReason);
        }
        StringBuilder text = new StringBuilder();
        fields.forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
        if ("length".equals(doneReason)) {
            text.append("WARNING: the answer stopped because it reached the output limit (num_predict), not because "
                    + "the model finished - it is probably cut off, or the model was looping.\n");
        }
        return text.toString();
    }

    private byte[] requestBody(String prompt) throws ProviderException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", model);
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "user").put("content", prompt);
        request.put("stream", false);
        // Refuse instead of silently dropping the start of the prompt - see the class comment.
        request.put("truncate", false);
        if (!options.isEmpty()) {
            request.set("options", MAPPER.valueToTree(options));
        }
        if (keepAlive != null && !keepAlive.isBlank()) {
            request.put("keep_alive", keepAlive);
        }
        try {
            return MAPPER.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw new ProviderException("Could not build the request for \"" + id + "\": " + e.getMessage(), e);
        }
    }

    /** Ollama's refusal of a prompt larger than the context, when asked not to truncate. */
    private static boolean isContextTooSmall(String responseBody) {
        return responseBody.contains("exceed_context_size_error")
                || responseBody.contains("exceeds the available context size");
    }

    /** {@code options.num_ctx} when configured as a number (or a numeric string). */
    private OptionalLong numCtx() {
        Object value = options.get("num_ctx");
        if (value instanceof Number number) {
            return OptionalLong.of(number.longValue());
        }
        if (value instanceof String text) {
            try {
                return OptionalLong.of(Long.parseLong(text.strip()));
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong longField(JsonNode json, String name) {
        JsonNode node = json.get(name);
        return node != null && node.canConvertToLong() ? OptionalLong.of(node.asLong()) : OptionalLong.empty();
    }

    private ProviderException.Diagnostics diagnostics(int status, String responseBody, String error) {
        String body = responseBody.length() <= MAX_BODY_IN_DIAGNOSTICS
                ? responseBody
                : responseBody.substring(0, MAX_BODY_IN_DIAGNOSTICS) + "\n... [" + (responseBody.length()
                        - MAX_BODY_IN_DIAGNOSTICS) + " more characters]";
        return new ProviderException.Diagnostics("POST " + chatEndpoint + " (model " + model + ")", status, body, error);
    }

    private static URI chatEndpointOf(String baseUrl) {
        String trimmed = baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return URI.create(trimmed + "/api/chat");
    }

    private static String firstLine(String body) {
        String stripped = body.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        int newline = stripped.indexOf('\n');
        String line = newline < 0 ? stripped : stripped.substring(0, newline);
        return ": " + (line.length() > 300 ? line.substring(0, 300) + "..." : line);
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    /** A failure the next attempt might not repeat. */
    private static final class Retryable extends Exception {
        private final ProviderException.Diagnostics diagnostics;

        Retryable(String message, ProviderException.Diagnostics diagnostics) {
            super(message, null, false, false);
            this.diagnostics = diagnostics;
        }

        ProviderException toProviderException() {
            return new ProviderException(getMessage(), diagnostics);
        }
    }
}
