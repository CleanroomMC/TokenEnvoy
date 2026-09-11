package com.cleanroommc.tokenenvoy.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LiteralTree;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class TokenJavacPlugin implements Plugin {

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
                tokens.add(Map.entry(input.readUTF(), input.readUTF()));
            }
            for (var patterns : List.of(includes, excludes)) {
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
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.PARSE) {
                    return;
                }
                var unit = event.getCompilationUnit();
                String prefix = unit.getPackageName() == null ? "" : unit.getPackageName().toString().replace('.', '/') + "/";
                TreeScanner<Void, Void> scanner = new TreeScanner<>() {
                    @Override
                    public Void visitLiteral(LiteralTree tree, Void unused) {
                        if (tree.getValue() instanceof String value) {
                            for (var token : tokens) {
                                value = value.replace(token.getKey(), token.getValue());
                            }
                            ((JCLiteral) tree).value = value;
                        }
                        return null;
                    }
                };
                for (var declaration : unit.getTypeDecls()) {
                    if (declaration instanceof ClassTree type && accepts(prefix + type.getSimpleName() + ".class", includes, excludes)) {
                        scanner.scan(type, null);
                    }
                }
                if (accepts(prefix + "package-info.class", includes, excludes)) {
                    scanner.scan(unit.getPackageAnnotations(), null);
                }
                if (accepts("module-info.class", includes, excludes)) {
                    scanner.scan(unit.getModule(), null);
                }
            }
        });
    }

    private static boolean accepts(String path, List<Pattern> includes, List<Pattern> excludes) {
        return (includes.isEmpty() || includes.stream().anyMatch(pattern -> pattern.matcher(path).matches()))
                && excludes.stream().noneMatch(pattern -> pattern.matcher(path).matches());
    }

}
