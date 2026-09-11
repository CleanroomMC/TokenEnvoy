/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class TokenFiles {

    private TokenFiles() {}

    public static Map<String, String> read(String contents, Map<String, String> interpolation) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(contents));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to parse token property file", exception);
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            tokens.put(name, interpolate(properties.getProperty(name), interpolation));
        }
        return tokens;
    }

    public static String interpolate(String value, Map<String, String> properties) {
        if (value == null || properties == null || !value.contains("${")) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            int start = value.indexOf("${", index);
            if (start < 0) {
                out.append(value, index, value.length());
                break;
            }
            out.append(value, index, start);
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                out.append(value, start, value.length());
                break;
            }
            String key = value.substring(start + 2, end);
            String replacement = properties.get(key);
            if (replacement != null) {
                out.append(replacement);
            } else {
                out.append(value, start, end + 1);
            }
            index = end + 1;
        }
        return out.toString();
    }

}
