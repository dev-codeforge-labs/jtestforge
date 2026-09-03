package com.devmanchego.jtestforge.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedTestSelectorTest {

    @Test
    void buildsTheSelectorForOneMethod() {
        assertThat(ScopedTestSelector.build("PaymentServiceTest", List.of("appliesTheFlatFee")))
                .isEqualTo("PaymentServiceTest#appliesTheFlatFee");
    }

    @Test
    void buildsTheSelectorForSeveralMethodsJoinedByPlus() {
        assertThat(ScopedTestSelector.build("PaymentServiceTest",
                List.of("appliesTheFlatFee", "rejectsNegativeAmounts")))
                .isEqualTo("PaymentServiceTest#appliesTheFlatFee+rejectsNegativeAmounts");
    }

    @Test
    void buildsTheReadyToUseDArgument() {
        assertThat(ScopedTestSelector.buildArgument("PaymentServiceTest", List.of("a", "b")))
                .isEqualTo("-Dtest=PaymentServiceTest#a+b");
    }

    @Test
    void rejectsAnEmptyMethodList() {
        assertThatThrownBy(() -> ScopedTestSelector.build("PaymentServiceTest", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScopedTestSelector.build("PaymentServiceTest", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void methodNamesWithNoSpecialShellMeaningPassThroughUnescaped() {
        // No shell sits between JTestForge and Maven (ProcessRunner always uses an
        // argument list), so characters like '#', '+' and ',' inside a name are not
        // metacharacters here - they simply never occur in valid Java identifiers anyway.
        assertThat(ScopedTestSelector.build("FooTest", List.of("plain_method123")))
                .isEqualTo("FooTest#plain_method123");
    }
}
