package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.Collaborator;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.SemanticVersion;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.Tier;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skeletons here must compile on first generation, before any AI output is merged
 * into them.
 *
 * <p>These tests assert that each skeleton <b>parses as valid Java and names the exact
 * annotations that exist on the detected stack</b>. They do not run javac: doing so would
 * require Spring Boot Test on JTestForge's own test classpath, which it deliberately does
 * not have (the whole point of {@code SpringStackDetector} is that the target module's
 * Spring is never ours). Real compilation of these skeletons is covered by phase 18's
 * end-to-end run against a genuine Spring Boot fixture module.
 */
class SpringTestClassFactoryTest {

    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    @Test
    void theWebSliceSkeletonUsesMockitoBeanOnBootThreeFour() {
        String source = factoryFor(bootThreeFour())
                .renderSkeleton(controller(), Tier.WEB_SLICE, "OrderControllerWebTest");

        assertThatParses(source);
        assertThat(source).contains("@MockitoBean");
        assertThat(source).contains("import org.springframework.test.context.bean.override.mockito.MockitoBean;");
        assertThat(source).doesNotContain("org.springframework.boot.test.mock.mockito.MockBean");
    }

    @Test
    void theWebSliceSkeletonUsesTheOlderMockBeanBelowBootThreeFour() {
        // @MockBean was superseded by @MockitoBean in Framework 6.2 / Boot 3.4. Emitting
        // the wrong one is a guaranteed compilation failure that no repair loop should
        // have to absorb (§2).
        String source = factoryFor(bootThreeTwo())
                .renderSkeleton(controller(), Tier.WEB_SLICE, "OrderControllerWebTest");

        assertThatParses(source);
        assertThat(source).contains("@MockBean");
        assertThat(source).contains("import org.springframework.boot.test.mock.mockito.MockBean;");
        assertThat(source).doesNotContain("MockitoBean");
    }

    @Test
    void theWebSliceSkeletonDeclaresTheControllerUnderTestAndAMockBeanPerCollaborator() {
        String source = factoryFor(bootThreeFour())
                .renderSkeleton(controller(), Tier.WEB_SLICE, "OrderControllerWebTest");

        assertThat(source).contains("@WebMvcTest(OrderController.class)");
        assertThat(source).contains("private MockMvc mockMvc;");
        assertThat(source).contains("private OrderService orderService;");
        assertThat(source).contains("private AuditLog auditLog;");
        assertThat(source).contains("package com.acme.web;");
        assertThat(source).contains("class OrderControllerWebTest {");
    }

    @Test
    void theDataSliceSkeletonWiresTestEntityManagerAndTheRepository() {
        ProductionClass repository = new ProductionClass("com.acme.repo.OrderRepository",
                Path.of("OrderRepository.java"), List.of(), List.of("JpaRepository"), List.of(), List.of());

        String source = factoryFor(bootThreeFour())
                .renderSkeleton(repository, Tier.DATA_SLICE, "OrderRepositoryDataTest");

        assertThatParses(source);
        assertThat(source).contains("@DataJpaTest");
        assertThat(source).contains("private TestEntityManager entityManager;");
        assertThat(source).contains("private OrderRepository orderRepository;");
    }

    @Test
    void theJsonSliceSkeletonWiresAJacksonTesterForTheType() {
        ProductionClass properties = new ProductionClass("com.acme.config.BillingProperties",
                Path.of("BillingProperties.java"), List.of(), List.of(), List.of(), List.of());

        String source = factoryFor(bootThreeFour())
                .renderSkeleton(properties, Tier.JSON_SLICE, "BillingPropertiesJsonTest");

        assertThatParses(source);
        assertThat(source).contains("@JsonTest");
        assertThat(source).contains("JacksonTester<BillingProperties>");
    }

    @Test
    void theContextSliceSkeletonRestrictsTheContextToTheConfigurationUnderTest() {
        ProductionClass configuration = new ProductionClass("com.acme.config.CacheConfig",
                Path.of("CacheConfig.java"), List.of(), List.of(), List.of(), List.of());

        String source = factoryFor(bootThreeFour())
                .renderSkeleton(configuration, Tier.CONTEXT_SLICE, "CacheConfigContextTest");

        assertThatParses(source);
        // The smallest class set that reproduces the wiring under test (§7.4) - never a
        // bare @SpringBootTest, which would load the entire application.
        assertThat(source).contains("@SpringBootTest(classes = CacheConfig.class)");
    }

    @Test
    void aSkeletonNeverDeclaresAnythingThatWouldForkTheContextCacheKey() {
        String source = factoryFor(bootThreeFour())
                .renderSkeleton(controller(), Tier.WEB_SLICE, "OrderControllerWebTest");

        assertThat(source)
                .doesNotContain("@DirtiesContext")
                .doesNotContain("@TestPropertySource")
                .doesNotContain("@ActiveProfiles")
                .doesNotContain("webEnvironment");
    }

    @Test
    void aPlainUnitTierIsRefusedBecauseItIsNotASpringSliceAtAll() {
        assertThat(factoryFor(bootThreeFour()).supports(Tier.PLAIN_UNIT)).isFalse();
        assertThat(factoryFor(bootThreeFour()).supports(Tier.WEB_SLICE)).isTrue();
    }

    private void assertThatParses(String source) {
        assertThat(javaParser.parse(source).isSuccessful())
                .withFailMessage("generated skeleton does not parse:%n%s", source)
                .isTrue();
    }

    private SpringTestClassFactory factoryFor(SpringStackFacts facts) {
        return new SpringTestClassFactory(facts, new MockBeanSetResolver());
    }

    private ProductionClass controller() {
        return new ProductionClass("com.acme.web.OrderController", Path.of("OrderController.java"),
                List.of("org.springframework.web.bind.annotation.RestController"), List.of(),
                List.of(new Collaborator("orderService", "com.acme.OrderService"),
                        new Collaborator("auditLog", "com.acme.AuditLog")),
                List.of());
    }

    private SpringStackFacts bootThreeFour() {
        return new SpringStackFacts(true, true,
                new SemanticVersion(6, 2, 1), new SemanticVersion(3, 4, 1),
                true, true, false, SpringStackFacts.DetectedEmbeddedDatabase.H2,
                SpringStackFacts.ValidationApi.JAKARTA,
                "org.springframework.test.context.bean.override.mockito.MockitoBean", true);
    }

    private SpringStackFacts bootThreeTwo() {
        return new SpringStackFacts(true, true,
                new SemanticVersion(6, 1, 14), new SemanticVersion(3, 2, 5),
                true, true, false, SpringStackFacts.DetectedEmbeddedDatabase.H2,
                SpringStackFacts.ValidationApi.JAKARTA,
                "org.springframework.boot.test.mock.mockito.MockBean", false);
    }
}
