/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.tree.JCTree.JCLiteral;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java 8 counterpart of the Java 21 plugin under {@code META-INF/versions/21}, for compilers that load the jar root.
 */
public final class TokenJavacPlugin implements Plugin {

    // Java 9-20 compilers load this class too, so module-info is still reached where the compiler has it
    private static final Method GET_MODULE = getModule();

    @Override
    public String getName() {
        return "TokenEnvoy";
    }

    @Override
    public void init(JavacTask task, String... args) {
        List<Map.Entry<String, String>> tokens = new ArrayList<>();
        List<Pattern> includes = new ArrayList<>();
        List<Pattern> excludes = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(args[0])))) {
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                tokens.add(new SimpleImmutableEntry<>(input.readUTF(), input.readUTF()));
            }
            for (List<Pattern> patterns : Arrays.asList(includes, excludes)) {
                count = input.readInt();
                for (int i = 0; i < count; i++) {
                    patterns.add(Pattern.compile(input.readUTF()));
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        // PARSE finishes before javac computes constants, switch hashes, and inlined values
        task.addTaskListener(new TaskListener() {

            @Override
            public void started(TaskEvent event) {}

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.PARSE) {
                    return;
                }
                CompilationUnitTree unit = event.getCompilationUnit();
                String prefix = unit.getPackageName() == null ? "" : unit.getPackageName().toString().replace('.', '/') + "/";
                TreeScanner<Void, Void> scanner = new TreeScanner<Void, Void>() {

                    @Override
                    public Void visitLiteral(LiteralTree tree, Void unused) {
                        if (tree.getValue() instanceof String) {
                            String value = (String) tree.getValue();
                            for (Map.Entry<String, String> token : tokens) {
                                value = value.replace(token.getKey(), token.getValue());
                            }
                            ((JCLiteral) tree).value = value;
                        }
                        return null;
                    }

                };
                for (Tree declaration : unit.getTypeDecls()) {
                    if (declaration instanceof ClassTree && accepts(prefix + ((ClassTree) declaration).getSimpleName() + ".class", includes, excludes)) {
                        scanner.scan(declaration, null);
                    }
                }
                if (accepts(prefix + "package-info.class", includes, excludes)) {
                    scanner.scan(unit.getPackageAnnotations(), null);
                }
                if (GET_MODULE != null && accepts("module-info.class", includes, excludes)) {
                    scanner.scan(module(unit), null);
                }
            }

        });
    }

    private static boolean accepts(String path, List<Pattern> includes, List<Pattern> excludes) {
        return (includes.isEmpty() || includes.stream().anyMatch(pattern -> pattern.matcher(path).matches())) &&
                excludes.stream().noneMatch(pattern -> pattern.matcher(path).matches());
    }

    private static Method getModule() {
        try {
            return CompilationUnitTree.class.getMethod("getModule");
        } catch (NoSuchMethodException java8) {
            return null;
        }
    }

    private static Tree module(CompilationUnitTree unit) {
        try {
            return (Tree) GET_MODULE.invoke(unit);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
