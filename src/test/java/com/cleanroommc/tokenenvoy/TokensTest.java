/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

class TokensTest {

    @Test
    void test() {
        assertThat(Tokens.placeholder("VERSION")).isEqualTo("@{VERSION}");
        assertThat(Tokens.replace("@{VERSION}", Map.of("VERSION", "1.2.3"))).isEqualTo("1.2.3");
        assertThat(Tokens.replace("mod=@{MOD_ID}", Map.of("MOD_ID", "example"))).isEqualTo("mod=example");
    }

    @Test
    void doesNotReplaceTokenlessNames() {
        assertThat(Tokens.replace("VERSION", Map.of("VERSION", "1.2.3"))).isEqualTo("VERSION");
    }

    @Test
    void skipsEmptyKeysAndNullValues() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("", "nope");
        tokens.put("MISSING", null);
        tokens.put("OK", "yes");
        assertThat(Tokens.replace("@{OK}", tokens)).isEqualTo("yes");
        assertThat(Tokens.replace("@{MISSING}", tokens)).isEqualTo("@{MISSING}");
    }

    @Test
    void leavesNullInputAlone() {
        assertThat(Tokens.replace(null, Map.of("A", "b"))).isNull();
    }

}
