package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.Tier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestClassLocatorTest {

    private static final Map<Tier, String> SUFFIX_BY_TIER = Map.of(
            Tier.WEB_SLICE, "WebTest",
            Tier.DATA_SLICE, "DataTest",
            Tier.JSON_SLICE, "JsonTest",
            Tier.CONTEXT_SLICE, "ContextTest");

    @Test
    void findsTheConventionallyNamedPlainUnitTestClass(@TempDir Path testSourceRoot) throws IOException {
        Path expected = writeTestClass(testSourceRoot, "com/acme/PaymentServiceTest.java", """
                package com.acme;
                class PaymentServiceTest {}
                """);

        assertThat(locator(testSourceRoot).locate("com.acme.PaymentService", Tier.PLAIN_UNIT))
                .contains(expected);
    }

    @Test
    void findsTheConventionallyNamedSliceTestClass(@TempDir Path testSourceRoot) throws IOException {
        Path expected = writeTestClass(testSourceRoot, "com/acme/web/OrderControllerWebTest.java", """
                package com.acme.web;
                class OrderControllerWebTest {}
                """);

        assertThat(locator(testSourceRoot).locate("com.acme.web.OrderController", Tier.WEB_SLICE))
                .contains(expected);
    }

    @Test
    void findsAnExistingSliceClassByItsAnnotationEvenWhenNamedAgainstConvention(
            @TempDir Path testSourceRoot) throws IOException {
        // §7.2: creating a second @WebMvcTest class for the same controller would double
        // that class's context loads for no benefit, so an existing one must be found by
        // shape rather than by the filename JTestForge would have chosen.
        Path unconventional = writeTestClass(testSourceRoot, "com/acme/web/OrderEndpointsIT.java", """
                package com.acme.web;

                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

                @WebMvcTest(OrderController.class)
                class OrderEndpointsIT {
                }
                """);

        assertThat(locator(testSourceRoot).locate("com.acme.web.OrderController", Tier.WEB_SLICE))
                .contains(unconventional);
    }

    @Test
    void aSliceClassForADifferentControllerIsNotMistakenForThisOne(@TempDir Path testSourceRoot)
            throws IOException {
        writeTestClass(testSourceRoot, "com/acme/web/CustomerEndpointsIT.java", """
                package com.acme.web;

                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

                @WebMvcTest(CustomerController.class)
                class CustomerEndpointsIT {
                }
                """);

        assertThat(locator(testSourceRoot).locate("com.acme.web.OrderController", Tier.WEB_SLICE))
                .isEmpty();
    }

    @Test
    void theConventionalNameWinsOverAnUnconventionalOneWhenBothExist(@TempDir Path testSourceRoot)
            throws IOException {
        Path conventional = writeTestClass(testSourceRoot, "com/acme/web/OrderControllerWebTest.java", """
                package com.acme.web;

                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

                @WebMvcTest(OrderController.class)
                class OrderControllerWebTest {
                }
                """);
        writeTestClass(testSourceRoot, "com/acme/web/OrderEndpointsIT.java", """
                package com.acme.web;

                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

                @WebMvcTest(OrderController.class)
                class OrderEndpointsIT {
                }
                """);

        assertThat(locator(testSourceRoot).locate("com.acme.web.OrderController", Tier.WEB_SLICE))
                .contains(conventional);
    }

    @Test
    void yieldsEmptyWhenNoTestClassExistsYet(@TempDir Path testSourceRoot) {
        assertThat(locator(testSourceRoot).locate("com.acme.PaymentService", Tier.PLAIN_UNIT)).isEmpty();
    }

    @Test
    void thePlainUnitTierNeverAdoptsASliceClassAsItsOwn(@TempDir Path testSourceRoot) throws IOException {
        // One test class per tier (§7.2): merging plain Mockito tests into a @WebMvcTest
        // class would run them against a loaded Spring context for no reason.
        writeTestClass(testSourceRoot, "com/acme/web/OrderControllerWebTest.java", """
                package com.acme.web;

                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

                @WebMvcTest(OrderController.class)
                class OrderControllerWebTest {
                }
                """);

        assertThat(locator(testSourceRoot).locate("com.acme.web.OrderController", Tier.PLAIN_UNIT))
                .isEmpty();
    }

    @Test
    void reportsWhereItWouldCreateAMissingTestClass(@TempDir Path testSourceRoot) {
        TestClassLocator locator = locator(testSourceRoot);

        assertThat(locator.conventionalPathFor("com.acme.PaymentService", Tier.PLAIN_UNIT))
                .isEqualTo(testSourceRoot.resolve("com/acme/PaymentServiceTest.java"));
        assertThat(locator.conventionalPathFor("com.acme.web.OrderController", Tier.WEB_SLICE))
                .isEqualTo(testSourceRoot.resolve("com/acme/web/OrderControllerWebTest.java"));
    }

    private TestClassLocator locator(Path testSourceRoot) {
        return new TestClassLocator(new TestClassScanner(), testSourceRoot, "Test", SUFFIX_BY_TIER);
    }

    private Path writeTestClass(Path testSourceRoot, String relativePath, String source) throws IOException {
        Path file = testSourceRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }
}
