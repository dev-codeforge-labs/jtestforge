package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.Locale;

/**
 * Reads {@code spring.enabled} from either a YAML boolean ({@code true}/{@code false})
 * or the string {@code "auto"}. A standard enum deserializer cannot handle this because
 * the two forms parse to different JSON node types (boolean vs. string).
 */
final class SpringEnabledModeDeserializer extends JsonDeserializer<SpringEnabledMode> {

    @Override
    public SpringEnabledMode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return switch (parser.currentToken()) {
            case VALUE_TRUE -> SpringEnabledMode.ENABLED;
            case VALUE_FALSE -> SpringEnabledMode.DISABLED;
            case VALUE_STRING -> parseString(parser.getValueAsString(), parser, context);
            default -> throw context.weirdStringException(
                    parser.getText(), SpringEnabledMode.class,
                    "spring.enabled must be true, false, or \"auto\"");
        };
    }

    private SpringEnabledMode parseString(String value, JsonParser parser, DeserializationContext context)
            throws IOException {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto" -> SpringEnabledMode.AUTO;
            case "true" -> SpringEnabledMode.ENABLED;
            case "false" -> SpringEnabledMode.DISABLED;
            default -> throw context.weirdStringException(
                    value, SpringEnabledMode.class,
                    "spring.enabled must be true, false, or \"auto\", was \"" + value + "\"");
        };
    }
}
