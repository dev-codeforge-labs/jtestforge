package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.TestCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The safety property this whole phase exists for: a merge followed by its revert must
 * leave the developer's file exactly as it was found.
 *
 * <p>Byte-identity is the assertion, not "semantically equivalent". JTestForge writes into
 * files a developer already owns; reformatting their comments, blank lines or import order
 * as a side effect of a generation attempt that was then discarded would be a change they
 * never asked for and would have to review.
 */
class TestClassMergeRoundTripTest {

    private final TestClassMerger merger = new TestClassMerger();
    private final TestClassReverter reverter = new TestClassReverter();

    private static final String HAND_WRITTEN_TEST_CLASS = """
            package com.acme;

            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.Nested;
            import org.junit.jupiter.api.Test;
            import org.mockito.Mock;

            import java.util.List;

            /**
             * Hand-written by a developer, with comments they care about.
             */
            class PaymentServiceTest {

                @Mock
                private PaymentGateway gateway;

                private PaymentService subject;

                @BeforeEach
                void setUp() {
                    subject = new PaymentService(gateway, null);
                }

                // This blank-line spacing and comment are deliberate.

                @Test
                void appliesTheFlatFeeBelowTheThreshold() {
                    List<String> unused = List.of();
                    assertEquals(1, 1);
                }

                @Nested
                class WhenTheAmountIsNegative {

                    @Test
                    void rejectsIt() {
                        assertEquals(2, 2);
                    }
                }
            }
            """;

    @Test
    void mergingThenRevertingLeavesTheFileByteIdentical(@TempDir Path dir) throws IOException {
        Path testFile = write(dir, HAND_WRITTEN_TEST_CLASS);
        String original = Files.readString(testFile);

        MergeResult merged = merger.merge(testFile, List.of(candidate("appliesThePercentageFeeAboveTheThreshold",
                """
                @Test
                void appliesThePercentageFeeAboveTheThreshold() {
                    assertEquals(3, 3);
                }
                """,
                List.of("java.math.RoundingMode"))));

        assertThat(merged.isRejected()).isFalse();
        assertThat(Files.readString(testFile)).isNotEqualTo(original);

        reverter.revert(testFile, merged.addedTestNames(), merged.addedImports());

        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void revertingOneUnitLeavesAnEarlierUnitsWorkOnTheSameFileIntact(@TempDir Path dir) throws IOException {
        // §9's revert semantics: reverting by AST subtraction rather than by restoring a
        // backup is what makes this possible. Restoring the backup would discard the
        // first unit's successful work along with the second unit's failed attempt.
        Path testFile = write(dir, HAND_WRITTEN_TEST_CLASS);

        MergeResult first = merger.merge(testFile, List.of(candidate("firstUnitTest", """
                @Test
                void firstUnitTest() {
                    assertEquals(1, 1);
                }
                """, List.of("java.time.Clock"))));
        String afterFirstUnit = Files.readString(testFile);

        MergeResult second = merger.merge(testFile, List.of(candidate("secondUnitTest", """
                @Test
                void secondUnitTest() {
                    assertEquals(2, 2);
                }
                """, List.of("java.time.Duration"))));

        reverter.revert(testFile, second.addedTestNames(), second.addedImports());

        assertThat(Files.readString(testFile)).isEqualTo(afterFirstUnit);
        assertThat(first.addedTestNames()).containsExactly("firstUnitTest");
    }

    @Test
    void anImportTheDeveloperLeftUnusedIsNeverPrunedByARevert(@TempDir Path dir) throws IOException {
        // Only imports the unit itself added may be removed. Pruning every unreferenced
        // import would silently "tidy" a file the unit was never asked to change.
        Path testFile = write(dir, """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import java.util.concurrent.atomic.AtomicLong;

                class PaymentServiceTest {

                    @Test
                    void anExistingTest() {
                        assertEquals(1, 1);
                    }
                }
                """);
        String original = Files.readString(testFile);

        MergeResult merged = merger.merge(testFile, List.of(candidate("addedTest", """
                @Test
                void addedTest() {
                    assertEquals(2, 2);
                }
                """, List.of("java.time.Clock"))));
        reverter.revert(testFile, merged.addedTestNames(), merged.addedImports());

        assertThat(Files.readString(testFile)).isEqualTo(original);
        assertThat(Files.readString(testFile)).contains("import java.util.concurrent.atomic.AtomicLong;");
    }

    @Test
    void anImportStillNeededByHandWrittenCodeSurvivesARevertThatAlsoAddedIt(@TempDir Path dir)
            throws IOException {
        // The unit "added" an import the file already had. Removing it on revert would
        // break the developer's own test.
        Path testFile = write(dir, """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import java.util.List;

                class PaymentServiceTest {

                    @Test
                    void anExistingTestUsingList() {
                        List<String> values = List.of("a");
                        assertEquals(1, values.size());
                    }
                }
                """);
        String original = Files.readString(testFile);

        MergeResult merged = merger.merge(testFile, List.of(candidate("addedTestAlsoUsingList", """
                @Test
                void addedTestAlsoUsingList() {
                    List<String> values = List.of("b");
                    assertEquals(1, values.size());
                }
                """, List.of("java.util.List"))));
        reverter.revert(testFile, merged.addedTestNames(), merged.addedImports());

        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    private TestCandidate candidate(String name, String source, List<String> imports) {
        return new TestCandidate(name, source, imports);
    }

    private Path write(Path dir, String source) throws IOException {
        Path file = dir.resolve("PaymentServiceTest.java");
        Files.writeString(file, source);
        return file;
    }
}
