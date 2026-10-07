package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.AssertionLibrary;
import com.devmanchego.jtestforge.model.InjectionStyle;
import com.devmanchego.jtestforge.model.MockField;
import com.devmanchego.jtestforge.model.TestClassInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestClassScannerTest {

    private final TestClassScanner scanner = new TestClassScanner();

    @Test
    void recordsEveryTestMethodNameIncludingParameterizedAndNestedOnes(@TempDir Path dir) throws IOException {
        TestClassInfo info = scan(dir, """
                package com.acme;

                import org.junit.jupiter.api.Nested;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;

                class PaymentServiceTest {

                    @Test
                    void plainTest() {}

                    @ParameterizedTest
                    void parameterizedTest(int value) {}

                    void notATestAtAll() {}

                    @Nested
                    class Inner {
                        @Test
                        void nestedTest() {}
                    }
                }
                """);

        // Nested tests count: a generated method with that name would still collide.
        assertThat(info.testMethodNames())
                .containsExactlyInAnyOrder("plainTest", "parameterizedTest", "nestedTest");
    }

    @Test
    void recordsMockFieldsWithTheirTypeAndAnnotation(@TempDir Path dir) throws IOException {
        TestClassInfo info = scan(dir, """
                package com.acme;

                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.Spy;

                class PaymentServiceTest {

                    @Mock
                    private PaymentGateway gateway;

                    @Spy
                    private Clock clock;

                    @InjectMocks
                    private PaymentService subject;

                    private String notAMock;
                }
                """);

        assertThat(info.mockFields())
                .extracting(MockField::name, MockField::typeName, MockField::annotationName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("gateway", "PaymentGateway", "Mock"),
                        org.assertj.core.groups.Tuple.tuple("clock", "Clock", "Spy"));
    }

    @Test
    void detectsInjectMocksAsTheInjectionStyle(@TempDir Path dir) throws IOException {
        TestClassInfo info = scan(dir, """
                package com.acme;

                import org.mockito.InjectMocks;

                class PaymentServiceTest {
                    @InjectMocks
                    private PaymentService subject;
                }
                """);

        assertThat(info.injectionStyle()).isEqualTo(InjectionStyle.INJECT_MOCKS);
    }

    @Test
    void detectsSpringMockBeansAsTheInjectionStyle(@TempDir Path dir) throws IOException {
        TestClassInfo info = scan(dir, """
                package com.acme;

                import org.springframework.test.context.bean.override.mockito.MockitoBean;

                class OrderControllerWebTest {
                    @MockitoBean
                    private OrderService orderService;
                }
                """);

        assertThat(info.injectionStyle()).isEqualTo(InjectionStyle.SPRING_MOCK_BEANS);
        assertThat(info.springMockBeanNames()).containsExactly("orderService");
    }

    @Test
    void detectsAssertJAsTheAssertionLibraryInUse(@TempDir Path dir) throws IOException {
        TestClassInfo info = scan(dir, """
                package com.acme;

                import org.junit.jupiter.api.Test;

                import static org.assertj.core.api.Assertions.assertThat;

                class PaymentServiceTest {
                    @Test
                    void t() { assertThat(1).isEqualTo(1); }
                }
                """);

        assertThat(info.assertionLibrary()).isEqualTo(AssertionLibrary.ASSERTJ);
    }

    @Test
    void detectsAFileUsingBothLibrariesAsMixed(@TempDir Path dir) throws IOException {
        TestClassInfo info = scan(dir, """
                package com.acme;

                import static org.assertj.core.api.Assertions.assertThat;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class PaymentServiceTest {
                }
                """);

        assertThat(info.assertionLibrary()).isEqualTo(AssertionLibrary.MIXED);
    }

    @Test
    void recordsClassLevelAnnotationsSoASliceClassIsIdentifiableByShapeNotName(@TempDir Path dir)
            throws IOException {
        TestClassInfo info = scan(dir, """
                package com.acme;

                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

                @WebMvcTest(OrderController.class)
                class SomeNameNobodyWouldGuess {
                }
                """);

        assertThat(info.hasClassAnnotation(
                "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest")).isTrue();
        assertThat(info.className()).isEqualTo("SomeNameNobodyWouldGuess");
    }

    @Test
    void scanningAMissingFileYieldsEmptyRatherThanFailing(@TempDir Path dir) {
        assertThat(scanner.scan(dir.resolve("Nope.java"))).isEmpty();
    }

    @Test
    void implementsTheResumeReconcilersTestFileInspectorPort(@TempDir Path dir) throws IOException {
        // Phase 2 defined this port with a recorded answer; this is the real one.
        Path testFile = writeFile(dir, """
                package com.acme;

                import org.junit.jupiter.api.Test;

                class PaymentServiceTest {
                    @Test
                    void applyFee_roundsHalfUp() {}
                }
                """);

        assertThat(scanner.testMethodNames(testFile)).containsExactly("applyFee_roundsHalfUp");
        assertThat(scanner.testMethodNames(dir.resolve("Gone.java"))).isEmpty();
    }

    private TestClassInfo scan(Path dir, String source) throws IOException {
        return scanner.scan(writeFile(dir, source)).orElseThrow();
    }

    private Path writeFile(Path dir, String source) throws IOException {
        Path file = dir.resolve("SubjectTest.java");
        Files.writeString(file, source);
        return file;
    }

    // --- inspect(): absent, parsed, unreadable --------------------------------------------

    @Test
    void aMissingFileIsAbsent(@TempDir Path dir) {
        assertThat(scanner.inspect(dir.resolve("NoSuchTest.java"))).isInstanceOf(TestFileScan.Absent.class);
    }

    @Test
    void aFileWithOnlyWhitespaceIsAbsentSinceThereIsNothingInItToLose(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("EmptyTest.java");
        Files.writeString(file, "  \n\n ");

        assertThat(scanner.inspect(file)).isInstanceOf(TestFileScan.Absent.class);
    }

    @Test
    void aReadableTestClassIsParsed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("PaymentServiceTest.java");
        Files.writeString(file, """
                package com.acme;
                class PaymentServiceTest {
                    @org.junit.jupiter.api.Test
                    void t() {}
                }
                """);

        assertThat(scanner.inspect(file)).isInstanceOfSatisfying(TestFileScan.Parsed.class, parsed ->
                assertThat(parsed.info().testMethodNames()).containsExactly("t"));
    }

    @Test
    void aFileThatDoesNotParseIsUnreadableNotAbsentAndScanDoesNotPretendOtherwise(@TempDir Path dir)
            throws IOException {
        // The regression this exists for: an unparseable file used to look exactly like a
        // missing one, and the generation loop then wrote a skeleton over it.
        Path file = dir.resolve("BrokenTest.java");
        Files.writeString(file, """
                package com.acme;
                class BrokenTest {
                    @org.junit.jupiter.api.Test
                    void t() { this is not java }
                }
                """);

        assertThat(scanner.inspect(file)).isInstanceOfSatisfying(TestFileScan.Unreadable.class, unreadable ->
                assertThat(unreadable.reason()).containsIgnoringCase("parse"));
        assertThat(scanner.scan(file)).isEmpty();
    }

    @Test
    void aFileInAnotherEncodingIsUnreadableWithAReasonThatSaysSo(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("LegacyTest.java");
        // "// Año" in ISO-8859-1: the 0xF1 byte is not valid UTF-8.
        Files.write(file, "// Año\nclass LegacyTest {}\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        assertThat(scanner.inspect(file)).isInstanceOfSatisfying(TestFileScan.Unreadable.class, unreadable ->
                assertThat(unreadable.reason()).contains("UTF-8"));
    }

    @Test
    void aFileWithNoTopLevelClassIsUnreadable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("NotAClassTest.java");
        Files.writeString(file, "package com.acme;\nenum NotAClassTest { A }\n");

        assertThat(scanner.inspect(file)).isInstanceOf(TestFileScan.Unreadable.class);
    }
}
