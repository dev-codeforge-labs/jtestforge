package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code ${env:VAR}} references inside string values of a parsed YAML tree,
 * per jtestforge-specification.md §5. Applied before binding to {@link JTestForgeConfig},
 * so the bound records never see the raw placeholder syntax.
 *
 * <p>A referenced variable that is not present in {@code environment} is a hard failure
 * at load time (not deferred to {@link ConfigValidator}): a config that cannot even be
 * fully read cannot be meaningfully validated either.
 */
public final class EnvVarExpander {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvVarExpander() {
    }

    public static JsonNode expand(JsonNode root, Map<String, String> environment) {
        return expandNode(root, environment);
    }

    private static JsonNode expandNode(JsonNode node, Map<String, String> environment) {
        if (node.isTextual()) {
            return new TextNode(expandString(node.textValue(), environment));
        }
        if (node.isObject()) {
            ObjectNode result = ((ObjectNode) node).objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), expandNode(field.getValue(), environment));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = ((ArrayNode) node).arrayNode();
            for (JsonNode element : node) {
                result.add(expandNode(element, environment));
            }
            return result;
        }
        return node;
    }

    private static String expandString(String value, Map<String, String> environment) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String resolved = environment.get(variableName);
            if (resolved == null) {
                throw new ConfigLoadException(
                        "Config references ${env:%s}, but no such environment variable is set."
                                .formatted(variableName));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
