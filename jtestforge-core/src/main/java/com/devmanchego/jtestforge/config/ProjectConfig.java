package com.devmanchego.jtestforge.config;

import com.devmanchego.jtestforge.model.Tier;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code project} block — jtestforge-specification.md §5.
 *
 * <p>{@code modulePath} has no default: it is the one required field in this block, and
 * its absence is reported by {@link ConfigValidator} rather than silently tolerated.
 */
public record ProjectConfig(
        String modulePath,
        String mavenExecutable,
        List<String> mavenArgs,
        String javaHome,
        String testSourceRoot,
        String mainSourceRoot,
        String testClassSuffix,
        Map<Tier, String> testClassSuffixByTier) {

    private static final Map<Tier, String> DEFAULT_SUFFIX_BY_TIER = defaultSuffixByTier();

    public ProjectConfig {
        mavenExecutable = mavenExecutable == null ? "mvn" : mavenExecutable;
        mavenArgs = mavenArgs == null ? List.of("-o", "-B") : List.copyOf(mavenArgs);
        testSourceRoot = testSourceRoot == null ? "src/test/java" : testSourceRoot;
        mainSourceRoot = mainSourceRoot == null ? "src/main/java" : mainSourceRoot;
        testClassSuffix = testClassSuffix == null ? "Test" : testClassSuffix;
        testClassSuffixByTier = mergeWithDefaults(testClassSuffixByTier);
    }

    private static Map<Tier, String> mergeWithDefaults(Map<Tier, String> configured) {
        Map<Tier, String> merged = new EnumMap<>(DEFAULT_SUFFIX_BY_TIER);
        if (configured != null) {
            merged.putAll(configured);
        }
        return Map.copyOf(merged);
    }

    private static Map<Tier, String> defaultSuffixByTier() {
        Map<Tier, String> defaults = new LinkedHashMap<>();
        defaults.put(Tier.WEB_SLICE, "WebTest");
        defaults.put(Tier.DATA_SLICE, "DataTest");
        defaults.put(Tier.JSON_SLICE, "JsonTest");
        defaults.put(Tier.CONTEXT_SLICE, "ContextTest");
        return Map.copyOf(defaults);
    }
}
