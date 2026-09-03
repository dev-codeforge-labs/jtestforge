package com.devmanchego.jtestforge.mutation;

import com.devmanchego.jtestforge.model.Mutant;
import com.devmanchego.jtestforge.model.MutationReport;
import com.devmanchego.jtestforge.model.MutationStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parses a hand-authored fixture against PIT's documented, long-stable
 * {@code mutations.xml} shape — jtestforge-specification.md §10.1, §13.2.
 *
 * <p>Hand-authored rather than captured from a live run, unlike the JaCoCo fixture in
 * phase 8: {@code org.pitest} artifacts are not resolvable in this offline environment, so
 * there is no way to run PIT for real here. This mirrors the project's existing precedent
 * for Surefire's recorded-shaped fixtures (phase 7) for the same reason.
 */
class MutationReportParserTest {

    private static final Path FIXTURE = Path.of("src/test/resources/pit-fixture/mutations.xml");

    private final MutationReportParser parser = new MutationReportParser();

    @Test
    void theFixtureParsesIntoFourMutants() {
        MutationReport report = parser.parse(FIXTURE);

        assertThat(report.mutants()).hasSize(4);
    }

    @Test
    void aKilledMutantCarriesItsKillingTestWithTheRunIndexSuffixStripped() {
        MutationReport report = parser.parse(FIXTURE);

        Mutant killed = mutantFor(report, "add");
        assertThat(killed.status()).isEqualTo(MutationStatus.KILLED);
        assertThat(killed.mutatedClass()).isEqualTo("com.acme.Calculator");
        assertThat(killed.methodDescription()).isEqualTo("(II)I");
        assertThat(killed.lineNumber()).isEqualTo(12);
        assertThat(killed.mutator())
                .isEqualTo("org.pitest.mutationtest.engine.gregor.mutators.MathMutator");
        assertThat(killed.indexes()).containsExactly(3);
        // PIT wrote "com.acme.CalculatorTest.addsTwoNumbers(3/5)" - the (3/5) run-index
        // suffix is not part of the test's identity and must not survive parsing.
        assertThat(killed.killingTest()).isEqualTo("com.acme.CalculatorTest.addsTwoNumbers");
        assertThat(killed.description()).isEqualTo("Replaced integer addition with subtraction");
    }

    @Test
    void aSurvivedMutantHasNoKillingTest() {
        Mutant survived = mutantFor(parser.parse(FIXTURE), "classify");

        assertThat(survived.status()).isEqualTo(MutationStatus.SURVIVED);
        assertThat(survived.killingTest()).isNull();
    }

    @Test
    void survivedOrUncoveredIncludesExactlySurvivedAndNoCoverage() {
        MutationReport report = parser.parse(FIXTURE);

        assertThat(report.survivedOrUncovered())
                .extracting(Mutant::mutatedMethod)
                .containsExactlyInAnyOrder("classify", "reset");
    }

    @Test
    void aNonViableMutantIsParsedButExcludedFromSurvivedOrUncovered() {
        MutationReport report = parser.parse(FIXTURE);

        Mutant nonViable = mutantFor(report, "<init>");
        assertThat(nonViable.status()).isEqualTo(MutationStatus.NON_VIABLE);
        assertThat(report.survivedOrUncovered()).extracting(Mutant::mutatedMethod)
                .doesNotContain("<init>");
    }

    private Mutant mutantFor(MutationReport report, String methodName) {
        return report.mutants().stream()
                .filter(mutant -> mutant.mutatedMethod().equals(methodName))
                .findFirst().orElseThrow();
    }
}
