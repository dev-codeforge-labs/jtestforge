package com.devmanchego.jtestforge.provider;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A scripted {@link AiProvider} for tests — never talks to a real process.
 *
 * <p>This is a deliberate deliverable of this phase, not merely a test helper local to
 * one test class: every later phase that exercises the generation loop (the engine,
 * prompt tuning, quality-gate tests) needs to feed a scripted sequence of responses and
 * inspect exactly what prompts were sent, without spending real time or real API/CLI
 * calls on it. It lives in {@code src/main/java} so those later test packages can depend
 * on it directly.
 */
public final class RecordedAiProvider implements AiProvider {

    private final String id;
    private final Deque<Supplier<AiResponse>> queue = new ArrayDeque<>();
    private final List<String> receivedPrompts = new ArrayList<>();

    public RecordedAiProvider(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    /** Queues a successful response containing exactly {@code content}. */
    public RecordedAiProvider enqueueResponse(String content) {
        queue.add(() -> new AiResponse(content, 0));
        return this;
    }

    /** Queues a transport failure - simulates every retry being exhausted. */
    public RecordedAiProvider enqueueFailure(String message) {
        queue.add(() -> {
            throw new RecordedFailure(message);
        });
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public AiResponse invoke(String prompt, Duration timeout) throws ProviderException {
        receivedPrompts.add(prompt);
        Supplier<AiResponse> next = queue.poll();
        if (next == null) {
            throw new ProviderException(
                    "RecordedAiProvider \"" + id + "\" has no more queued responses "
                            + "(received " + receivedPrompts.size() + " prompts so far)");
        }
        try {
            return next.get();
        } catch (RecordedFailure failure) {
            throw new ProviderException(failure.getMessage());
        }
    }

    /** Every prompt this provider was asked to answer, in the order received. */
    public List<String> receivedPrompts() {
        return List.copyOf(receivedPrompts);
    }

    public String lastReceivedPrompt() {
        if (receivedPrompts.isEmpty()) {
            throw new IllegalStateException("No prompts have been received yet");
        }
        return receivedPrompts.get(receivedPrompts.size() - 1);
    }

    public boolean hasQueuedResponses() {
        return !queue.isEmpty();
    }

    private static final class RecordedFailure extends RuntimeException {
        RecordedFailure(String message) {
            super(message);
        }
    }
}
