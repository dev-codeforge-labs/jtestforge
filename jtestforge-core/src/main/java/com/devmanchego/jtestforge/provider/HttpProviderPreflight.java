package com.devmanchego.jtestforge.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * What can be checked about an Ollama server before a run starts, so a wrong setup costs one
 * line at the top instead of one failed unit after another (each of which would end the run
 * with "AI provider unreachable"): is it running, does it have the model, and will the prompts
 * this run is going to build fit the context it will give that model.
 *
 * <p>Two read-only requests: {@code GET /api/version} and {@code POST /api/show}. Nothing is
 * generated and no model is loaded. Configured headers are sent and never reported.
 */
public final class HttpProviderPreflight {

    /**
     * Characters per token assumed when sizing a prompt. Deliberately pessimistic: measured on
     * the fixture module with Qwen3-Coder it is about 4.5, but a prompt full of symbols or
     * non-English text is denser, and the cost of a wrong guess is asymmetric - a spurious
     * warning is read and dismissed, a missed one is a unit refused for not fitting.
     */
    static final double CHARS_PER_TOKEN = 3.0;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** @param notes what was found, for the run's header; {@code errors} stop the run, {@code warnings} do not */
    public record Result(List<String> errors, List<String> warnings, List<String> notes) {
        public boolean isUsable() {
            return errors.isEmpty();
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * @param options        the provider's {@code options}, as configured
     * @param maxPromptChars the prompt budget this run will actually use for this provider
     */
    public Result check(String baseUrl, String model, Map<String, Object> options,
                        Map<String, String> headers, int maxPromptChars) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String root = OllamaSettings.root(baseUrl);

        Exchange version = send(HttpRequest.newBuilder(URI.create(root + "/api/version")).GET(), headers);
        if (version.failure() != null) {
            errors.add("Cannot reach the model server at " + root + " (" + version.failure()
                    + ") - is it running?");
            return new Result(errors, warnings, notes);
        }
        if (version.status() == 200) {
            String serverVersion = parse(version.body()).path("version").asText("");
            if (!serverVersion.isEmpty()) {
                notes.add("Model server at " + root + " is Ollama " + serverVersion);
            }
        }

        HttpRequest.Builder show = HttpRequest.newBuilder(URI.create(root + "/api/show"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.createObjectNode().put("model", model).toString(), StandardCharsets.UTF_8));
        Exchange shown = send(show, headers);
        if (shown.failure() != null) {
            errors.add("Cannot ask the model server about \"" + model + "\" (" + shown.failure() + ").");
            return new Result(errors, warnings, notes);
        }
        if (shown.status() == 404) {
            errors.add("The model server has no model named \"" + model + "\". Pull or create it first "
                    + "(`ollama pull " + model + "` / `ollama create`); `ollama list` shows what it has.");
            return new Result(errors, warnings, notes);
        }
        if (shown.status() != 200) {
            errors.add("The model server answered HTTP " + shown.status() + " when asked about \"" + model + "\".");
            return new Result(errors, warnings, notes);
        }

        OptionalLong modelContext = contextLength(parse(shown.body()));
        modelContext.ifPresent(length -> notes.add("Model \"" + model + "\" supports a context of " + length + " tokens"));
        assessContext(options, maxPromptChars, modelContext, warnings, notes);
        return new Result(errors, warnings, notes);
    }

    /**
     * The pure part: does the configured context hold the prompts this run will build?
     * Split out because it is arithmetic worth testing without a server.
     */
    static void assessContext(Map<String, Object> options, int maxPromptChars, OptionalLong modelContext,
                              List<String> warnings, List<String> notes) {
        OptionalLong numCtx = number(options.get("num_ctx"));
        if (numCtx.isEmpty()) {
            warnings.add("options.num_ctx is not set, so the server's default context applies - usually far "
                    + "smaller than a " + maxPromptChars + "-character prompt needs, and a prompt that does not "
                    + "fit is refused. Set options.num_ctx (32768 holds the default budget).");
            return;
        }
        long configured = numCtx.getAsLong();
        // The server will not give a model more context than it supports, so that is what fits.
        long ctx = modelContext.isPresent() ? Math.min(configured, modelContext.getAsLong()) : configured;
        if (modelContext.isPresent() && configured > modelContext.getAsLong()) {
            warnings.add("options.num_ctx (" + configured + ") is larger than the model's own context ("
                    + modelContext.getAsLong() + "); the server will not give it more than the model supports.");
        }
        long promptTokens = (long) Math.ceil(maxPromptChars / CHARS_PER_TOKEN);
        long outputTokens = number(options.get("num_predict")).orElse(0);
        notes.add("Context budget: num_ctx " + ctx + " tokens; a full " + maxPromptChars + "-character prompt is about "
                + promptTokens + " tokens" + (outputTokens > 0 ? ", plus up to " + outputTokens + " of answer" : ""));
        if (promptTokens + outputTokens > ctx) {
            warnings.add("A full " + maxPromptChars + "-character prompt (about " + promptTokens + " tokens"
                    + (outputTokens > 0 ? " plus up to " + outputTokens + " of answer" : "")
                    + ") may not fit the usable context of " + ctx + " tokens; such a prompt is refused, not cut. Raise "
                    + "options.num_ctx, or lower maxPromptChars for this provider (about "
                    + Math.max(0, (long) ((ctx - outputTokens) * CHARS_PER_TOKEN)) + " characters fit).");
        }
    }

    private static OptionalLong contextLength(JsonNode show) {
        JsonNode info = show.path("model_info");
        var fields = info.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().endsWith(".context_length") && field.getValue().canConvertToLong()) {
                return OptionalLong.of(field.getValue().asLong());
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong number(Object value) {
        return OllamaSettings.number(value);
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body == null ? "" : body);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    private Exchange send(HttpRequest.Builder request, Map<String, String> headers) {
        request.timeout(TIMEOUT);
        headers.forEach(request::setHeader);
        try {
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Exchange(response.statusCode(), response.body(), null);
        } catch (IOException e) {
            return new Exchange(-1, "", e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Exchange(-1, "", "interrupted");
        }
    }

    private record Exchange(int status, String body, String failure) {
    }
}
