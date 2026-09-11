/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import java.util.Map;

class TokenFilesTest {

    @Test
    void readsPropertiesAndInterpolatesValues() {
        Map<String, String> tokens = TokenFiles.read(
                """
                VERSION=${mod_version}
                MOD_ID=plain
                """,
                Map.of("mod_version", "4.0.0")
        );
        assertThat(tokens.get("VERSION")).isEqualTo("4.0.0");
        assertThat(tokens.get("MOD_ID")).isEqualTo("plain");
    }

    @Test
    void leavesUnknownPlaceholdersInPlace() {
        assertThat(TokenFiles.interpolate("${missing}", Map.of("other", "x"))).isEqualTo("${missing}");
    }

    @Test
    void interpolatesMultiplePlaceholders() {
        assertThat(TokenFiles.interpolate("${one}-${two}", Map.of("one", "a", "two", "b"))).isEqualTo("a-b");
    }

}
