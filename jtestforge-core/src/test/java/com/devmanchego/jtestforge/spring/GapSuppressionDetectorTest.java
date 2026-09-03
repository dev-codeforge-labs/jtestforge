package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.AssertionShape;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.SemanticGapKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GapSuppressionDetectorTest {

    private final GapSuppressionDetector detector = new GapSuppressionDetector();

    @Test
    void aHandWrittenWebSliceTestAssertingTheMappingSuppressesExactlyThatOneGap(@TempDir Path dir)
            throws IOException {
        Path testFile = writeTestFile(dir, """
                package com.acme.web;

                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
                import org.springframework.test.web.servlet.MockMvc;

                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                @WebMvcTest(OrderController.class)
                class OrderControllerWebTest {

                    @Autowired
                    private MockMvc mockMvc;

                    @Test
                    void returnsTheOrderForAKnownId() throws Exception {
                        mockMvc.perform(get("/api/orders/42"))
                                .andExpect(status().isOk())
                                .andExpect(content().string("order-42"));
                    }
                }
                """);

        List<SemanticGap> remaining = detector.removeSuppressed(
                List.of(mappingGap("findById", "GET", "/api/orders/{id}"),
                        mappingGap("audit", "GET", "/api/orders/{id}/audit"),
                        validationGap("create"),
                        securityGap("audit")),
                List.of(testFile));

        assertThat(remaining).extracting(SemanticGap::methodName, SemanticGap::kind)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("audit", SemanticGapKind.REQUEST_MAPPING),
                        org.assertj.core.groups.Tuple.tuple("create", SemanticGapKind.BEAN_VALIDATION),
                        org.assertj.core.groups.Tuple.tuple("audit", SemanticGapKind.METHOD_SECURITY));
    }

    @Test
    void aLiteralRequestPathIsMatchedAgainstTheMappingTemplateItSatisfies(@TempDir Path dir)
            throws IOException {
        // The test requests /api/orders/42; the mapping is /api/orders/{id}. Matching
        // must understand the template, or every path-variable mapping would look
        // permanently unverified no matter how thoroughly it was tested.
        Path testFile = writeMockMvcTest(dir, "get", "/api/orders/42");

        List<SemanticGap> remaining = detector.removeSuppressed(
                List.of(mappingGap("findById", "GET", "/api/orders/{id}")), List.of(testFile));

        assertThat(remaining).isEmpty();
    }

    @Test
    void aRequestToADifferentPathDoesNotSuppressTheGap(@TempDir Path dir) throws IOException {
        Path testFile = writeMockMvcTest(dir, "get", "/api/customers/42");

        List<SemanticGap> remaining = detector.removeSuppressed(
                List.of(mappingGap("findById", "GET", "/api/orders/{id}")), List.of(testFile));

        assertThat(remaining).hasSize(1);
    }

    @Test
    void aRequestWithTheWrongHttpMethodDoesNotSuppressTheGap(@TempDir Path dir) throws IOException {
        Path testFile = writeMockMvcTest(dir, "post", "/api/orders/42");

        List<SemanticGap> remaining = detector.removeSuppressed(
                List.of(mappingGap("findById", "GET", "/api/orders/{id}")), List.of(testFile));

        assertThat(remaining).hasSize(1);
    }

    @Test
    void suppressionIsDecidedByAssertionShapeNotByTestMethodName(@TempDir Path dir) throws IOException {
        // A test named exactly after the gap, asserting nothing about the mapping, must
        // not suppress it. Names are a convention the target project may not follow.
        Path testFile = writeTestFile(dir, """
                package com.acme.web;

                import org.junit.jupiter.api.Test;

                class OrderControllerTest {

                    @Test
                    void findById_requestMapping_isVerified() {
                        OrderController controller = new OrderController();
                        controller.findById(42L, null);
                    }
                }
                """);

        List<SemanticGap> remaining = detector.removeSuppressed(
                List.of(mappingGap("findById", "GET", "/api/orders/{id}")), List.of(testFile));

        assertThat(remaining).hasSize(1);
    }

    @Test
    void aValidationGapNeedsBothAStatusAndABodyAssertionToBeSuppressed(@TempDir Path dir)
            throws IOException {
        Path statusOnly = writeTestFile(dir, """
                package com.acme.web;

                import org.junit.jupiter.api.Test;
                import org.springframework.test.web.servlet.MockMvc;

                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                class OrderControllerWebTest {
                    private MockMvc mockMvc;

                    @Test
                    void rejectsBlankReference() throws Exception {
                        mockMvc.perform(post("/api/orders").content("{}"))
                                .andExpect(status().isBadRequest());
                    }
                }
                """);

        List<SemanticGap> remaining = detector.removeSuppressed(
                List.of(validationGap("create")), List.of(statusOnly));

        // Asserting only the 400 proves the request was rejected, not which constraint
        // rejected it - the same "bare status" weakness §11.1 guard 8 exists to catch.
        assertThat(remaining).hasSize(1);
    }

    @Test
    void noTestFilesAtAllSuppressesNothing() {
        List<SemanticGap> gaps = List.of(mappingGap("findById", "GET", "/api/orders/{id}"));

        assertThat(detector.removeSuppressed(gaps, List.of())).isEqualTo(gaps);
    }

    @Test
    void aMissingTestFileIsTreatedAsNoEvidenceRatherThanFailing(@TempDir Path dir) {
        List<SemanticGap> gaps = List.of(mappingGap("findById", "GET", "/api/orders/{id}"));

        assertThat(detector.removeSuppressed(gaps, List.of(dir.resolve("Nope.java")))).isEqualTo(gaps);
    }

    @Test
    void evidenceRecordsTheShapeAndTargetItFound(@TempDir Path dir) throws IOException {
        Path testFile = writeMockMvcTest(dir, "get", "/api/orders/42");

        List<AssertionEvidence> evidence = detector.evidenceIn(testFile);

        assertThat(evidence).extracting(AssertionEvidence::shape)
                .contains(AssertionShape.MOCKMVC_REQUEST_TO_PATH);
        assertThat(evidence).extracting(AssertionEvidence::path).contains("/api/orders/42");
    }

    // --- fixtures -----------------------------------------------------------------

    private Path writeMockMvcTest(Path dir, String requestMethod, String path) throws IOException {
        return writeTestFile(dir, """
                package com.acme.web;

                import org.junit.jupiter.api.Test;
                import org.springframework.test.web.servlet.MockMvc;

                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.%s;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                class OrderControllerWebTest {
                    private MockMvc mockMvc;

                    @Test
                    void exercisesTheEndpoint() throws Exception {
                        mockMvc.perform(%s("%s"))
                                .andExpect(status().isOk())
                                .andExpect(content().string("order-42"));
                    }
                }
                """.formatted(requestMethod, requestMethod, path));
    }

    private Path writeTestFile(Path dir, String source) throws IOException {
        Path file = dir.resolve("OrderControllerWebTest.java");
        Files.writeString(file, source);
        return file;
    }

    private SemanticGap mappingGap(String methodName, String httpMethod, String path) {
        return new SemanticGap(SemanticGapKind.REQUEST_MAPPING, "com.acme.web.OrderController",
                methodName, "The mapping is not verified.", httpMethod, path, 10);
    }

    private SemanticGap validationGap(String methodName) {
        return new SemanticGap(SemanticGapKind.BEAN_VALIDATION, "com.acme.web.OrderController",
                methodName, "Constraint enforcement is not verified.", "POST", "/api/orders", 20);
    }

    private SemanticGap securityGap(String methodName) {
        return new SemanticGap(SemanticGapKind.METHOD_SECURITY, "com.acme.web.OrderController",
                methodName, "The authorisation decision is not verified.", "GET",
                "/api/orders/{id}/audit", 30);
    }
}
