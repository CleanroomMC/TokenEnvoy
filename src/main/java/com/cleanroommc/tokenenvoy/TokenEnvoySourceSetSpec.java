/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;

public abstract class TokenEnvoySourceSetSpec implements Named {

    private final String name;
    private final TokenFileFilterSpec classFilter;
    private final TokenFileFilterSpec resourceFilter;

    /**
     * When {@code true}, only {@code processResources} is hooked for this source set.
     * Compiled classes are left untouched. Defaults to {@code false}.
     */
    public abstract Property<Boolean> getResourcesOnly();

    public abstract MapProperty<String, String> getTokens();

    @Inject
    protected abstract ObjectFactory getObjects();

    @Inject
    protected abstract ProviderFactory getProviders();

    @Inject
    protected abstract ProjectLayout getLayout();

    @Inject
    public TokenEnvoySourceSetSpec(String name) {
        this.name = name;
        this.classFilter = getObjects().newInstance(TokenFileFilterSpec.class);
        this.resourceFilter = getObjects().newInstance(TokenFileFilterSpec.class);
        getResourcesOnly().convention(false);
        getTokens().convention(Map.of());
    }

    @Override
    public String getName() {
        return this.name;
    }

    /**
     * Class-file filter for this source set. Unioned with the global
     * {@link TokenEnvoyExtension#getClasses() classes} filter.
     */
    public TokenFileFilterSpec getClasses() {
        return this.classFilter;
    }

    /**
     * Resource filter for this source set. Unioned with the global
     * {@link TokenEnvoyExtension#getResources() resources} filter.
     */
    public TokenFileFilterSpec getResources() {
        return this.resourceFilter;
    }

    public void classes(Action<? super TokenFileFilterSpec> action) {
        action.execute(this.classFilter);
    }

    public void classes(Closure<?> closure) {
        this.classFilter.configure(closure);
    }

    public void resources(Action<? super TokenFileFilterSpec> action) {
        action.execute(this.resourceFilter);
    }

    public void resources(Closure<?> closure) {
        this.resourceFilter.configure(closure);
    }

    public void includeClasses(Object... patterns) {
        this.classFilter.include(patterns);
    }

    public void excludeClasses(Object... patterns) {
        this.classFilter.exclude(patterns);
    }

    public void includeResources(Object... patterns) {
        this.resourceFilter.include(patterns);
    }

    public void excludeResources(Object... patterns) {
        this.resourceFilter.exclude(patterns);
    }

    /**
     * Sets a token for this source set.
     * <p>This overrides a global token of the same name
     *
     * @param token does not include the prefix+suffix with the name. Only do that in your source/resource files.
     */
    public void set(String token, Object value) {
        TokenBindings.put(getTokens(), getProviders(), Logging.getLogger(getClass()), token, value);
    }

    /**
     * Loads {@code NAME=value} entries from a properties file into this source set.
     * Values may contain {@code ${gradleProperty}} placeholders.
     */
    public void set(Object file) {
        TokenBindings.putFile(getTokens(), getObjects(), getProviders(), getLayout(), file);
    }

    static final class TokenBindings {

        private TokenBindings() {}

        static void put(MapProperty<String, String> tokens, ProviderFactory providers, Logger logger, String token, Object value) {
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("Token name must not be empty");
            }
            ProjectCapture.warnIfUnsafe(logger, token, value);
            if (value instanceof Provider<?>) {
                tokens.put(token, stringify(providers, value));
            } else {
                tokens.put(token, stringify(value));
            }
        }

        static void putFile(MapProperty<String, String> tokens, ObjectFactory objects, ProviderFactory providers, ProjectLayout layout, Object file) {
            RegularFileProperty fileProperty = objects.fileProperty();
            Object resolved = file instanceof Provider<?> provider ? provider.get() : file;
            fileProperty.set(resolveFile(layout, resolved));
            Provider<String> contents = providers.fileContents(fileProperty).getAsText();
            Provider<Map<String, String>> interpolation = providers.gradlePropertiesPrefixedBy("");
            tokens.putAll(contents.flatMap(text -> interpolation.map(props -> TokenFiles.read(text, props))));
        }

        private static Provider<String> stringify(ProviderFactory providers, Object value) {
            if (value instanceof Property<?> property) {
                return property.map(TokenBindings::stringify);
            }
            if (value instanceof Provider<?> provider) {
                return provider.map(TokenBindings::stringify);
            }
            return providers.provider(() -> stringify(value));
        }

        private static String stringify(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private static File resolveFile(ProjectLayout layout, Object file) {
            if (file instanceof File resolved) {
                return resolved;
            }
            if (file instanceof Path path) {
                return path.toFile();
            }
            if (file instanceof RegularFile regularFile) {
                return regularFile.getAsFile();
            }
            if (file instanceof Directory directory) {
                return directory.getAsFile();
            }
            String path = file.toString();
            File raw = new File(path);
            if (raw.isAbsolute()) {
                return raw;
            }
            return layout.getProjectDirectory().file(path).getAsFile();
        }

    }

}
