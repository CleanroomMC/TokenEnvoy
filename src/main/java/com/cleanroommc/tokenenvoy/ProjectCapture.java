/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import groovy.lang.Closure;
import groovy.lang.Script;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Detects token values that will not survive the configuration cache because they
 * close over {@link Project} (or are a live {@code provider { }} that easily can).
 */
final class ProjectCapture {

    private static final int MAX_DEPTH = 8;

    private ProjectCapture() {}

    static void warnIfUnsafe(Logger logger, String token, Object value) {
        String reason = diagnose(value);
        if (reason == null) {
            return;
        }
        logger.warn(
                "[Token Envoy] Token '{}' {}. Breaks the configuration cache. " + "Pass a realized value (set '{}', project.version) " +
                        "or providers.gradleProperty('name'), not provider { project.... }.",
                token,
                reason,
                token
        );
    }

    static String diagnose(Object value) {
        if (captures(0, value)) {
            return "captures Project";
        }
        if (isUserCallableProvider(value)) {
            return "is a live provider { }";
        }
        return null;
    }

    static boolean captures(int depth, Object value) {
        return captures(value, depth, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    static boolean isUserCallableProvider(Object value) {
        if (!(value instanceof Provider<?>) || value instanceof Property<?>) {
            return false;
        }
        // DefaultProvider is Project.provider { } / providers.provider { }.
        // Detect by type name so we do not need to read Gradle-internal fields
        String type = value.getClass().getName();
        return type.contains("DefaultProvider");
    }

    private static boolean captures(Object value, int depth, Set<Object> seen) {
        if (value == null || depth > MAX_DEPTH || !seen.add(value)) {
            return false;
        }
        if (value instanceof Project) {
            return true;
        }
        if (value instanceof Closure<?> closure) {
            return isProject(closure.getOwner()) || isProject(closure.getDelegate()) || isProject(closure.getThisObject());
        }
        if (isLambda(value.getClass()) && fieldsIncludeProject(value)) {
            return true;
        }
        if (value instanceof Provider<?>) {
            return captures(readField(value, "value"), depth + 1, seen) ||
                    captures(readField(value, "transformer"), depth + 1, seen) ||
                    captures(readField(value, "provider"), depth + 1, seen);
        }
        if (value instanceof Callable<?> || value instanceof Supplier<?>) {
            return fieldsIncludeProject(value) || captures(readField(value, "value"), depth + 1, seen);
        }
        return false;
    }

    private static boolean fieldsIncludeProject(Object value) {
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Project.class.isAssignableFrom(field.getType()) || Script.class.isAssignableFrom(field.getType())) {
                    return true;
                }
                if (isLambda(field.getType()) || Closure.class.isAssignableFrom(field.getType())) {
                    Object nested = read(field, value);
                    if (nested != null && nested != value && captures(1, nested)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Object readField(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                return read(type.getDeclaredField(name), owner);
            } catch (NoSuchFieldException ignored) {
                // Keep walking up the hierarchy
            }
        }
        return null;
    }

    private static Object read(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isProject(Object value) {
        return value instanceof Project;
    }

    private static boolean isLambda(Class<?> type) {
        return type.isSynthetic() || type.getName().contains("$$Lambda");
    }

}
