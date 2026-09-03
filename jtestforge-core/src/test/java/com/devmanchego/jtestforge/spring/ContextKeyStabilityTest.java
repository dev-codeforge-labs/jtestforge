package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.analysis.TestClassScanner;
import com.devmanchego.jtestforge.model.ContextKey;
import com.devmanchego.jtestforge.model.TestCandidate;
import com.devmanchego.jtestforge.model.TestClassInfo;
import com.devmanchego.jtestforge.model.Tier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The context cache key is the single largest cost risk in Spring test generation
 * (§7.6). These tests pin the behaviours that keep it an invariant of the test class.
 */
class ContextKeyStabilityTest {

    private final TestClassScanner scanner = new TestClassScanner();
    private final ContextKeyModel keyModel = new ContextKeyModel();
    private final ContextKeyGuard guard = new ContextKeyGuard();

    // --- ContextKeyModel ----------------------------------------------------------

    @Test
    void twoSliceClassesWithTheSameShapeShareOneContextKey(@TempDir Path dir) throws IOException {
        TestClassInfo first = scan(dir, "FirstWebTest.java", webSliceClass("FirstWebTest",
                "    @MockitoBean private OrderService orderService;"));
        TestClassInfo second = scan(dir, "SecondWebTest.java", webSliceClass("SecondWebTest",
                "    @MockitoBean private OrderService orderService;"));

        assertThat(keyModel.keyOf(first, Tier.WEB_SLICE))
                .isEqualTo(keyModel.keyOf(second, Tier.WEB_SLICE));
    }

    @Test
    void theMockBeanSetIsOrderInsensitiveJustAsSpringsOwnKeyIs(@TempDir Path dir) throws IOException {
        TestClassInfo first = scan(dir, "FirstWebTest.java", webSliceClass("FirstWebTest", """
                    @MockitoBean private OrderService orderService;
                    @MockitoBean private AuditLog auditLog;"""));
        TestClassInfo second = scan(dir, "SecondWebTest.java", webSliceClass("SecondWebTest", """
                    @MockitoBean private AuditLog auditLog;
                    @MockitoBean private OrderService orderService;"""));

        // Reporting a fork Spring does not actually make would send the run chasing a
        // problem that is not there.
        assertThat(keyModel.keyOf(first, Tier.WEB_SLICE))
                .isEqualTo(keyModel.keyOf(second, Tier.WEB_SLICE));
    }

    @Test
    void anExtraMockBeanForksTheKey(@TempDir Path dir) throws IOException {
        TestClassInfo lean = scan(dir, "LeanWebTest.java", webSliceClass("LeanWebTest",
                "    @MockitoBean private OrderService orderService;"));
        TestClassInfo extra = scan(dir, "ExtraWebTest.java", webSliceClass("ExtraWebTest", """
                    @MockitoBean private OrderService orderService;
                    @MockitoBean private AuditLog auditLog;"""));

        assertThat(keyModel.keyOf(lean, Tier.WEB_SLICE))
                .isNotEqualTo(keyModel.keyOf(extra, Tier.WEB_SLICE));
    }

    @Test
    void aTestPropertySourceForksTheKey(@TempDir Path dir) throws IOException {
        TestClassInfo plain = scan(dir, "PlainWebTest.java", webSliceClass("PlainWebTest", ""));
        TestClassInfo withProperties = scan(dir, "PropertiedWebTest.java", """
                package com.acme.web;

                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
                import org.springframework.test.context.TestPropertySource;

                @WebMvcTest(OrderController.class)
                @TestPropertySource(properties = "acme.feature.enabled=true")
                class PropertiedWebTest {
                }
                """);

        assertThat(keyModel.keyOf(plain, Tier.WEB_SLICE))
                .isNotEqualTo(keyModel.keyOf(withProperties, Tier.WEB_SLICE));
    }

    @Test
    void aDifferentActiveProfileForksTheKey(@TempDir Path dir) throws IOException {
        TestClassInfo plain = scan(dir, "PlainWebTest.java", webSliceClass("PlainWebTest", ""));
        TestClassInfo profiled = scan(dir, "ProfiledWebTest.java", """
                package com.acme.web;

                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
                import org.springframework.test.context.ActiveProfiles;

                @WebMvcTest(OrderController.class)
                @ActiveProfiles("integration")
                class ProfiledWebTest {
                }
                """);

        assertThat(keyModel.keyOf(plain, Tier.WEB_SLICE))
                .isNotEqualTo(keyModel.keyOf(profiled, Tier.WEB_SLICE));
    }

    // --- ContextKeyGuard ----------------------------------------------------------

    @Test
    void aCandidateCarryingTestPropertySourceIsRefusedBeforeItIsEverWritten(@TempDir Path dir)
            throws IOException {
        TestClassInfo testClass = scan(dir, "OrderControllerWebTest.java",
                webSliceClass("OrderControllerWebTest", "    @MockitoBean private OrderService orderService;"));

        ContextKeyDecision decision = guard.evaluate(candidate("forksTheKey", """
                @Test
                @TestPropertySource(properties = "acme.feature.enabled=true")
                void forksTheKey() {
                    assertThat(1).isEqualTo(1);
                }
                """), testClass);

        assertThat(decision.outcome()).isEqualTo(ContextKeyDecision.Outcome.REJECTED_KEY_FORK);
        assertThat(decision.reason()).contains("TestPropertySource");
    }

    @Test
    void everyContextKeyForkingAnnotationIsRefused(@TempDir Path dir) throws IOException {
        TestClassInfo testClass = scan(dir, "OrderControllerWebTest.java",
                webSliceClass("OrderControllerWebTest", ""));

        List<String> forbidden = List.of("DirtiesContext", "TestPropertySource", "ActiveProfiles",
                "ContextConfiguration", "MockitoBean", "MockBean", "SpyBean", "Import");

        for (String annotation : forbidden) {
            ContextKeyDecision decision = guard.evaluate(candidate("t", """
                    @Test
                    @%s
                    void t() {
                        assertThat(1).isEqualTo(1);
                    }
                    """.formatted(annotation)), testClass);

            assertThat(decision.outcome())
                    .withFailMessage("@%s must be refused as a context-key fork", annotation)
                    .isEqualTo(ContextKeyDecision.Outcome.REJECTED_KEY_FORK);
        }
    }

    @Test
    void dirtiesContextIsRefusedBecauseItEvictsTheCacheForEveryLaterClass(@TempDir Path dir)
            throws IOException {
        // One generated occurrence degrades the whole module's suite - a cost paid by the
        // user's CI forever, in exchange for a single generated test (§7.6).
        TestClassInfo testClass = scan(dir, "OrderControllerWebTest.java",
                webSliceClass("OrderControllerWebTest", ""));

        ContextKeyDecision decision = guard.evaluate(candidate("evictsTheCache", """
                @Test
                @DirtiesContext
                void evictsTheCache() {
                    assertThat(1).isEqualTo(1);
                }
                """), testClass);

        assertThat(decision.outcome()).isEqualTo(ContextKeyDecision.Outcome.REJECTED_KEY_FORK);
    }

    @Test
    void anOrdinaryCandidateUsingOnlyDeclaredMockBeansIsAccepted(@TempDir Path dir) throws IOException {
        TestClassInfo testClass = scan(dir, "OrderControllerWebTest.java",
                webSliceClass("OrderControllerWebTest", "    @MockitoBean private OrderService orderService;"));

        ContextKeyDecision decision = guard.evaluate(candidate("stubsADeclaredMock", """
                @Test
                void stubsADeclaredMock() throws Exception {
                    when(orderService.findById(1L)).thenReturn(null);
                    mockMvc.perform(get("/api/orders/1")).andExpect(status().isOk());
                }
                """), testClass);

        assertThat(decision.isAccepted()).isTrue();
    }

    @Test
    void aCandidateStubbingAnUndeclaredBeanIsAnEscalationNotARejection(@TempDir Path dir)
            throws IOException {
        // Genuinely needing a mock bean the class does not declare is not the model
        // misbehaving - it is the class's mock-bean set being wrong. That is fixed once,
        // deliberately, rather than by letting the candidate add a per-method annotation.
        TestClassInfo testClass = scan(dir, "OrderControllerWebTest.java",
                webSliceClass("OrderControllerWebTest", "    @MockitoBean private OrderService orderService;"));

        ContextKeyDecision decision = guard.evaluate(candidate("needsAnotherMock", """
                @Test
                void needsAnotherMock() {
                    when(auditLog.record("x")).thenReturn(true);
                }
                """), testClass);

        assertThat(decision.outcome()).isEqualTo(ContextKeyDecision.Outcome.ESCALATION_REQUIRED);
        assertThat(decision.missingMockBeanTypes()).contains("auditLog");
    }

    @Test
    void aLocalVariableIsNotMistakenForAMissingMockBean(@TempDir Path dir) throws IOException {
        TestClassInfo testClass = scan(dir, "OrderControllerWebTest.java",
                webSliceClass("OrderControllerWebTest", "    @MockitoBean private OrderService orderService;"));

        ContextKeyDecision decision = guard.evaluate(candidate("usesALocal", """
                @Test
                void usesALocal() {
                    OrderService other = mock(OrderService.class);
                    when(other.findById(1L)).thenReturn(null);
                }
                """), testClass);

        assertThat(decision.isAccepted()).isTrue();
    }

    // --- escalation bookkeeping ---------------------------------------------------

    @Test
    void aClassMayEscalateItsMockBeanSetOnceAndNeverLoop() {
        MockBeanEscalation escalation = new MockBeanEscalation();

        assertThat(escalation.recordEscalation("com.acme.web.OrderControllerWebTest")).isTrue();
        assertThat(escalation.recordEscalation("com.acme.web.OrderControllerWebTest")).isFalse();
        assertThat(escalation.hasAlreadyEscalated("com.acme.web.OrderControllerWebTest")).isTrue();
        assertThat(escalation.hasAlreadyEscalated("com.acme.web.OtherWebTest")).isFalse();
    }

    // --- ContextLoadCounter -------------------------------------------------------

    @Test
    void theLoadCounterCountsDistinctKeysNotTestClassesOrMethods(@TempDir Path dir) throws IOException {
        ContextLoadCounter counter = new ContextLoadCounter(40);
        TestClassInfo first = scan(dir, "FirstWebTest.java", webSliceClass("FirstWebTest",
                "    @MockitoBean private OrderService orderService;"));
        TestClassInfo second = scan(dir, "SecondWebTest.java", webSliceClass("SecondWebTest",
                "    @MockitoBean private OrderService orderService;"));
        ContextKey sharedKey = keyModel.keyOf(first, Tier.WEB_SLICE);
        ContextKey sameKeyAgain = keyModel.keyOf(second, Tier.WEB_SLICE);

        assertThat(counter.record(sharedKey)).isTrue();
        assertThat(counter.record(sameKeyAgain)).isFalse();
        assertThat(counter.record(sharedKey)).isFalse();

        assertThat(counter.contextLoads()).isEqualTo(1);
    }

    @Test
    void theLoadCounterReportsWhenTheBudgetIsExhausted(@TempDir Path dir) throws IOException {
        ContextLoadCounter counter = new ContextLoadCounter(1);
        TestClassInfo lean = scan(dir, "LeanWebTest.java", webSliceClass("LeanWebTest", ""));
        TestClassInfo extra = scan(dir, "ExtraWebTest.java", webSliceClass("ExtraWebTest",
                "    @MockitoBean private OrderService orderService;"));

        counter.record(keyModel.keyOf(lean, Tier.WEB_SLICE));
        assertThat(counter.budgetExhausted()).isTrue();

        counter.record(keyModel.keyOf(extra, Tier.WEB_SLICE));
        assertThat(counter.contextLoads()).isEqualTo(2);
        assertThat(counter.budgetExhausted()).isTrue();
    }

    // --- fixtures -----------------------------------------------------------------

    private String webSliceClass(String className, String fields) {
        return """
                package com.acme.web;

                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
                import org.springframework.test.context.bean.override.mockito.MockitoBean;
                import org.springframework.test.web.servlet.MockMvc;

                @WebMvcTest(OrderController.class)
                class %s {

                    private MockMvc mockMvc;
                %s
                }
                """.formatted(className, fields);
    }

    private TestCandidate candidate(String name, String source) {
        return new TestCandidate(name, source, List.of());
    }

    private TestClassInfo scan(Path dir, String fileName, String source) throws IOException {
        Path file = dir.resolve(fileName);
        Files.writeString(file, source);
        return scanner.scan(file).orElseThrow();
    }
}
