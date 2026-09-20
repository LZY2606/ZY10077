package local.migrationlab.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Json {
    public static final String RULE_VERSION = "migration-lab.rules.v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.findAndRegisterModules();
        MAPPER.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JsonNode read(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize JSON", e);
        }
    }

    public static String canonical(JsonNode node) {
        try {
            Object normalized = MAPPER.treeToValue(node, Object.class);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(normalize(normalized));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot canonicalize JSON", e);
        }
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : hash) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> payload(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Payload keys and values must be paired");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())))
                    .forEach(entry -> result.put(String.valueOf(entry.getKey()), normalize(entry.getValue())));
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> result = new java.util.ArrayList<>();
            for (Object item : iterable) {
                result.add(normalize(item));
            }
            return result;
        }
        return value;
    }
}
