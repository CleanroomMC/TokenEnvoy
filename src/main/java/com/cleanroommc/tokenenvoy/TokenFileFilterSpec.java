/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import groovy.lang.Closure;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.util.PatternFilterable;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Include/exclude patterns for token replacement on classes or resources.
 * <p>
 * Patterns are Gradle Ant-style ({@link PatternFilterable}).
 * They are relative to the class or resource output root ({@code com/example/Tokens.class}).
 * A name without {@code /} or {@code **} matches only at that root.
 * Use {@code **}{@code /Tokens.class} for any directory.
 * Class patterns also accept a fully-qualified name ({@code com.example.Tokens}).
 * When includes are empty, every file is eligible. Excludes always win.
 */
public abstract class TokenFileFilterSpec {

    public abstract ListProperty<String> getIncludes();

    public abstract ListProperty<String> getExcludes();

    @Inject
    public TokenFileFilterSpec() {
        getIncludes().convention(List.of());
        getExcludes().convention(List.of());
    }

    /**
     * Files that receive token replacement. When none are set, every file is eligible.
     */
    public TokenFileFilterSpec include(Object... patterns) {
        add(getIncludes(), patterns);
        return this;
    }

    /**
     * Files that skip token replacement even if they match an include.
     */
    public TokenFileFilterSpec exclude(Object... patterns) {
        add(getExcludes(), patterns);
        return this;
    }

    TokenFileFilterSpec configure(Closure<?> closure) {
        Closure<?> copy = (Closure<?>) closure.clone();
        copy.setDelegate(this);
        copy.setResolveStrategy(Closure.DELEGATE_FIRST);
        copy.call(this);
        return this;
    }

    private static void add(ListProperty<String> target, Object... patterns) {
        if (patterns == null) {
            return;
        }
        for (Object pattern : patterns) {
            addOne(target, pattern);
        }
    }

    private static void addOne(ListProperty<String> target, Object pattern) {
        if (pattern == null) {
            return;
        }
        if (pattern instanceof Provider<?> provider) {
            target.addAll(provider.map(TokenFileFilterSpec::flatten));
            return;
        }
        if (pattern instanceof Iterable<?> iterable && !(pattern instanceof CharSequence)) {
            for (Object element : iterable) {
                addOne(target, element);
            }
            return;
        }
        if (pattern instanceof Object[] array) {
            for (Object element : array) {
                addOne(target, element);
            }
            return;
        }
        String value = String.valueOf(pattern).trim();
        if (!value.isEmpty()) {
            target.add(value);
        }
    }

    private static List<String> flatten(Object value) {
        List<String> out = new ArrayList<>();
        collect(out, value);
        return out;
    }

    private static void collect(List<String> out, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Provider<?> provider) {
            collect(out, provider.getOrNull());
            return;
        }
        if (value instanceof Iterable<?> iterable && !(value instanceof CharSequence)) {
            for (Object element : iterable) {
                collect(out, element);
            }
            return;
        }
        if (value instanceof Object[] array) {
            for (Object element : array) {
                collect(out, element);
            }
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            out.add(text);
        }
    }

}
