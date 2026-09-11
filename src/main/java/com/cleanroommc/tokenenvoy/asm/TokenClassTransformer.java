/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy.asm;

import com.cleanroommc.tokenenvoy.Tokens;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.util.List;
import java.util.Map;

public final class TokenClassTransformer {

    public static byte[] transform(byte[] classBytes, Map<String, String> tokens) {
        List<Map.Entry<String, String>> ordered = Tokens.ordered(tokens);
        if (ordered.isEmpty()) {
            return classBytes;
        }
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classBytes).accept(new TokenClassVisitor(writer, ordered), 0);
        return writer.toByteArray();
    }

    private TokenClassTransformer() {}

}
