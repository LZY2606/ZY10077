package com.example.migsb.engine;

import java.util.regex.Pattern;

public final class Ident {
    private static final Pattern SIMPLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SCHEMA = Pattern.compile("SBX_[0-9a-f]{8,32}");

    private Ident() {}

    public static String check(String name) {
        if (name == null || !SIMPLE.matcher(name).matches()) {
            throw new IllegalArgumentException("非法标识符: " + name);
        }
        return name;
    }

    public static String schema(String schema) {
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("非法沙箱模式名: " + schema);
        }
        return schema;
    }

    public static String q(String schema, String table) {
        return "\"" + schema(schema) + "\".\"" + check(table) + "\"";
    }

    public static String qColumn(String column) {
        return "\"" + check(column) + "\"";
    }
}
