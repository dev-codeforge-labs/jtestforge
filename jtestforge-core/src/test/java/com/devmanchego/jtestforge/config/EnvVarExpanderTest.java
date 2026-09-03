package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvVarExpanderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expandsAPlaceholderInsideAStringLeaf() {
        ObjectNode root = mapper.createObjectNode();
        root.put("modulePath", "${env:JTF_MODULE}/core");

        JsonNode expanded = EnvVarExpander.expand(root, Map.of("JTF_MODULE", "C:/work/app"));

        assertThat(expanded.get("modulePath").asText()).isEqualTo("C:/work/app/core");
    }

    @Test
    void expandsMultiplePlaceholdersInsideOneString() {
        ObjectNode root = mapper.createObjectNode();
        root.put("value", "${env:A}-${env:B}");

        JsonNode expanded = EnvVarExpander.expand(root, Map.of("A", "left", "B", "right"));

        assertThat(expanded.get("value").asText()).isEqualTo("left-right");
    }

    @Test
    void expandsInsideNestedObjectsAndArrays() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode nested = root.putObject("aiProvider").putObject("providers").putObject("claude");
        nested.putArray("args").add("${env:FLAG}").add("literal");

        JsonNode expanded = EnvVarExpander.expand(root, Map.of("FLAG", "--verbose"));

        JsonNode args = expanded.get("aiProvider").get("providers").get("claude").get("args");
        assertThat(args.get(0).asText()).isEqualTo("--verbose");
        assertThat(args.get(1).asText()).isEqualTo("literal");
    }

    @Test
    void leavesStringsWithoutPlaceholdersUnchanged() {
        ObjectNode root = mapper.createObjectNode();
        root.put("value", "plain text, no placeholders here");

        JsonNode expanded = EnvVarExpander.expand(root, Map.of());

        assertThat(expanded.get("value").asText()).isEqualTo("plain text, no placeholders here");
    }

    @Test
    void throwsAClearErrorWhenTheReferencedVariableIsNotSet() {
        ObjectNode root = mapper.createObjectNode();
        root.put("value", "${env:DOES_NOT_EXIST}");

        assertThatThrownBy(() -> EnvVarExpander.expand(root, Map.of()))
                .isInstanceOf(ConfigLoadException.class)
                .hasMessageContaining("DOES_NOT_EXIST");
    }

    @Test
    void leavesNonTextualLeavesUntouched() {
        ObjectNode root = mapper.createObjectNode();
        root.put("timeoutSeconds", 300);
        root.put("dryRun", false);

        JsonNode expanded = EnvVarExpander.expand(root, Map.of());

        assertThat(expanded.get("timeoutSeconds").asInt()).isEqualTo(300);
        assertThat(expanded.get("dryRun").asBoolean()).isFalse();
    }
}
