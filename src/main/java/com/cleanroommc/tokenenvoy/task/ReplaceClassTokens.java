/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy.task;

import com.cleanroommc.tokenenvoy.TokenPathFilter;
import com.cleanroommc.tokenenvoy.asm.TokenClassTransformer;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileType;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@CacheableTask
public abstract class ReplaceClassTokens extends DefaultTask {

    @Input
    @Optional
    public abstract MapProperty<String, String> getTokens();

    @Input
    @Optional
    public abstract ListProperty<String> getIncludes();

    @Input
    @Optional
    public abstract ListProperty<String> getExcludes();

    @Incremental
    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassFiles();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    public ReplaceClassTokens() {
        getTokens().convention(Map.of());
        getIncludes().convention(List.of());
        getExcludes().convention(List.of());
    }

    @TaskAction
    public void replace(InputChanges changes) {
        Path outputRoot = getOutputDirectory().get().getAsFile().toPath();
        Map<String, String> tokens = getTokens().getOrElse(Map.of());
        TokenPathFilter filter = TokenPathFilter.of(getIncludes().get(), getExcludes().get(), TokenPathFilter.Kind.CLASSES);
        try {
            if (!changes.isIncremental()) {
                deleteRecursively(outputRoot);
            }
            Files.createDirectories(outputRoot);
            for (FileChange change : changes.getFileChanges(getClassFiles())) {
                if (change.getFileType() == FileType.DIRECTORY) {
                    continue;
                }
                Path target = outputRoot.resolve(change.getNormalizedPath());
                if (change.getChangeType() == ChangeType.REMOVED) {
                    Files.deleteIfExists(target);
                    deleteEmptyParents(target, outputRoot);
                    continue;
                }
                Files.createDirectories(target.getParent());
                copyOrTransform(change.getFile().toPath(), target, tokens, filter, change.getNormalizedPath());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to replace tokens in class files", exception);
        }
    }

    private static void copyOrTransform(Path source, Path target, Map<String, String> tokens, TokenPathFilter filter, String relativePath) throws IOException {
        String fileName = source.getFileName().toString();
        if (!fileName.endsWith(".class") || !filter.accepts(relativePath)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try {
            byte[] transformed = TokenClassTransformer.transform(Files.readAllBytes(source), tokens);
            Files.write(target, transformed);
        } catch (IOException | RuntimeException exception) {
            throw new UncheckedIOException(new IOException("Failed to replace tokens in " + source, exception));
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }

    private static void deleteEmptyParents(Path file, Path root) throws IOException {
        Path parent = file.getParent();
        while (parent != null && parent.startsWith(root) && !parent.equals(root)) {
            try (Stream<Path> children = Files.list(parent)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(parent);
            parent = parent.getParent();
        }
    }

}
