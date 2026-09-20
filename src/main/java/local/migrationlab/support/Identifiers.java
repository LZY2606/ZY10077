package local.migrationlab.support;

import com.fasterxml.jackson.databind.JsonNode;

public final class Identifiers {
    private Identifiers() {
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing text field: " + field);
        }
        return value.asText();
    }
}
