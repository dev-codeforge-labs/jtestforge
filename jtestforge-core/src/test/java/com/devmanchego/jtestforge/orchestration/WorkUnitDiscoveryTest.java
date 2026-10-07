package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.TestClassLocator;
import com.devmanchego.jtestforge.analysis.TestClassScanner;
import com.devmanchego.jtestforge.config.ContextConfig;
import com.devmanchego.jtestforge.config.ExecutionConfig;
import com.devmanchego.jtestforge.config.GenerateConfig;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.config.ProjectConfig;
import com.devmanchego.jtestforge.config.SelectionConfig;
import com.devmanchego.jtestforge.config.SpringConfig;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.ProductionParameter;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkUnitDiscovery} against a real test source tree - the decision "does this unit's
 * test class exist?" is the one that, made wrongly, used to overwrite a developer's tests.
 */
class WorkUnitDiscoveryTest {

    @TempDir
    Path moduleDir;

    private Path testSourceRoot;
    private ProductionClass pricingRules;

    @BeforeEach
    void setUp() throws IOException {
        testSourceRoot = moduleDir.resolve("src/test/java");
        Path sourceFile = moduleDir.resolve("src/main/java/com/acme/PricingRules.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.acme;
                public class PricingRules {
                    public int band(int amount) {
                        if (amount < 0) { throw new IllegalArgumentException(); }
                        return amount > 100_000 ? 3 : 1;
                    }
                }
                """);
        ProductionMethod band = new ProductionMethod("band", "int",
                List.of(new ProductionParameter("amount", "int", List.of())),
                Visibility.PUBLIC, false, List.of(), Map.of(), List.of(), 3, 6, 3);
        pricingRules = new ProductionClass("com.acme.PricingRules", sourceFile,
                List.of(), List.of(), List.of(), List.of(band));
    }

    @Test
    void aTestClassThatCannotBeParsedIsSkippedWithItsReasonAndLeftByteForByteUntouched() throws IOException {
        String brokenSource = """
                package com.acme;

                class PricingRulesTest {
                    @org.junit.jupiter.api.Test
                    void bandsTheSmallestAmount() { this does not parse }
                }
                """;
        Path testFile = writeTestClass("com/acme/PricingRulesTest.java", brokenSource);

        List<DiscoveredUnit> units = discover();

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.workUnit().status()).isEqualTo(UnitStatus.SKIPPED_TEST_FILE_UNREADABLE);
            assertThat(unit.workUnit().skipReason()).contains("PricingRulesTest.java").containsIgnoringCase("parse");
            assertThat(unit.testFile()).isEqualTo(testFile);
        });
        assertThat(Files.readString(testFile)).isEqualTo(brokenSource);
    }

    @Test
    void aTestClassInAnotherEncodingIsSkippedRatherThanTreatedAsMissing() throws IOException {
        Path testFile = testSourceRoot.resolve("com/acme/PricingRulesTest.java");
        Files.createDirectories(testFile.getParent());
        byte[] latin1 = "package com.acme;\n// Año\nclass PricingRulesTest {}\n"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(testFile, latin1);

        List<DiscoveredUnit> units = discover();

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.workUnit().status()).isEqualTo(UnitStatus.SKIPPED_TEST_FILE_UNREADABLE);
            assertThat(unit.workUnit().skipReason()).contains("UTF-8");
        });
        assertThat(Files.readAllBytes(testFile)).isEqualTo(latin1);
    }

    @Test
    void aTestClassInTheModulesDeclaredEncodingIsAnOrdinaryPendingUnitNotASkippedOne() throws IOException {
        Path testFile = testSourceRoot.resolve("com/acme/PricingRulesTest.java");
        Files.createDirectories(testFile.getParent());
        byte[] latin1 = "package com.acme;\n// A\u00f1o\nclass PricingRulesTest {}\n"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(testFile, latin1);
        TestClassLocator locator = new TestClassLocator(
                new TestClassScanner(java.nio.charset.StandardCharsets.ISO_8859_1), testSourceRoot, "Test", Map.of());

        List<DiscoveredUnit> units = new WorkUnitDiscovery(config(), locator).discover(new DiscoveryInputs(
                List.of(pricingRules), Map.of(), SpringTierState.springDisabled(40), null, null));

        assertThat(units).singleElement()
                .satisfies(unit -> assertThat(unit.workUnit().status()).isEqualTo(UnitStatus.PENDING));
    }

    @Test
    void aMissingTestClassStillYieldsAnOrdinaryPendingUnit() {
        List<DiscoveredUnit> units = discover();

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.workUnit().status()).isEqualTo(UnitStatus.PENDING);
            assertThat(unit.testFile()).isEqualTo(testSourceRoot.resolve("com/acme/PricingRulesTest.java"));
        });
    }

    @Test
    void aReadableTestClassStillYieldsAnOrdinaryPendingUnit() throws IOException {
        writeTestClass("com/acme/PricingRulesTest.java", """
                package com.acme;
                class PricingRulesTest {
                    @org.junit.jupiter.api.Test
                    void bandsTheSmallestAmount() {}
                }
                """);

        assertThat(discover()).singleElement()
                .satisfies(unit -> assertThat(unit.workUnit().status()).isEqualTo(UnitStatus.PENDING));
    }

    private List<DiscoveredUnit> discover() {
        TestClassLocator locator = new TestClassLocator(new TestClassScanner(), testSourceRoot, "Test", Map.of());
        return new WorkUnitDiscovery(config(), locator).discover(new DiscoveryInputs(
                List.of(pricingRules), Map.of(), SpringTierState.springDisabled(40), null, null));
    }

    private Path writeTestClass(String relativePath, String source) throws IOException {
        Path file = testSourceRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private JTestForgeConfig config() {
        return new JTestForgeConfig(
                new ProjectConfig(moduleDir.toString(), "mvn", List.of(), null, null, null, null, null, null, null, null),
                new SelectionConfig(null, null, null, null, null, null, null),
                null, null,
                new SpringConfig(null, null, null, null, null, null, null, null, null, null, null),
                new ContextConfig(null, null, null, null, null, null, null),
                new GenerateConfig(null, null, null, null, null),
                null,
                new ExecutionConfig(moduleDir.resolve(".jtestforge").toString(), null, null, null, null));
    }
}
