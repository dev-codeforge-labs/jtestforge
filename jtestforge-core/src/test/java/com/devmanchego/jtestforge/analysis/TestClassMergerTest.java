package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.TestCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestClassMergerTest {

    private final TestClassMerger merger = new TestClassMerger();

    @Test
    void insertsTheMethodAndItsImportWithoutTouchingTheRest(@TempDir Path dir) throws IOException {
        Path testFile = write(dir, simpleTestClass());

        MergeResult result = merger.merge(testFile, List.of(candidate("newTest", """
                @Test
                void newTest() {
                    assertEquals(1, 1);
                }
                """, List.of("java.time.Clock"))));

        String merged = Files.readString(testFile);
        assertThat(result.addedTestNames()).containsExactly("newTest");
        assertThat(result.addedImports()).containsExactly("java.time.Clock");
        assertThat(merged).contains("void newTest()");
        assertThat(merged).contains("import java.time.Clock;");
        assertThat(merged).contains("void anExistingTest()");
    }

    @Test
    void newImportsLandInSortedPositionWithoutReorderingExistingOnes(@TempDir Path dir) throws IOException {
        // Re-sorting the whole import list would be a change to the developer's file that
        // nobody asked for, and would defeat the byte-identical revert.
        Path testFile = write(dir, """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import java.util.List;

                class PaymentServiceTest {

                    @Test
                    void anExistingTest() {
                        assertEquals(1, 1);
                    }
                }
                """);

        merger.merge(testFile, List.of(candidate("newTest", """
                @Test
                void newTest() {
                    assertEquals(1, 1);
                }
                """, List.of("java.time.Clock"))));

        String merged = Files.readString(testFile);
        // The pre-existing, unsorted order is preserved exactly as the developer left it.
        assertThat(merged.indexOf("import org.junit.jupiter.api.Test;"))
                .isLessThan(merged.indexOf("import java.util.List;"));
        assertThat(merged).contains("import java.time.Clock;");
    }

    @Test
    void anImportAlreadyPresentIsNotDuplicatedAndIsNotReportedAsAdded(@TempDir Path dir) throws IOException {
        Path testFile = write(dir, simpleTestClass());

        MergeResult result = merger.merge(testFile, List.of(candidate("newTest", """
                @Test
                void newTest() {
                    assertEquals(1, 1);
                }
                """, List.of("org.junit.jupiter.api.Test"))));

        String merged = Files.readString(testFile);
        assertThat(merged.split("import org\\.junit\\.jupiter\\.api\\.Test;", -1)).hasSize(2);
        assertThat(result.addedImports()).isEmpty();
    }

    @Test
    void aStaticImportTheFileAlreadyHasIsNotAddedAgain(@TempDir Path dir) throws IOException {
        // Seen in real runs: the model asks for `static org.assertj...assertThatThrownBy` and the
        // file already had `import static ...;` - compared raw, the two never matched.
        Path testFile = write(dir, """
                package com.acme;

                import org.junit.jupiter.api.Test;

                import static org.assertj.core.api.Assertions.assertThatThrownBy;

                class PaymentServiceTest {

                    @Test
                    void anExistingTest() {
                        assertThatThrownBy(() -> { }).isNull();
                    }
                }
                """);

        MergeResult result = merger.merge(testFile, List.of(candidate("newTest", """
                @Test
                void newTest() {
                    assertThatThrownBy(() -> { }).isNull();
                }
                """, List.of("static org.assertj.core.api.Assertions.assertThatThrownBy"))));

        assertThat(Files.readString(testFile).split("assertThatThrownBy;", -1)).hasSize(2);
        assertThat(result.addedImports()).isEmpty();
    }

    @Test
    void theSameStaticImportRequestedByTwoCandidatesIsAddedOnce(@TempDir Path dir) throws IOException {
        Path testFile = write(dir, simpleTestClass());
        String staticImport = "static org.assertj.core.api.Assertions.assertThatThrownBy";

        MergeResult result = merger.merge(testFile, List.of(
                candidate("firstNew", "@Test\nvoid firstNew() { assertThatThrownBy(() -> { }); }\n", List.of(staticImport)),
                candidate("secondNew", "@Test\nvoid secondNew() { assertThatThrownBy(() -> { }); }\n", List.of(staticImport))));

        assertThat(Files.readString(testFile).split("import static org\\.assertj\\.core\\.api\\.Assertions\\.assertThatThrownBy;", -1))
                .hasSize(2);
        assertThat(result.addedImports()).containsExactly(staticImport);
    }

    @Test
    void aCandidateWhoseNameCollidesWithAnExistingTestIsRejected(@TempDir Path dir) throws IOException {
        Path testFile = write(dir, simpleTestClass());
        String original = Files.readString(testFile);

        MergeResult result = merger.merge(testFile, List.of(candidate("anExistingTest", """
                @Test
                void anExistingTest() {
                    assertEquals(9, 9);
                }
                """, List.of())));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectionReason()).contains("anExistingTest");
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void aCandidateThatShadowsAMockFieldWithALocalVariableIsRejected(@TempDir Path dir) throws IOException {
        // A local `PaymentGateway gateway = mock(...)` inside a class that already has an
        // @Mock field of that name creates a second mock which is never injected into the
        // subject. The test then passes while exercising nothing - and reads as correct.
        Path testFile = write(dir, """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import org.mockito.Mock;

                class PaymentServiceTest {

                    @Mock
                    private PaymentGateway gateway;

                    @Test
                    void anExistingTest() {
                        assertEquals(1, 1);
                    }
                }
                """);
        String original = Files.readString(testFile);

        MergeResult result = merger.merge(testFile, List.of(candidate("shadowingTest", """
                @Test
                void shadowingTest() {
                    PaymentGateway gateway = mock(PaymentGateway.class);
                    assertEquals(1, 1);
                }
                """, List.of())));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectionReason()).contains("gateway");
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void nothingIsWrittenWhenAnyCandidateInTheBatchIsRejected(@TempDir Path dir) throws IOException {
        // All-or-nothing: a partially applied batch would leave the unit's bookkeeping
        // disagreeing with the file, which resume reconciliation would then have to guess at.
        Path testFile = write(dir, simpleTestClass());
        String original = Files.readString(testFile);

        MergeResult result = merger.merge(testFile, List.of(
                candidate("perfectlyFineTest", """
                        @Test
                        void perfectlyFineTest() {
                            assertEquals(1, 1);
                        }
                        """, List.of()),
                candidate("anExistingTest", """
                        @Test
                        void anExistingTest() {
                            assertEquals(9, 9);
                        }
                        """, List.of())));

        assertThat(result.isRejected()).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void twoCandidatesInOneBatchAreBothInserted(@TempDir Path dir) throws IOException {
        Path testFile = write(dir, simpleTestClass());

        MergeResult result = merger.merge(testFile, List.of(
                candidate("firstNewTest", """
                        @Test
                        void firstNewTest() {
                            assertEquals(1, 1);
                        }
                        """, List.of()),
                candidate("secondNewTest", """
                        @Test
                        void secondNewTest() {
                            assertEquals(2, 2);
                        }
                        """, List.of())));

        assertThat(result.addedTestNames()).containsExactly("firstNewTest", "secondNewTest");
        assertThat(Files.readString(testFile)).contains("firstNewTest").contains("secondNewTest");
    }

    @Test
    void mergingIntoAnAssertJFileIntroducesNoJUnitAssertionImport(@TempDir Path dir) throws IOException {
        Path testFile = write(dir, """
                package com.acme;

                import org.junit.jupiter.api.Test;

                import static org.assertj.core.api.Assertions.assertThat;

                class PaymentServiceTest {

                    @Test
                    void anExistingTest() {
                        assertThat(1).isEqualTo(1);
                    }
                }
                """);

        merger.merge(testFile, List.of(candidate("newAssertJTest", """
                @Test
                void newAssertJTest() {
                    assertThat(2).isEqualTo(2);
                }
                """, List.of())));

        String merged = Files.readString(testFile);
        assertThat(merged).doesNotContain("org.junit.jupiter.api.Assertions");
        assertThat(merged).contains("newAssertJTest");
    }

    @Test
    void aMissingTestFileIsRejectedRatherThanSilentlyCreated(@TempDir Path dir) {
        MergeResult result = merger.merge(dir.resolve("DoesNotExist.java"),
                List.of(candidate("x", "@Test void x() {}", List.of())));

        assertThat(result.isRejected()).isTrue();
    }

    // --- beforeWrite: the write-ahead hook of phase 3 ----------------------------------------

    @Test
    void beforeWriteSeesExactlyWhatWillBeAddedWhileTheFileIsStillUntouched(@TempDir Path dir) throws IOException {
        Path testFile = write(dir, simpleTestClass());
        String original = Files.readString(testFile);
        List<String> fileAtCallback = new java.util.ArrayList<>();
        List<MergeResult> announced = new java.util.ArrayList<>();

        merger.merge(testFile, List.of(candidate("newTest", """
                @Test
                void newTest() {
                    assertEquals(1, 1);
                }
                """, List.of("java.time.Clock"))), planned -> {
                    announced.add(planned);
                    fileAtCallback.add(readUnchecked(testFile));
                });

        assertThat(announced).singleElement().satisfies(planned -> {
            assertThat(planned.addedTestNames()).containsExactly("newTest");
            assertThat(planned.addedImports()).containsExactly("java.time.Clock");
        });
        assertThat(fileAtCallback).containsExactly(original);
        assertThat(Files.readString(testFile)).contains("void newTest()");
    }

    @Test
    void whenBeforeWriteThrowsNothingIsWritten(@TempDir Path dir) throws IOException {
        // An edit that could not be recorded must not happen.
        Path testFile = write(dir, simpleTestClass());
        String original = Files.readString(testFile);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> merger.merge(testFile,
                        List.of(candidate("newTest", "@Test\nvoid newTest() { assertEquals(1, 1); }\n", List.of())),
                        planned -> {
                            throw new IllegalStateException("state.json could not be written");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void beforeWriteIsNotCalledForARefusedBatch(@TempDir Path dir) throws IOException {
        Path testFile = write(dir, simpleTestClass());
        List<MergeResult> announced = new java.util.ArrayList<>();

        MergeResult result = merger.merge(testFile, List.of(candidate("anExistingTest",
                "@Test\nvoid anExistingTest() { assertEquals(2, 2); }\n", List.of())), announced::add);

        assertThat(result.isRejected()).isTrue();
        assertThat(announced).isEmpty();
    }

    private static String readUnchecked(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String simpleTestClass() {
        return """
                package com.acme;

                import org.junit.jupiter.api.Test;

                class PaymentServiceTest {

                    @Test
                    void anExistingTest() {
                        assertEquals(1, 1);
                    }
                }
                """;
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
