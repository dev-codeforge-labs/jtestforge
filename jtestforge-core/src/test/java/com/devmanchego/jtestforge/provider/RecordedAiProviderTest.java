package com.devmanchego.jtestforge.provider;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordedAiProviderTest {

    @Test
    void returnsQueuedResponsesInOrder() throws Exception {
        RecordedAiProvider provider = new RecordedAiProvider("claude")
                .enqueueResponse("first")
                .enqueueResponse("second");

        assertThat(provider.invoke("p1", Duration.ofSeconds(1)).content()).isEqualTo("first");
        assertThat(provider.invoke("p2", Duration.ofSeconds(1)).content()).isEqualTo("second");
    }

    @Test
    void recordsEveryPromptItWasAskedToAnswer() throws Exception {
        RecordedAiProvider provider = new RecordedAiProvider("claude")
                .enqueueResponse("a").enqueueResponse("b");

        provider.invoke("first prompt", Duration.ofSeconds(1));
        provider.invoke("second prompt", Duration.ofSeconds(1));

        assertThat(provider.receivedPrompts()).containsExactly("first prompt", "second prompt");
        assertThat(provider.lastReceivedPrompt()).isEqualTo("second prompt");
    }

    @Test
    void aQueuedFailureThrowsProviderException() {
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueFailure("simulated transport failure");

        assertThatThrownBy(() -> provider.invoke("p", Duration.ofSeconds(1)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("simulated transport failure");
    }

    @Test
    void invokingWithNothingQueuedThrowsRatherThanReturningNull() {
        RecordedAiProvider provider = new RecordedAiProvider("claude");

        assertThatThrownBy(() -> provider.invoke("p", Duration.ofSeconds(1)))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void hasQueuedResponsesReflectsRemainingCount() {
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueResponse("a");

        assertThat(provider.hasQueuedResponses()).isTrue();
    }

    @Test
    void idIsReportedAsConstructed() {
        assertThat(new RecordedAiProvider("gemini").id()).isEqualTo("gemini");
    }
}
