package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.config.ContextConfig;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.TestCandidate;
import com.devmanchego.jtestforge.prompt.ContextAssembler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A legacy module saved as ISO-8859-1 (or windows-1252) with accents in its comments and
 * strings is not valid UTF-8. Everything JTestForge reads or writes has to round-trip byte for
 * byte in the module's own encoding: before the encoding was detected, such a file was either
 * refused outright or - worse - rewritten with its accents replaced.
 *
 * <p>Accented characters are written as {@code \}{@code u} escapes so this test does not
 * itself depend on the encoding of its own source file.
 */
class SourceEncodingRoundTripTest {

    private static final Charset LATIN_1 = StandardCharsets.ISO_8859_1;

    /** "Año" - the byte 0xF1 that is not valid UTF-8 on its own. */
    private static final String TEST_CLASS = """
            package com.acme;

            import org.junit.jupiter.api.Test;

            // Año fiscal: la comparación de importes
            class PaymentServiceTest {

                @Test
                void anExistingTest() {
                    assertEquals("año", "año");
                }
            }
            """;

    @Test
    void aLatin1TestClassIsRefusedByAnUtf8MergerAndLeftByteForByteUntouched(@TempDir Path dir) throws IOException {
        Path testFile = writeLatin1(dir, "PaymentServiceTest.java", TEST_CLASS);
        byte[] original = Files.readAllBytes(testFile);

        MergeResult result = new TestClassMerger(StandardCharsets.UTF_8).merge(testFile, List.of(candidate("newTest")));

        assertThat(result.isRejected()).isTrue();
        assertThat(Files.readAllBytes(testFile)).isEqualTo(original);
    }

    @Test
    void aLatin1TestClassMergesAndRevertsBackToTheExactOriginalBytes(@TempDir Path dir) throws IOException {
        Path testFile = writeLatin1(dir, "PaymentServiceTest.java", TEST_CLASS);
        byte[] original = Files.readAllBytes(testFile);
        TestCandidate withAccent = new TestCandidate("newTest", """
                @Test
                void newTest() {
                    assertEquals("compración", "compración");
                }
                """, List.of("java.time.Clock"));

        MergeResult merged = new TestClassMerger(LATIN_1).merge(testFile, List.of(withAccent));

        assertThat(merged.isRejected()).isFalse();
        byte[] afterMerge = Files.readAllBytes(testFile);
        assertThat(new String(afterMerge, LATIN_1)).contains("void newTest()").contains("compración")
                .contains("Año fiscal");
        // written as the single Latin-1 byte, not as the two bytes UTF-8 would use
        assertThat(contains(afterMerge, new byte[] {'c', 'i', (byte) 0xF3, 'n'})).isTrue();
        assertThat(contains(afterMerge, new byte[] {(byte) 0xC3, (byte) 0xB3})).isFalse();

        new TestClassReverter(LATIN_1).revert(testFile, merged.addedTestNames(), merged.addedImports());

        assertThat(Files.readAllBytes(testFile)).isEqualTo(original);
    }

    @Test
    void aCandidateWithACharacterTheEncodingCannotRepresentIsRefusedInsteadOfCorrupted(@TempDir Path dir)
            throws IOException {
        // The euro sign is in windows-1252 but not in ISO-8859-1.
        Path testFile = writeLatin1(dir, "PaymentServiceTest.java", TEST_CLASS);
        byte[] original = Files.readAllBytes(testFile);
        TestCandidate euro = new TestCandidate("newTest", """
                @Test
                void newTest() {
                    assertEquals("5 €", "5 €");
                }
                """, List.of());

        MergeResult result = new TestClassMerger(LATIN_1).merge(testFile, List.of(euro));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectionReason()).contains("ISO-8859-1").contains("cannot represent");
        assertThat(Files.readAllBytes(testFile)).isEqualTo(original);

        // ...and the same candidate is perfectly fine in an encoding that has the character.
        Path windows = writeBytes(dir, "Windows1252Test.java",
                TEST_CLASS.replace("PaymentServiceTest", "Windows1252Test").getBytes(Charset.forName("windows-1252")));
        assertThat(new TestClassMerger(Charset.forName("windows-1252")).merge(windows, List.of(euro)).isRejected())
                .isFalse();
    }

    @Test
    void theScannerReadsALatin1TestClassOnceItKnowsTheEncoding(@TempDir Path dir) throws IOException {
        Path testFile = writeLatin1(dir, "PaymentServiceTest.java", TEST_CLASS);

        assertThat(new TestClassScanner(LATIN_1).inspect(testFile)).isInstanceOf(TestFileScan.Parsed.class);
        assertThat(new TestClassScanner(StandardCharsets.UTF_8).inspect(testFile))
                .isInstanceOfSatisfying(TestFileScan.Unreadable.class,
                        unreadable -> assertThat(unreadable.reason()).contains("UTF-8"));
    }

    @Test
    void theProductionScannerDecodesIdentifiersInTheModulesEncoding(@TempDir Path dir) throws IOException {
        // An accented method name is legal Java; decoded as UTF-8 it becomes U+FFFD, which is
        // not a legal identifier character - so the whole scan used to fail on such a module.
        Path root = dir.resolve("src/main/java");
        writeLatin1(root, "com/acme/Calculadora.java", """
                package com.acme;

                public class Calculadora {
                    public int calculaAño(int base) {
                        return base + 1;
                    }
                }
                """);

        List<ProductionClass> latin1 = new ProductionClassScanner(
                ProductionTypeSolvers.forModule(root, List.of(), LATIN_1), root, 0, LATIN_1).scan();

        assertThat(latin1.get(0).methods()).extracting(method -> method.name()).containsExactly("calculaAño");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProductionClassScanner(
                        ProductionTypeSolvers.forModule(root, List.of()), root, 0).scan())
                .isInstanceOf(ProductionScanException.class);
    }

    @Test
    void thePromptShowsTheProductionSourceInTheModulesEncodingInsteadOfSourceUnavailable(@TempDir Path dir)
            throws IOException {
        Path source = writeLatin1(dir, "Calculadora.java", """
                package com.acme;
                // Año fiscal
                public class Calculadora {}
                """);
        ProductionClass productionClass = new ProductionClass("com.acme.Calculadora", source,
                List.of(), List.of(), List.of(), List.of());
        ContextConfig config = new ContextConfig(null, null, null, null, null, null, null);

        assertThat(new ContextAssembler(config, LATIN_1).classSource(productionClass)).contains("Año fiscal");
        assertThat(new ContextAssembler(config).classSource(productionClass)).contains("source unavailable");
    }

    private static TestCandidate candidate(String name) {
        return new TestCandidate(name, "@Test\nvoid " + name + "() { assertEquals(1, 1); }\n", List.of());
    }

    private static Path writeLatin1(Path dir, String relativePath, String content) throws IOException {
        return writeBytes(dir, relativePath, content.getBytes(LATIN_1));
    }

    private static Path writeBytes(Path dir, String relativePath, byte[] bytes) throws IOException {
        Path file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return file;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
