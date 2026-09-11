/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import org.gradle.api.file.FilePermissions;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.RelativePath;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.util.PatternSet;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decides whether a relative class or resource path should receive token replacement.
 * <p>
 * Matching is Gradle Ant-style {@link PatternSet}.
 * Class patterns that look like a fully-qualified name ({@code com.example.Tokens} or
 * {@code com.example.Tokens.class}) are rewritten to {@code com/example/Tokens.class}
 * before matching.
 * Gradle default excludes are not applied: an empty {@code include} list accepts every file,
 * and only the configured {@code exclude} list reject.
 */
public final class TokenPathFilter {

    public enum Kind {

        CLASSES,
        RESOURCES

    }

    private final Spec<FileTreeElement> includes;
    private final Spec<FileTreeElement> excludes;

    private TokenPathFilter(Spec<FileTreeElement> includes, Spec<FileTreeElement> excludes) {
        this.includes = includes;
        this.excludes = excludes;
    }

    public static TokenPathFilter of(Collection<String> includes, Collection<String> excludes, Kind kind) {
        Kind resolved = kind == null ? Kind.RESOURCES : kind;
        return new TokenPathFilter(includeSpec(canonicalize(includes, resolved)), excludeSpec(canonicalize(excludes, resolved)));
    }

    public boolean accepts(FileTreeElement element) {
        if (element == null) {
            return false;
        }
        RelativePath path = element.getRelativePath();
        if (path == null || path.getSegments().length == 0) {
            return false;
        }
        return !this.excludes.isSatisfiedBy(element) && this.includes.isSatisfiedBy(element);
    }

    public boolean accepts(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        return accepts(new PathElement(relativePath));
    }

    private static Spec<FileTreeElement> includeSpec(List<String> patterns) {
        PatternSet patternSet = new PatternSet();
        patternSet.setIncludes(patterns);
        return patternSet.getAsIncludeSpec();
    }

    private static Spec<FileTreeElement> excludeSpec(List<String> patterns) {
        if (patterns.isEmpty()) {
            return Specs.satisfyNone();
        }
        return includeSpec(patterns);
    }

    static List<String> canonicalize(Collection<String> patterns, Kind kind) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String pattern : patterns) {
            String canonical = canonicalizePattern(pattern, kind);
            if (!canonical.isEmpty() && !out.contains(canonical)) {
                out.add(canonical);
            }
        }
        return out;
    }

    private static String canonicalizePattern(String pattern, Kind kind) {
        if (pattern == null) {
            return "";
        }
        String value = pattern.replace('\\', '/').trim();
        if (value.isEmpty() || kind != Kind.CLASSES || isGlob(value) || value.indexOf('/') >= 0) {
            return value;
        }
        if (value.endsWith(".class")) {
            String prefix = value.substring(0, value.length() - ".class".length());
            return isFqcn(prefix) ? toClassFile(prefix) : value;
        }
        return isFqcn(value) ? toClassFile(value) : value;
    }

    private static String toClassFile(String fqcn) {
        return fqcn.replace('.', '/') + ".class";
    }

    private static boolean isGlob(String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
    }

    private static boolean isFqcn(String value) {
        int last = 0;
        boolean sawDot = false;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '.') {
                if (i == last || !isJavaIdentifier(value.substring(last, i))) {
                    return false;
                }
                if (i < value.length()) {
                    sawDot = true;
                }
                last = i + 1;
            }
        }
        return sawDot;
    }

    private static boolean isJavaIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link PatternSet} matches {@link FileTreeElement#getRelativePath()} only. Unused file operations throw.
     */
    private static final class PathElement implements FileTreeElement {

        private final RelativePath relativePath;

        PathElement(String path) {
            this.relativePath = RelativePath.parse(true, path.replace('\\', '/'));
        }

        @Override
        public RelativePath getRelativePath() {
            return this.relativePath;
        }

        @Override
        public String getPath() {
            return this.relativePath.getPathString();
        }

        @Override
        public String getName() {
            return this.relativePath.getLastName();
        }

        @Override
        public boolean isDirectory() {
            return false;
        }

        @Override
        public File getFile() {
            throw unsupported();
        }

        @Override
        public long getLastModified() {
            throw unsupported();
        }

        @Override
        public long getSize() {
            throw unsupported();
        }

        @Override
        public InputStream open() {
            throw unsupported();
        }

        @Override
        public void copyTo(OutputStream output) {
            throw unsupported();
        }

        @Override
        public boolean copyTo(File target) {
            throw unsupported();
        }

        @Override
        public FilePermissions getPermissions() {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Pattern matching does not need the backing file");
        }

    }

}
