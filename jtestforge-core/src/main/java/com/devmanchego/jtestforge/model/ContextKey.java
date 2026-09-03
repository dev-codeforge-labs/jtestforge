package com.devmanchego.jtestforge.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The cache key Spring's TestContext framework would compute for a test class —
 * jtestforge-specification.md §7.6.
 *
 * <p>Modelled so two test classes can be compared for key equality <em>before</em>
 * anything runs. Any difference between two classes forks a second
 * {@code ApplicationContext}, and a run that carelessly forks turns a single two-second
 * load into forty of them - a cost the user's CI then pays forever, with nothing pointing
 * back at the tool that caused it.
 *
 * <p>{@code mockBeanTypes} is a sorted set on purpose: Spring's key is order-insensitive
 * over the mock-bean definitions, so two classes declaring the same mocks in a different
 * order must compare equal here too, or JTestForge would report a fork that Spring does
 * not actually make.
 *
 * @param tier                 which slice annotation the class carries
 * @param configurationClasses the classes the slice annotation names
 * @param activeProfiles       {@code @ActiveProfiles} values
 * @param propertySources      {@code @TestPropertySource} locations and inlined properties
 * @param mockBeanTypes        the mocked types, sorted
 * @param webEnvironment       {@code @SpringBootTest} web environment, or {@code ""}
 * @param initializers         {@code @ContextConfiguration} initializer classes
 */
public record ContextKey(
        Tier tier,
        List<String> configurationClasses,
        List<String> activeProfiles,
        List<String> propertySources,
        Set<String> mockBeanTypes,
        String webEnvironment,
        List<String> initializers) {

    public ContextKey {
        Objects.requireNonNull(tier, "tier");
        configurationClasses = configurationClasses == null ? List.of() : List.copyOf(configurationClasses);
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        propertySources = propertySources == null ? List.of() : List.copyOf(propertySources);
        mockBeanTypes = mockBeanTypes == null
                ? Set.of()
                : java.util.Collections.unmodifiableSet(new TreeSet<>(mockBeanTypes));
        webEnvironment = webEnvironment == null ? "" : webEnvironment;
        initializers = initializers == null ? List.of() : List.copyOf(initializers);
    }
}
