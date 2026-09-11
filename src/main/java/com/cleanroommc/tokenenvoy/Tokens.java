/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import org.objectweb.asm.ConstantDynamic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces {@code @{NAME}} markers. The DSL and property files use the bare {@code NAME}.
 */
public final class Tokens {

    public static final String PREFIX = "@{";
    public static final String SUFFIX = "}";

    private Tokens() {}

    public static String placeholder(String name) {
        return PREFIX + name + SUFFIX;
    }

    public static List<Map.Entry<String, String>> ordered(Map<String, String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, String>> ordered = new ArrayList<>();
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                continue;
            }
            ordered.add(Map.entry(placeholder(entry.getKey()), entry.getValue()));
        }
        ordered.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed());
        return List.copyOf(ordered);
    }

    public static String replace(String input, Map<String, String> tokens) {
        return replace(input, ordered(tokens));
    }

    public static String replace(String input, List<Map.Entry<String, String>> tokens) {
        if (input == null || tokens.isEmpty()) {
            return input;
        }
        String result = input;
        for (Map.Entry<String, String> entry : tokens) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public static Object replaceValue(Object value, List<Map.Entry<String, String>> tokens) {
        if (value instanceof String string) {
            return replace(string, tokens);
        }
        if (value instanceof ConstantDynamic dynamic) {
            return replaceConstantDynamic(dynamic, tokens);
        }
        return value;
    }

    public static Object[] replaceValues(Object[] values, List<Map.Entry<String, String>> tokens) {
        if (values == null || values.length == 0 || tokens.isEmpty()) {
            return values;
        }
        Object[] replaced = values;
        for (int i = 0; i < values.length; i++) {
            Object original = values[i];
            Object next = replaceValue(original, tokens);
            if (next != original) {
                if (replaced == values) {
                    replaced = values.clone();
                }
                replaced[i] = next;
            }
        }
        return replaced;
    }

    public static Map<String, String> merge(Map<String, String> globals, Map<String, String> locals) {
        if ((globals == null || globals.isEmpty()) && (locals == null || locals.isEmpty())) {
            return Map.of();
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (globals != null) {
            merged.putAll(globals);
        }
        if (locals != null) {
            merged.putAll(locals);
        }
        return merged;
    }

    private static ConstantDynamic replaceConstantDynamic(ConstantDynamic dynamic, List<Map.Entry<String, String>> tokens) {
        int count = dynamic.getBootstrapMethodArgumentCount();
        Object[] args = new Object[count];
        boolean changed = false;
        for (int i = 0; i < count; i++) {
            Object original = dynamic.getBootstrapMethodArgument(i);
            Object next = replaceValue(original, tokens);
            args[i] = next;
            changed |= next != original;
        }
        if (!changed) {
            return dynamic;
        }
        return new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(), dynamic.getBootstrapMethod(), args);
    }

}
