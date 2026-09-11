/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy.resource;

import com.cleanroommc.tokenenvoy.TokenPathFilter;
import com.cleanroommc.tokenenvoy.Tokens;
import org.gradle.api.Action;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.provider.Provider;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Applies token replacement to text resources copied by {@code processResources}.
 */
public final class TokenResourceAction implements Action<FileCopyDetails>, Serializable {

    private final Provider<Map<String, String>> tokens;
    private final Provider<List<String>> includes;
    private final Provider<List<String>> excludes;
    private transient TokenPathFilter filter;

    public TokenResourceAction(Provider<Map<String, String>> tokens, Provider<List<String>> includes, Provider<List<String>> excludes) {
        this.tokens = tokens;
        this.includes = includes;
        this.excludes = excludes;
    }

    @Override
    public void execute(FileCopyDetails details) {
        if (TokenResources.isBinary(details.getSourceName())) {
            return;
        }
        Map<String, String> map = this.tokens.getOrElse(Map.of());
        if (map.isEmpty()) {
            return;
        }
        if (!filter().accepts(details)) {
            return;
        }
        List<Map.Entry<String, String>> ordered = Tokens.ordered(map);
        details.filter(line -> line == null ? null : Tokens.replace(line, ordered));
    }

    private TokenPathFilter filter() {
        TokenPathFilter cached = this.filter;
        if (cached == null) {
            cached = TokenPathFilter.of(this.includes.get(), this.excludes.get(), TokenPathFilter.Kind.RESOURCES);
            this.filter = cached;
        }
        return cached;
    }

}
