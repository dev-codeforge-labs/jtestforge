package com.devmanchego.jtestforge.e2e;

import com.devmanchego.jtestforge.provider.AiResponse;
import com.devmanchego.jtestforge.provider.AiProvider;
import com.devmanchego.jtestforge.provider.ProviderException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic {@link AiProvider} that answers based on <em>what the prompt asks for</em>
 * rather than on call order.
 *
 * <p>{@code RecordedAiProvider}'s strict FIFO queue is right for a test that scripts one
 * unit's exact sequence, but wrong here: an end-to-end run's unit order depends on tier
 * ordering, class grouping and measured coverage, so a queue would make these tests fail
 * for reasons that have nothing to do with what they assert. Routing on prompt content
 * keeps the run genuinely deterministic while leaving the engine free to schedule units
 * however §9.6 says it should.
 *
 * <p>An unmatched prompt is a hard failure, never a silent empty answer: it means the run
 * asked for something this fixture did not anticipate, which is a fact the test needs to
 * see rather than absorb.
 */
final class PromptRoutingAiProvider implements AiProvider {

    private final List<Rule> rules = new ArrayList<>();
    private final List<String> receivedPrompts = new ArrayList<>();

    /**
     * Adds a rule; the first rule whose markers <b>all</b> appear in a prompt answers it.
     *
     * <p>One marker is rarely enough. Two units for the same method name - a plain one for
     * the handler body and a web-slice one for its mapping - render the same
     * {@code {{TARGET_METHOD}}} signature, and the unit's tier does not appear in a
     * generation prompt at all. Requiring several markers (a signature plus something only
     * the slice template or the generated slice skeleton contains) is what separates them,
     * and ordering the rules most-specific-first is what keeps a broad rule from
     * swallowing a narrow one.
     */
    PromptRoutingAiProvider respondTo(List<String> requiredMarkers, String response) {
        rules.add(new Rule(List.copyOf(requiredMarkers), response));
        return this;
    }

    PromptRoutingAiProvider respondTo(String marker, String response) {
        return respondTo(List.of(marker), response);
    }

    @Override
    public String id() {
        return "prompt-routing";
    }

    @Override
    public AiResponse invoke(String prompt, Duration timeout) throws ProviderException {
        receivedPrompts.add(prompt);
        for (Rule rule : rules) {
            if (rule.matches(prompt)) {
                return new AiResponse(rule.response(), 0);
            }
        }
        throw new ProviderException("No scripted response matches this prompt. Known rules: "
                + rules.stream().map(Rule::requiredMarkers).toList());
    }

    List<String> receivedPrompts() {
        return List.copyOf(receivedPrompts);
    }

    int invocationCount() {
        return receivedPrompts.size();
    }

    private record Rule(List<String> requiredMarkers, String response) {
        boolean matches(String prompt) {
            return requiredMarkers.stream().allMatch(prompt::contains);
        }
    }
}
