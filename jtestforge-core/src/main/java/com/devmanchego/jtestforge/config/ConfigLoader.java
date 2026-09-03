package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads {@code jtestforge.yaml} into a fully-bound, fully-defaulted
 * {@link JTestForgeConfig}. See jtestforge-specification.md §5.
 *
 * <p>Three steps, in order: parse YAML into a tree, expand {@code ${env:VAR}}
 * references in that tree ({@link EnvVarExpander}), then bind the tree to the record
 * hierarchy. Unknown top-level keys are collected as warnings rather than causing a
 * hard failure (§5.1); this loader does not perform the semantic checks in §5.1 that
 * require the config's own content or the surrounding environment - those belong to
 * {@link ConfigValidator}, run separately against the result.
 */
public final class ConfigLoader {

    private static final Set<String> KNOWN_TOP_LEVEL_KEYS = knownTopLevelKeys();

    private final YAMLMapper mapper;

    public ConfigLoader() {
        this.mapper = YAMLMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public ConfigLoadResult load(Path yamlFile, Map<String, String> environment) {
        JsonNode rawTree = parse(yamlFile);
        List<ConfigViolation> warnings = detectUnknownTopLevelKeys(rawTree);
        JsonNode expandedTree = EnvVarExpander.expand(rawTree, environment);
        JTestForgeConfig config = bind(expandedTree, yamlFile);
        String configHash = ConfigHasher.hash(config);
        return new ConfigLoadResult(config, configHash, yamlFile, warnings);
    }

    private JsonNode parse(Path yamlFile) {
        try {
            return mapper.readTree(Files.readString(yamlFile));
        } catch (IOException e) {
            throw new ConfigLoadException("Failed to read config file: " + yamlFile, e);
        }
    }

    private JTestForgeConfig bind(JsonNode tree, Path yamlFile) {
        try {
            return mapper.treeToValue(tree, JTestForgeConfig.class);
        } catch (JsonProcessingException e) {
            throw new ConfigLoadException(
                    "Failed to parse config file: " + yamlFile + " - " + e.getOriginalMessage(), e);
        }
    }

    private List<ConfigViolation> detectUnknownTopLevelKeys(JsonNode root) {
        List<ConfigViolation> warnings = new ArrayList<>();
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            if (!KNOWN_TOP_LEVEL_KEYS.contains(key)) {
                warnings.add(new ConfigViolation(key,
                        "Unknown top-level configuration key; it will be ignored."));
            }
        }
        return warnings;
    }

    private static Set<String> knownTopLevelKeys() {
        return Arrays.stream(JTestForgeConfig.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
