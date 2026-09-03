package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.MockBeanDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** §7.6's escalation path: adding mock-bean fields to an existing Spring slice class. */
class MockBeanSynthesizerTest {

    private static final String MOCK_BEAN_ANNOTATION_FQN =
            "org.springframework.test.context.bean.override.mockito.MockitoBean";

    private final MockBeanSynthesizer synthesizer = new MockBeanSynthesizer();

    @Test
    void addsAFieldAndItsAnnotationAndTypeImports(@TempDir Path dir) throws IOException {
        Path testFile = writeTestFile(dir, """
                package com.acme.web;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
                import org.springframework.test.web.servlet.MockMvc;

                @WebMvcTest(OrderController.class)
                class OrderControllerWebTest {

                    @Autowired
                    private MockMvc mockMvc;
                }
                """);

        List<String> added = synthesizer.synthesize(testFile,
                List.of(new MockBeanDeclaration("auditLog", "com.acme.audit.AuditLog")),
                MOCK_BEAN_ANNOTATION_FQN);

        String result = Files.readString(testFile);
        assertThat(added).containsExactly("auditLog");
        assertThat(result).contains("import " + MOCK_BEAN_ANNOTATION_FQN + ";");
        assertThat(result).contains("import com.acme.audit.AuditLog;");
        assertThat(result).contains("@MockitoBean");
        assertThat(result).contains("private AuditLog auditLog;");
        // The developer's own field survives untouched.
        assertThat(result).contains("private MockMvc mockMvc;");
    }

    @Test
    void aFieldAlreadyDeclaredIsNotDuplicated(@TempDir Path dir) throws IOException {
        Path testFile = writeTestFile(dir, """
                package com.acme.web;

                @org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest(OrderController.class)
                class OrderControllerWebTest {

                    @org.springframework.test.context.bean.override.mockito.MockitoBean
                    private PaymentGateway paymentGateway;
                }
                """);
        String original = Files.readString(testFile);

        List<String> added = synthesizer.synthesize(testFile,
                List.of(new MockBeanDeclaration("paymentGateway", "com.acme.PaymentGateway")),
                MOCK_BEAN_ANNOTATION_FQN);

        assertThat(added).isEmpty();
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void anAlreadyImportedAnnotationIsNotImportedTwice(@TempDir Path dir) throws IOException {
        Path testFile = writeTestFile(dir, """
                package com.acme.web;

                import org.springframework.test.context.bean.override.mockito.MockitoBean;

                @org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest(OrderController.class)
                class OrderControllerWebTest {

                    @MockitoBean
                    private PaymentGateway paymentGateway;
                }
                """);

        synthesizer.synthesize(testFile,
                List.of(new MockBeanDeclaration("auditLog", "com.acme.audit.AuditLog")),
                MOCK_BEAN_ANNOTATION_FQN);

        String result = Files.readString(testFile);
        assertThat(result.split("import " + java.util.regex.Pattern.quote(MOCK_BEAN_ANNOTATION_FQN) + ";", -1))
                .hasSize(2);
    }

    @Test
    void multipleMissingBeansAreAllAdded(@TempDir Path dir) throws IOException {
        Path testFile = writeTestFile(dir, """
                package com.acme.web;

                @org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest(OrderController.class)
                class OrderControllerWebTest {
                }
                """);

        List<String> added = synthesizer.synthesize(testFile,
                List.of(new MockBeanDeclaration("auditLog", "com.acme.audit.AuditLog"),
                        new MockBeanDeclaration("paymentGateway", "com.acme.PaymentGateway")),
                MOCK_BEAN_ANNOTATION_FQN);

        assertThat(added).containsExactlyInAnyOrder("auditLog", "paymentGateway");
        String result = Files.readString(testFile);
        assertThat(result).contains("private AuditLog auditLog;").contains("private PaymentGateway paymentGateway;");
    }

    @Test
    void aNonExistentTestFileYieldsNoAdditions(@TempDir Path dir) {
        Path missing = dir.resolve("DoesNotExist.java");

        List<String> added = synthesizer.synthesize(missing,
                List.of(new MockBeanDeclaration("auditLog", "com.acme.audit.AuditLog")),
                MOCK_BEAN_ANNOTATION_FQN);

        assertThat(added).isEmpty();
    }

    private Path writeTestFile(Path dir, String content) throws IOException {
        Path testFile = dir.resolve("OrderControllerWebTest.java");
        Files.writeString(testFile, content);
        return testFile;
    }
}
