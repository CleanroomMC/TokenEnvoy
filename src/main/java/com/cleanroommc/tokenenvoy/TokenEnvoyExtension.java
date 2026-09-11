/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import groovy.lang.Closure;
import groovy.lang.MissingMethodException;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.ProviderFactory;

import javax.inject.Inject;
import java.util.Map;

public abstract class TokenEnvoyExtension {

    private final NamedDomainObjectContainer<TokenEnvoySourceSetSpec> sourceSets;
    private final TokenFileFilterSpec classFilter;
    private final TokenFileFilterSpec resourceFilter;

    /**
     * Tokens applied to every source set. A source-set {@code set} of the same name wins.
     */
    public abstract MapProperty<String, String> getTokens();

    @Inject
    protected abstract ObjectFactory getObjects();

    @Inject
    protected abstract ProviderFactory getProviders();

    @Inject
    protected abstract ProjectLayout getLayout();

    @Inject
    public TokenEnvoyExtension(ObjectFactory objects) {
        this.sourceSets = objects.domainObjectContainer(TokenEnvoySourceSetSpec.class, name -> objects.newInstance(TokenEnvoySourceSetSpec.class, name));
        this.classFilter = objects.newInstance(TokenFileFilterSpec.class);
        this.resourceFilter = objects.newInstance(TokenFileFilterSpec.class);
        getTokens().convention(Map.of());
    }

    /**
     * Global class-file filter. Applied to every source set, then unioned with
     * that source set's own {@link TokenEnvoySourceSetSpec#getClasses() classes} filter.
     */
    public TokenFileFilterSpec getClasses() {
        return this.classFilter;
    }

    /**
     * Global resource filter. Applied to every source set, then unioned with
     * that source set's own {@link TokenEnvoySourceSetSpec#getResources() resources} filter.
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

    public NamedDomainObjectContainer<TokenEnvoySourceSetSpec> getSourceSets() {
        return this.sourceSets;
    }

    public void sourceSets(Action<? super NamedDomainObjectContainer<TokenEnvoySourceSetSpec>> action) {
        action.execute(this.sourceSets);
    }

    public TokenEnvoySourceSetSpec sourceSet(String name) {
        return this.sourceSets.maybeCreate(name);
    }

    public void sourceSet(String name, Action<? super TokenEnvoySourceSetSpec> action) {
        action.execute(this.sourceSets.maybeCreate(name));
    }

    /**
     * Sets a token for every source set.
     * {@code name} is the bare token name.
     * Classes and resources must write {@code @{name}}.
     */
    public void set(String token, Object value) {
        TokenEnvoySourceSetSpec.TokenBindings.put(getTokens(), getProviders(), Logging.getLogger(getClass()), token, value);
    }

    /**
     * Loads {@code NAME=value} entries from a properties file as global tokens.
     * Values may contain {@code ${gradleProperty}} placeholders.
     */
    public void set(Object file) {
        TokenEnvoySourceSetSpec.TokenBindings.putFile(getTokens(), getObjects(), getProviders(), getLayout(), file);
    }

    /**
     * Groovy: {@code main { ... }} configures the source set of that name.
     */
    @SuppressWarnings("unused")
    public Object methodMissing(String name, Object args) {
        Object[] arguments = args instanceof Object[] objects ? objects : new Object[] { args };
        if (arguments.length == 1) {
            TokenEnvoySourceSetSpec spec = this.sourceSets.maybeCreate(name);
            Object argument = arguments[0];
            if (argument instanceof Closure<?> closure) {
                configure(spec, closure);
                return spec;
            }
            if (argument instanceof Action<?> action) {
                @SuppressWarnings("unchecked") Action<TokenEnvoySourceSetSpec> typed = (Action<TokenEnvoySourceSetSpec>) action;
                typed.execute(spec);
                return spec;
            }
        }
        throw new MissingMethodException(name, getClass(), arguments);
    }

    private static void configure(Object target, Closure<?> closure) {
        Closure<?> copy = (Closure<?>) closure.clone();
        copy.setDelegate(target);
        copy.setResolveStrategy(Closure.DELEGATE_FIRST);
        copy.call(target);
    }

}
