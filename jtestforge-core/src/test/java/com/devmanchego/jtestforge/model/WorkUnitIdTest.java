package com.devmanchego.jtestforge.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkUnitIdTest {

    @Test
    void formatsAPassOneIdExactlyAsTheSpecificationShows() {
        WorkUnitId id = WorkUnitId.of("com.acme.PaymentService",
                "applyFee(java.math.BigDecimal,java.util.Currency)", Tier.PLAIN_UNIT);

        assertThat(id.format()).isEqualTo(
                "com.acme.PaymentService#applyFee(java.math.BigDecimal,java.util.Currency)@PLAIN_UNIT");
    }

    @Test
    void formatsAPassTwoIdWithItsMutantGroup() {
        WorkUnitId id = WorkUnitId.of("com.acme.PaymentService", "applyFee(BigDecimal)", Tier.PLAIN_UNIT)
                .withMutantGroup("CONDITIONALS_BOUNDARY@47");

        assertThat(id.format()).isEqualTo(
                "com.acme.PaymentService#applyFee(BigDecimal)@PLAIN_UNIT::CONDITIONALS_BOUNDARY@47");
    }

    @Test
    void roundTripsThroughItsTextualFormIncludingParameterTypesAndMutantGroup() {
        WorkUnitId original = WorkUnitId.of("com.acme.Foo",
                "bar(java.util.List<java.lang.String>,int)", Tier.WEB_SLICE)
                .withMutantGroup("NEGATE_CONDITIONALS@12");

        assertThat(WorkUnitId.parse(original.format())).isEqualTo(original);
    }

    @Test
    void theTierIsPartOfIdentitySoTheSameMethodCanHoldOneUnitPerTier() {
        WorkUnitId plain = WorkUnitId.of("com.acme.FooController", "get(Long)", Tier.PLAIN_UNIT);
        WorkUnitId slice = WorkUnitId.of("com.acme.FooController", "get(Long)", Tier.WEB_SLICE);

        assertThat(plain).isNotEqualTo(slice);
        assertThat(plain.format()).isNotEqualTo(slice.format());
    }

    @Test
    void aPassOneAndPassTwoIdForTheSameMethodAreDistinct() {
        WorkUnitId passOne = WorkUnitId.of("com.acme.Foo", "bar()", Tier.PLAIN_UNIT);
        WorkUnitId passTwo = passOne.withMutantGroup("MATH@9");

        assertThat(passOne).isNotEqualTo(passTwo);
    }

    @Test
    void parsingRejectsMalformedIds() {
        assertThatThrownBy(() -> WorkUnitId.parse("no-hash-separator@PLAIN_UNIT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkUnitId.parse("com.acme.Foo#bar()"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkUnitId.parse("com.acme.Foo#bar()@NOT_A_TIER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_A_TIER");
    }

    @Test
    void aMethodSignatureContainingAnAtSignIsRejectedBecauseItWouldBreakParsing() {
        assertThatThrownBy(() ->
                WorkUnitId.of("com.acme.Foo", "bar@baz()", Tier.PLAIN_UNIT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
