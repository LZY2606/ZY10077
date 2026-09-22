package com.example.migsb.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class Fp {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Fp() {}

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String specFingerprint(ObjectMapper mapper, Object specTree) {
        try {
            Object value = specTree instanceof com.fasterxml.jackson.databind.JsonNode node
                    ? mapper.treeToValue(node, Object.class) : specTree;
            ObjectMapper canonical = mapper.copy()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            byte[] body = canonical.writer().writeValueAsBytes(value);
            return sha256Hex(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法计算定义指纹", e);
        }
    }

    public static String randomId(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
