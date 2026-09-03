package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.TestClassInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds which file holds a production class's tests for a given tier, or where one would
 * be created — jtestforge-specification.md §7.2.
 *
 * <p>Two lookups, in order. The conventional filename first, then - for a Spring tier - a
 * scan of the package for a class carrying that tier's slice annotation and naming this
 * production class. The second lookup exists because a project may already have a
 * perfectly good {@code @WebMvcTest} under a name JTestForge would never have guessed,
 * and creating a second one for the same controller would double that class's context
 * loads for no benefit at all.
 *
 * <p>Each tier keeps its own file (§7.2). A slice class is never adopted by the plain unit
 * tier: merging plain Mockito tests into a {@code @WebMvcTest} class would run them
 * against a loaded Spring context for no reason.
 */
public final class TestClassLocator {

    private static final Map<Tier, String> SLICE_ANNOTATION_BY_TIER = Map.of(
            Tier.WEB_SLICE, "WebMvcTest",
            Tier.DATA_SLICE, "DataJpaTest",
            Tier.JSON_SLICE, "JsonTest",
            Tier.CONTEXT_SLICE, "SpringBootTest");

    private final TestClassScanner scanner;
    private final Path testSourceRoot;
    private final String plainUnitSuffix;
    private final Map<Tier, String> suffixByTier;

    public TestClassLocator(TestClassScanner scanner, Path testSourceRoot,
                            String plainUnitSuffix, Map<Tier, String> suffixByTier) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.testSourceRoot = Objects.requireNonNull(testSourceRoot, "testSourceRoot");
        this.plainUnitSuffix = Objects.requireNonNull(plainUnitSuffix, "plainUnitSuffix");
        this.suffixByTier = Map.copyOf(suffixByTier);
    }

    public Optional<Path> locate(String productionClassFqn, Tier tier) {
        Path conventional = conventionalPathFor(productionClassFqn, tier);
        if (Files.isRegularFile(conventional) && isUsableForTier(conventional, productionClassFqn, tier)) {
            return Optional.of(conventional);
        }
        return findSliceClassByAnnotation(productionClassFqn, tier);
    }

    /** Where this tier's test class would be created if none exists. */
    public Path conventionalPathFor(String productionClassFqn, Tier tier) {
        String packagePath = packageOf(productionClassFqn).replace('.', '/');
        String fileName = simpleNameOf(productionClassFqn) + suffixFor(tier) + ".java";
        return packagePath.isEmpty()
                ? testSourceRoot.resolve(fileName)
                : testSourceRoot.resolve(packagePath).resolve(fileName);
    }

    private String suffixFor(Tier tier) {
        return tier == Tier.PLAIN_UNIT ? plainUnitSuffix : suffixByTier.getOrDefault(tier, plainUnitSuffix);
    }

    /**
     * Whether the file sitting at this tier's conventional path may be used for it.
     *
     * <p>The filename already ties the file to this production class, so the only
     * disqualifying condition is that it is some <em>other</em> tier's slice class: merging
     * plain Mockito tests into a {@code @WebMvcTest} would run them against a loaded
     * context for no reason, and merging web-slice tests into a {@code @DataJpaTest} would
     * not run them in a web slice at all.
     *
     * <p>A conventionally named file carrying no slice annotation yet is accepted rather
     * than refused. Refusing it would leave the caller believing no test class exists and
     * trying to create a file that is already there.
     */
    private boolean isUsableForTier(Path candidate, String productionClassFqn, Tier tier) {
        Optional<TestClassInfo> info = scanner.scan(candidate);
        if (info.isEmpty()) {
            return false;
        }
        String wantedAnnotation = SLICE_ANNOTATION_BY_TIER.get(tier);
        return SLICE_ANNOTATION_BY_TIER.entrySet().stream()
                .filter(entry -> entry.getKey() != tier)
                .map(Map.Entry::getValue)
                .filter(otherAnnotation -> !otherAnnotation.equals(wantedAnnotation))
                .noneMatch(otherAnnotation -> info.get().hasClassAnnotation(otherAnnotation));
    }

    private Optional<Path> findSliceClassByAnnotation(String productionClassFqn, Tier tier) {
        if (tier == Tier.PLAIN_UNIT) {
            return Optional.empty();
        }
        Path packageDirectory = packageDirectoryOf(productionClassFqn);
        if (!Files.isDirectory(packageDirectory)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(packageDirectory)) {
            // Sorted so the answer does not depend on filesystem enumeration order, which
            // varies by platform and is not itself meaningful.
            List<Path> candidates = files
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path candidate : candidates) {
                Optional<TestClassInfo> info = scanner.scan(candidate);
                if (info.isPresent() && declaresSliceFor(info.get(), productionClassFqn, tier)) {
                    return Optional.of(candidate);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + packageDirectory, e);
        }
        return Optional.empty();
    }

    /**
     * Whether this test class is the slice for {@code productionClassFqn}. The annotation
     * must both be the right one for the tier and actually name this production class -
     * a {@code @WebMvcTest(CustomerController.class)} sitting in the same package is
     * somebody else's slice, not this one's.
     */
    private boolean declaresSliceFor(TestClassInfo info, String productionClassFqn, Tier tier) {
        String sliceAnnotation = SLICE_ANNOTATION_BY_TIER.get(tier);
        if (sliceAnnotation == null || !info.hasClassAnnotation(sliceAnnotation)) {
            return false;
        }
        String attributes = info.classAnnotationAttribute(sliceAnnotation);
        if (attributes == null || attributes.isBlank()) {
            // @DataJpaTest and @JsonTest routinely take no arguments at all; for those the
            // annotation plus the package is as specific as the source can be.
            return true;
        }
        return namesType(attributes, simpleNameOf(productionClassFqn));
    }

    /** Word-boundary match, so {@code Order} does not match {@code OrderController}. */
    private boolean namesType(String attributes, String simpleName) {
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(attributes).find();
    }

    private Path packageDirectoryOf(String productionClassFqn) {
        String packagePath = packageOf(productionClassFqn).replace('.', '/');
        return packagePath.isEmpty() ? testSourceRoot : testSourceRoot.resolve(packagePath);
    }

    private String packageOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? "" : fqn.substring(0, lastDot);
    }

    private String simpleNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }
}
